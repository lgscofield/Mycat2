package io.mycat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 启动 -D/-d 参数解析回归锁：键不得被二次截断（旧 bug：剥掉 -D 后又 substring(2)
 * 吃掉键前两个字符），值内嵌 '=' 不得被 split 截断（典型受害者：JDBC URL 查询串）。
 */
public class MycatCoreArgsTest {

    @Test
    public void keyIsNotTruncated() {
        withSaved("mycat.test.key", () -> {
            MycatCore.applySystemPropertyArgs(new String[]{"-Dmycat.test.key=bar"});
            assertEquals("bar", System.getProperty("mycat.test.key"),
                    "键必须完整保留，不得吃掉前两个字符");
        });
    }

    @Test
    public void valueKeepsEmbeddedEquals() {
        withSaved("mycat.test.url", () -> {
            String url = "jdbc:mysql://h:3306/db?useSSL=false&x=1";
            MycatCore.applySystemPropertyArgs(new String[]{"-Dmycat.test.url=" + url});
            assertEquals(url, System.getProperty("mycat.test.url"),
                    "值中的 '=' 之后内容不得丢失");
        });
    }

    @Test
    public void flagWithoutValueBecomesTrue() {
        withSaved("mycat.test.flag", () -> {
            MycatCore.applySystemPropertyArgs(new String[]{"-Dmycat.test.flag"});
            assertEquals("true", System.getProperty("mycat.test.flag"));
        });
    }

    @Test
    public void lowercaseDPrefixAccepted() {
        withSaved("mycat.test.lower", () -> {
            MycatCore.applySystemPropertyArgs(new String[]{"-dmycat.test.lower=v"});
            assertEquals("v", System.getProperty("mycat.test.lower"));
        });
    }

    @Test
    public void nonDArgsIgnoredAndNullTolerated() {
        withSaved("plainArg", () -> {
            MycatCore.applySystemPropertyArgs(new String[]{"plainArg=x", "--help"});
            MycatCore.applySystemPropertyArgs(null);
            assertNull(System.getProperty("plainArg"));
            assertNull(System.getProperty("--help"));
        });
    }

    private void withSaved(String key, Runnable body) {
        String saved = System.getProperty(key);
        try {
            System.clearProperty(key);
            body.run();
        } finally {
            if (saved == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, saved);
            }
        }
    }
}