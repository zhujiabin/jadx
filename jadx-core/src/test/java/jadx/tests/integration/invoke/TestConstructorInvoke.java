package jadx.tests.integration.invoke;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.ClassNode;
import jadx.tests.api.IntegrationTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class TestConstructorInvoke extends IntegrationTest {

	public class TestCls {
		void test(String root, String name) {
			ViewHolder viewHolder = new ViewHolder(root, name);
		}

		private final class ViewHolder {
			private int mElements = 0;
			private final String mRoot;
			private String mName;

			private ViewHolder(String root, String name) {
				this.mRoot = root;
				this.mName = name;
			}
		}
	}

	@Test
	public void test() {
		ClassNode cls = getClassNode(TestConstructorInvoke.class);
		String code = cls.getCode().toString();

		assertThat(code, containsString("new ViewHolder(root, name);"));
	}
}
