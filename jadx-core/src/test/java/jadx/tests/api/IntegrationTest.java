package jadx.tests.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JadxInternalAccess;
import jadx.core.ProcessClass;
import jadx.core.codegen.CodeGen;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.AttrList;
import jadx.core.dex.attributes.IAttributeNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.DepthTraversal;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.xmlgen.ResourceStorage;
import jadx.core.xmlgen.entry.ResourceEntry;
import jadx.tests.api.compiler.DynamicCompiler;
import jadx.tests.api.compiler.StaticCompiler;
import jadx.tests.api.utils.TestUtils;

import static jadx.core.utils.files.FileUtils.addFileToJar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class IntegrationTest extends TestUtils {

	private static final String TEST_DIRECTORY = "src/test/java";
	private static final String TEST_DIRECTORY2 = "jadx-core/" + TEST_DIRECTORY;

	/**
	 * Run auto check method if defined:
	 * <pre>
	 *     public void check() {}
	 * </pre>
	 */
	public static final String CHECK_METHOD_NAME = "check";

	protected JadxArgs args;

	protected boolean deleteTmpFiles = true;
	protected boolean withDebugInfo = true;
	protected boolean unloadCls = true;

	protected Map<Integer, String> resMap = Collections.emptyMap();

	protected String outDir = "test-out-tmp";

	protected boolean compile = true;
	private DynamicCompiler dynamicCompiler;

	public IntegrationTest() {
		args = new JadxArgs();
		args.setOutDir(new File(outDir));
		args.setShowInconsistentCode(true);
		args.setThreadsCount(1);
		args.setSkipResources(true);
		args.setFsCaseSensitive(false); // use same value on all systems
	}

	public String getTestName() {
		return this.getClass().getSimpleName();
	}

	public String getTestPkg() {
		return this.getClass().getPackage().getName().replace("jadx.tests.integration.", "");
	}

	public ClassNode getClassNode(Class<?> clazz) {
		try {
			File jar = getJarForClass(clazz);
			return getClassNodeFromFile(jar, clazz.getName());
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		return null;
	}

	public ClassNode getClassNodeFromFile(File file, String clsName) {
		JadxDecompiler d = loadFiles(Collections.singletonList(file));
		RootNode root = JadxInternalAccess.getRoot(d);

		ClassNode cls = root.searchClassByName(clsName);
		assertThat("Class not found: " + clsName, cls, notNullValue());
		assertThat(clsName, is(cls.getClassInfo().getFullName()));

		decompileAndCheckCls(d, cls);
		return cls;
	}

	protected JadxDecompiler loadFiles(List<File> inputFiles) {
		JadxDecompiler d = null;
		try {
			args.setInputFiles(inputFiles);
			d = new JadxDecompiler(args);
			d.load();
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		RootNode root = JadxInternalAccess.getRoot(d);
		insertResources(root);
		return d;
	}

	protected void decompileAndCheckCls(JadxDecompiler d, ClassNode cls) {
		if (unloadCls) {
			decompile(d, cls);
		} else {
			decompileWithoutUnload(d, cls);
		}

		System.out.println("-----------------------------------------------------------");
		System.out.println(cls.getCode());
		System.out.println("-----------------------------------------------------------");

		checkCode(cls);
		compile(cls);
		runAutoCheck(cls.getClassInfo().getFullName());
	}

	private void insertResources(RootNode root) {
		if (resMap.isEmpty()) {
			return;
		}
		ResourceStorage resStorage = new ResourceStorage();
		for (Map.Entry<Integer, String> entry : resMap.entrySet()) {
			Integer id = entry.getKey();
			String name = entry.getValue();
			String[] parts = name.split("\\.");
			resStorage.add(new ResourceEntry(id, "", parts[0], parts[1]));
		}
		root.processResources(resStorage);
	}

	protected void decompile(JadxDecompiler jadx, ClassNode cls) {
		List<IDexTreeVisitor> passes = JadxInternalAccess.getPassList(jadx);
		ProcessClass.process(cls, passes, true);
	}

	protected void decompileWithoutUnload(JadxDecompiler jadx, ClassNode cls) {
		cls.load();
		for (IDexTreeVisitor visitor : JadxInternalAccess.getPassList(jadx)) {
			DepthTraversal.visit(visitor, cls);
		}
		generateClsCode(cls);
		// don't unload class
	}

	protected void generateClsCode(ClassNode cls) {
		try {
			CodeGen.generate(cls);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	protected static void checkCode(ClassNode cls) {
		assertFalse(hasErrors(cls), "Inconsistent cls: " + cls);
		for (MethodNode mthNode : cls.getMethods()) {
			assertFalse(hasErrors(mthNode), "Method with problems: " + mthNode);
		}
		assertThat(cls.getCode().toString(), not(containsString("inconsistent")));
	}

	private static boolean hasErrors(IAttributeNode node) {
		if (node.contains(AFlag.INCONSISTENT_CODE)
				|| node.contains(AType.JADX_ERROR)
				|| node.contains(AType.JADX_WARN)) {
			return true;
		}
		AttrList<String> commentsAttr = node.get(AType.COMMENTS);
		if (commentsAttr != null) {
			for (String comment : commentsAttr.getList()) {
				if (comment.contains("JADX WARN")) {
					return true;
				}
			}
		}
		return false;
	}

	private void runAutoCheck(String clsName) {
		try {
			// run 'check' method from original class
			Class<?> origCls;
			try {
				origCls = Class.forName(clsName);
			} catch (ClassNotFoundException e) {
				// ignore
				return;
			}
			Method checkMth;
			try {
				checkMth = origCls.getMethod(CHECK_METHOD_NAME);
			} catch (NoSuchMethodException e) {
				// ignore
				return;
			}
			if (!checkMth.getReturnType().equals(void.class)
					|| !Modifier.isPublic(checkMth.getModifiers())
					|| Modifier.isStatic(checkMth.getModifiers())) {
				fail("Wrong 'check' method");
				return;
			}
			try {
				checkMth.invoke(origCls.getConstructor().newInstance());
			} catch (InvocationTargetException ie) {
				rethrow("Original check failed", ie);
			}
			// run 'check' method from decompiled class
			if (compile) {
				try {
					invoke("check");
				} catch (InvocationTargetException ie) {
					rethrow("Decompiled check failed", ie);
				}
				System.out.println("Auto check: PASSED");
			}
		} catch (Exception e) {
			e.printStackTrace();
			fail("Auto check exception: " + e.getMessage());
		}
	}

	private void rethrow(String msg, InvocationTargetException ie) {
		Throwable cause = ie.getCause();
		if (cause instanceof AssertionError) {
			System.err.println(msg);
			throw (AssertionError) cause;
		} else {
			cause.printStackTrace();
			fail(msg + cause.getMessage());
		}
	}

	protected MethodNode getMethod(ClassNode cls, String method) {
		for (MethodNode mth : cls.getMethods()) {
			if (mth.getName().equals(method)) {
				return mth;
			}
		}
		fail("Method not found " + method + " in class " + cls);
		return null;
	}

	void compile(ClassNode cls) {
		if (!compile) {
			return;
		}
		try {
			dynamicCompiler = new DynamicCompiler(cls);
			boolean result = dynamicCompiler.compile();
			assertTrue(result, "Compilation failed");
			System.out.println("Compilation: PASSED");
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	public Object invoke(String method) throws Exception {
		return invoke(method, new Class<?>[0]);
	}

	public Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
		Method mth = getReflectMethod(method, types);
		return invoke(mth, args);
	}

	public Method getReflectMethod(String method, Class<?>... types) {
		assertNotNull(dynamicCompiler, "dynamicCompiler not ready");
		try {
			return dynamicCompiler.getMethod(method, types);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		return null;
	}

	public Object invoke(Method mth, Object... args) throws Exception {
		assertNotNull(dynamicCompiler, "dynamicCompiler not ready");
		assertNotNull(mth, "unknown method");
		return dynamicCompiler.invoke(mth, args);
	}

	public File getJarForClass(Class<?> cls) throws IOException {
		String path = cls.getPackage().getName().replace('.', '/');
		List<File> list;
		if (!withDebugInfo) {
			list = compileClass(cls);
		} else {
			list = getClassFilesWithInners(cls);
			if (list.isEmpty()) {
				list = compileClass(cls);
			}
		}
		assertThat("File list is empty", list, not(empty()));

		File temp = createTempFile(".jar");
		try (JarOutputStream jo = new JarOutputStream(new FileOutputStream(temp))) {
			for (File file : list) {
				addFileToJar(jo, file, path + '/' + file.getName());
			}
		}
		return temp;
	}

	protected File createTempFile(String suffix) {
		File temp = null;
		try {
			temp = File.createTempFile("jadx-tmp-", System.nanoTime() + suffix);
			if (deleteTmpFiles) {
				temp.deleteOnExit();
			} else {
				System.out.println("Temporary file path: " + temp.getAbsolutePath());
			}
		} catch (IOException e) {
			fail(e.getMessage());
		}
		return temp;
	}

	private static File createTempDir(String prefix) throws IOException {
		File baseDir = new File(System.getProperty("java.io.tmpdir"));
		String baseName = prefix + '-' + System.nanoTime();
		for (int counter = 1; counter < 1000; counter++) {
			File tempDir = new File(baseDir, baseName + counter);
			if (tempDir.mkdir()) {
				return tempDir;
			}
		}
		throw new IOException("Failed to create temp directory");
	}

	private List<File> getClassFilesWithInners(Class<?> cls) {
		List<File> list = new ArrayList<>();
		String pkgName = cls.getPackage().getName();
		URL pkgResource = ClassLoader.getSystemClassLoader().getResource(pkgName.replace('.', '/'));
		if (pkgResource != null) {
			try {
				String clsName = cls.getName();
				File directory = new File(pkgResource.toURI());
				String[] files = directory.list();
				for (String file : files) {
					String fullName = pkgName + '.' + file;
					if (fullName.startsWith(clsName)) {
						list.add(new File(directory, file));
					}
				}
			} catch (URISyntaxException e) {
				fail(e.getMessage());
			}
		}
		return list;
	}

	private List<File> compileClass(Class<?> cls) throws IOException {
		String fileName = cls.getName();
		int end = fileName.indexOf('$');
		if (end != -1) {
			fileName = fileName.substring(0, end);
		}
		fileName = fileName.replace('.', '/') + ".java";
		File file = new File(TEST_DIRECTORY, fileName);
		if (!file.exists()) {
			file = new File(TEST_DIRECTORY2, fileName);
		}
		assertThat("Test source file not found: " + fileName, file.exists(), is(true));
		List<File> compileFileList = Collections.singletonList(file);

		File outTmp = createTempDir("jadx-tmp-classes");
		outTmp.deleteOnExit();
		List<File> files = StaticCompiler.compile(compileFileList, outTmp, withDebugInfo);
		// remove classes which are parents for test class
		files.removeIf(next -> !next.getName().contains(cls.getSimpleName()));
		for (File clsFile : files) {
			clsFile.deleteOnExit();
		}
		return files;
	}

	public JadxArgs getArgs() {
		return args;
	}

	public void setArgs(JadxArgs args) {
		this.args = args;
	}

	public void setResMap(Map<Integer, String> resMap) {
		this.resMap = resMap;
	}

	protected void noDebugInfo() {
		this.withDebugInfo = false;
	}

	protected void setFallback() {
		this.args.setFallbackMode(true);
	}

	protected void disableCompilation() {
		this.compile = false;
	}

	protected void dontUnloadClass() {
		this.unloadCls = false;
	}

	protected void enableDeobfuscation() {
		args.setDeobfuscationOn(true);
		args.setDeobfuscationForceSave(true);
		args.setDeobfuscationMinLength(2);
		args.setDeobfuscationMaxLength(64);
	}

	// Use only for debug purpose
	@Deprecated
	protected void setOutputCFG() {
		this.args.setCfgOutput(true);
		this.args.setRawCFGOutput(true);
	}    // Use only for debug purpose

	@Deprecated
	protected void setOutputRawCFG() {
		this.args.setRawCFGOutput(true);
	}

	// Use only for debug purpose
	@Deprecated
	protected void notDeleteTmpJar() {
		this.deleteTmpFiles = false;
	}
}
