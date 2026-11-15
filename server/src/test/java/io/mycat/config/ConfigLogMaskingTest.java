/*
 * I-DEBT-135 离线锁：配置日志凭据脱敏。
 *
 * 活体点位（修复前）：
 *  1) StdStorageManagerImpl.checkConfigConsistency 一致性失败时 LOGGER.error("from {}\n to {}")
 *     直接打印两侧 toMap（datasources/users 配置文件原文），明文口令进日志；
 *  2) MycatRouterConfigOps.commit() 在 DEBUG 级 dump 全量 original/newConfig JSON，明文口令进日志。
 * 修法：JsonUtil 新增 maskPasswordsInJson(String)（正则替换 "password":"..." → "password":"******"，
 * 兼容紧凑与带空格两种形态）；两处日志前统一过该工具。本锁钉：
 *  1) 紧凑/带空格/pretty 三种 JSON 形态下的 password 全掩码；
 *  2) 非 password 字段逐字不变；
 *  3) StdStorageManagerImpl 的 map 日志脱敏（内层 value 为配置文件原文 JSON）。
 */
package io.mycat.config;

import io.mycat.sqlhandler.config.StdStorageManagerImpl;
import io.mycat.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigLogMaskingTest {

    private static final String SECRET = "Audit#2026";

    @Test
    public void compactJsonPasswordIsMasked() {
        String in = "{\"name\":\"prototypeDs\",\"password\":\"" + SECRET + "\",\"user\":\"app\"}";
        String out = JsonUtil.maskPasswordsInJson(in);
        assertFalse(out.contains(SECRET));
        assertTrue(out.contains("\"password\":\"******\""));
    }

    @Test
    public void spacedAndPrettyJsonPasswordIsMasked() {
        String spaced = "{\"password\" : \"" + SECRET + "\"}";
        assertTrue(JsonUtil.maskPasswordsInJson(spaced).contains("\"password\":\"******\""));

        String pretty = "{\n  \"users\" : [ {\n    \"password\" : \"" + SECRET + "\"\n  } ]\n}";
        String masked = JsonUtil.maskPasswordsInJson(pretty);
        assertFalse(masked.contains(SECRET));
        assertTrue(masked.contains("\"password\":\"******\""));
    }

    @Test
    public void nonPasswordFieldsStayByteIdentical() {
        String in = "{\"name\":\"ds0\",\"user\":\"app\",\"url\":\"jdbc:mysql://127.0.0.1:3306/db\",\"password\":\"" + SECRET + "\"}";
        String out = JsonUtil.maskPasswordsInJson(in);
        assertTrue(out.contains("\"name\":\"ds0\""));
        assertTrue(out.contains("\"user\":\"app\""));
        assertTrue(out.contains("\"url\":\"jdbc:mysql://127.0.0.1:3306/db\""));
        assertFalse(out.contains(SECRET));
    }

    @Test
    public void escapedPasswordValueIsMasked() {
        String in = "{\"password\":\"a\\\"b" + SECRET + "\"}";
        String out = JsonUtil.maskPasswordsInJson(in);
        assertFalse(out.contains(SECRET));
    }

    @Test
    public void nullAndEmptyAndNoPasswordInputPassThrough() {
        assertNull(JsonUtil.maskPasswordsInJson(null));
        assertEquals("", JsonUtil.maskPasswordsInJson(""));
        assertEquals("{\"a\":1}", JsonUtil.maskPasswordsInJson("{\"a\":1}"));
    }

    @Test
    public void stdStorageManagerConsistencyLogMapIsMasked() {
        String fileContent = "{\"name\":\"prototypeDs\",\"password\":\"" + SECRET + "\"}";
        Map<String, String> inner = new LinkedHashMap<>();
        inner.put("prototypeDs.datasource.json", fileContent);
        Map<String, Map<String, String>> snapshot = new LinkedHashMap<>();
        snapshot.put("datasources", inner);

        String masked = StdStorageManagerImpl.maskConfigSnapshotForLog(snapshot);
        assertFalse(masked.contains(SECRET), "一致性失败日志不得外泄配置文件明文口令");
        assertTrue(masked.contains("\"password\":\"******\""));
        assertTrue(masked.contains("prototypeDs.datasource.json"), "文件名（非敏感）保留");
    }
}
