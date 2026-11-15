/*
 * I-DEBT-121 离线锁：showConfigText 凭据倾倒封堵。
 *
 * 活体点位（修复前）：HintHandler "showconfigtext" 分支直接 JsonUtil.toJson(mycatRouterConfig)
 * 把全量配置（users[].password 解密后明文 + datasources[].password）原样回显给任意连接者。
 * 本锁钉三件事：
 *  1) 门：showconfigtext 属管理面 cmd，isManagementHint 必须命中（非 admin 在 :158 统一拒）；
 *  2) 脱敏：maskConfigText 输出不含任何明文口令（users 与 datasources 双面），password 字段统一 ******；
 *  3) 形状：users/datasources 名称与非敏感字段原样保留，输出仍是合法 JSON 结构。
 */
package io.mycat.sqlhandler.dql;

import io.mycat.config.DatasourceConfig;
import io.mycat.config.MycatRouterConfig;
import io.mycat.config.UserConfig;
import io.mycat.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintHandlerShowConfigTextSecurityTest {

    private MycatRouterConfig newConfigWithSecrets() {
        MycatRouterConfig config = new MycatRouterConfig();

        UserConfig user = new UserConfig();
        user.setUsername("root");
        user.setPassword("Root#2026");
        user.setIp("127.0.0.1");
        config.getUsers().add(user);

        DatasourceConfig ds = new DatasourceConfig();
        ds.setName("prototypeDs");
        ds.setUser("app");
        ds.setPassword("Ds#2026");
        ds.setUrl("jdbc:mysql://127.0.0.1:3306/db");
        config.getDatasources().add(ds);
        return config;
    }

    @Test
    public void showConfigTextIsManagementHint() {
        // 门面：与 migrate/createUser 等同权，非 admin 一律拒（复用 :158 单点门）
        assertTrue(HintHandler.isManagementHint("showConfigText"));
        assertTrue(HintHandler.isManagementHint("showconfigtext"));
    }

    @Test
    public void maskedConfigTextContainsNoPlaintextPasswords() {
        String text = HintHandler.maskConfigText(newConfigWithSecrets());
        assertFalse(text.contains("Root#2026"), "users[].password 明文不得回显");
        assertFalse(text.contains("Ds#2026"), "datasources[].password 明文不得回显");
        assertTrue(text.contains("******"), "password 字段应输出固定掩码");
    }

    @Test
    public void maskedConfigTextKeepsNamesAndNonSensitiveFields() {
        MycatRouterConfig config = newConfigWithSecrets();
        String text = HintHandler.maskConfigText(config);

        assertTrue(text.contains("root"), "users[].username 必须保留");
        assertTrue(text.contains("prototypeDs"), "datasources[].name 必须保留");
        assertTrue(text.contains("jdbc:mysql://127.0.0.1:3306/db"), "非敏感字段(url)形状不变");
        assertTrue(text.contains("127.0.0.1"), "非敏感字段(ip)形状不变");
    }

    @Test
    public void maskedConfigTextIsStillValidJsonWithSameStructure() {
        String text = HintHandler.maskConfigText(newConfigWithSecrets());
        Map map = JsonUtil.from(text, Map.class);
        List users = (List) map.get("users");
        List datasources = (List) map.get("datasources");
        assertEquals(1, users.size());
        assertEquals(1, datasources.size());

        Map user = (Map) users.get(0);
        Map ds = (Map) datasources.get(0);
        assertEquals("root", user.get("username"));
        assertEquals("******", user.get("password"));
        assertEquals("prototypeDs", ds.get("name"));
        assertEquals("app", ds.get("user"));
        assertEquals("******", ds.get("password"));
    }
}
