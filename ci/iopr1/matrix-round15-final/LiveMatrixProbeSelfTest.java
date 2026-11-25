import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

public class LiveMatrixProbeSelfTest {
    public static void main(String[] args) throws Exception {
        RuntimeException expected = new RuntimeException("injected sleeper failure");
        Statement statement = proxy(Statement.class, (target, method, arguments) -> {
            if ("executeQuery".equals(method.getName())) {
                throw expected;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Connection connection = proxy(Connection.class, (target, method, arguments) -> {
            if ("createStatement".equals(method.getName())) {
                return statement;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });

        LiveMatrixProbe.Sleeper sleeper = new LiveMatrixProbe.Sleeper(connection, 1, "SELECT 1");
        sleeper.start();
        if (!sleeper.finished.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Sleeper did not finish after RuntimeException");
        }
        Throwable actual = sleeper.error.get();
        if (actual != expected) {
            throw new AssertionError("RuntimeException was not recorded: " + actual);
        }
        if (sleeper.errorMillis < sleeper.startedMillis) {
            throw new AssertionError("Failure timestamp was not recorded");
        }
        System.out.println("SLEEPER_SELFTEST_PASS");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
