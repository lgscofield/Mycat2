package io.mycat.sqlhandler.dql;

import io.mycat.config.MycatRouterConfig;
import io.mycat.config.UserConfig;
import io.mycat.hint.MigrateHint;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MYCAT CHANGE (I-DEBT-141): MIGRATE 写库凭据兜底字段锁——
 * 修复前 resolveMigratePassword 兜底判的是 output.getUsername()（copy 自 username 分支），
 * 导致 output 仅配置 password 未配置 username 时，password 恒被回落为 users[0].getPassword()，
 * 用户显式指定的写库密码被丢弃。
 */
public class HintHandlerMigratePasswordFallbackTest {

    private static MycatRouterConfig routerWithUser(String username, String password) {
        UserConfig userConfig = new UserConfig();
        userConfig.setUsername(username);
        userConfig.setPassword(password);
        MycatRouterConfig routerConfig = new MycatRouterConfig();
        routerConfig.setUsers(Collections.singletonList(userConfig));
        return routerConfig;
    }

    @Test
    public void passwordExplicitlyConfiguredMustBeUsed() {
        MigrateHint.Output output = new MigrateHint.Output();
        output.setUsername("migrate_user");
        output.setPassword("explicit_password");
        MycatRouterConfig routerConfig = routerWithUser("fallback_user", "fallback_password");

        assertEquals("explicit_password", HintHandler.resolveMigratePassword(output, routerConfig),
                "output.password 非空时必须使用显式密码，不得回落 users[0]");
        assertEquals("migrate_user", HintHandler.resolveMigrateUsername(output, routerConfig));
    }

    @Test
    public void passwordMissingFallsBackToFirstUser() {
        MigrateHint.Output output = new MigrateHint.Output();
        output.setUsername("migrate_user");
        output.setPassword(null);
        MycatRouterConfig routerConfig = routerWithUser("fallback_user", "fallback_password");

        assertEquals("fallback_password", HintHandler.resolveMigratePassword(output, routerConfig),
                "output.password 为空时回落 users[0].password");
        assertEquals("migrate_user", HintHandler.resolveMigrateUsername(output, routerConfig));
    }

    @Test
    public void usernameMissingFallsBackToFirstUser() {
        MigrateHint.Output output = new MigrateHint.Output();
        output.setUsername(null);
        output.setPassword("explicit_password");
        MycatRouterConfig routerConfig = routerWithUser("fallback_user", "fallback_password");

        assertEquals("fallback_user", HintHandler.resolveMigrateUsername(output, routerConfig));
        assertEquals("explicit_password", HintHandler.resolveMigratePassword(output, routerConfig));
    }
}
