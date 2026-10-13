package io.mycat;

import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.reactivex.rxjava3.core.Observable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-ME-1 排序规则表补全：information_schema.COLLATIONS / COLLATION_CHARACTER_SET_APPLICABILITY /
 * CHARACTER_SETS 三张仿真表由「写死单行」改为 **按 persona 输出**，行值逐字段锚定官网：
 * <ul>
 * <li>MySQL 5.7：{@code SHOW CHARACTER SET} 默认集（utf8mb4 默认 utf8mb4_general_ci；utf8 默认
 * utf8_general_ci）+ {@code SHOW COLLATION} 示例中的 Id/Compiled/Sortlen/Pad_attribute；</li>
 * <li>MySQL 8.0：官网 "Character Sets and Collations in MySQL" 的 {@code SHOW COLLATION WHERE
 * Charset='utf8mb4'} 全表（utf8mb4_0900_* NO PAD、Sortlen 0；默认 utf8mb4_0900_ai_ci Id=255）；</li>
 * <li>MariaDB：官方 "Supported Character Sets and Collations"（utf8mb4 默认 utf8mb4_general_ci 直到
 * 11.4，**11.5 起**才翻 utf8mb4_uca1400_ai_ci；utf8mb4_bin=46/utf8mb3_bin=83 等 id 与 MySQL 不同；
 * 0900 系为 uca1400 别名）。persona 集只放官网能锚定 id/属性的行，宁缺勿错。</li>
 * </ul>
 * SHOW COLLATION 活路径（{@code ShowCollationSQLHandler} 回退分支）与 i_s 查询共用同一张
 * 虚拟表，故本锁即命令面口径锁。零回归锚点：DI 未注册时回落 MYSQL_5_7（同 VERSION()）。
 */
public class PersonaCollationsTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    private void registerPersona(ServerPersona persona) {
        IdentityHashMap<Class, Object> ctx = new IdentityHashMap<>();
        ctx.put(VersionProfile.class, VersionProfileFactory.of(persona, null));
        MetaClusterCurrent.register(ctx);
    }

    private static Object[] find(List<Object[]> rows, String collationName) {
        return rows.stream().filter(r -> collationName.equals(r[0])).findFirst().orElse(null);
    }

    /** 5.7 persona：utf8mb4_general_ci(45) 为默认；0900 系必须缺席（5.7 无此族，官网 charset-charsets）。 */
    @Test
    public void mysql57PersonaEmitsGeneralCiDefaultAndNo0900Family() {
        List<Object[]> rows = PersonaCollations.collationRows(ServerPersona.MYSQL_5_7);
        Object[] general = find(rows, "utf8mb4_general_ci");
        assertNotNull(general, "5.7 应有 utf8mb4_general_ci");
        assertEquals("utf8mb4", general[1]);
        assertEquals(45, general[2]);
        assertEquals("Yes", general[3], "5.7 utf8mb4 默认 collation = utf8mb4_general_ci");
        assertEquals("Yes", general[4]);
        assertEquals(1, general[5]);
        assertEquals("PAD SPACE", general[6]);
        assertTrue(rows.stream().noneMatch(r -> String.valueOf(r[0]).contains("_0900_")),
                "5.7 persona 不得广告 0900 系排序规则");
        assertNotNull(find(rows, "utf8_general_ci"), "5.7 须有 utf8(=utf8mb3 旧名) 系");
    }

    /** 8.0 persona：0900 系在场且默认位翻到 utf8mb4_0900_ai_ci(255, NO PAD, Sortlen 0)。 */
    @Test
    public void mysql80PersonaEmits0900FamilyAsDefault() {
        List<Object[]> rows = PersonaCollations.collationRows(ServerPersona.MYSQL_8_0);
        Object[] def = find(rows, "utf8mb4_0900_ai_ci");
        assertNotNull(def, "8.0 应有 utf8mb4_0900_ai_ci");
        assertEquals(255, def[2]);
        assertEquals("Yes", def[3]);
        assertEquals(0, def[5]);
        assertEquals("NO PAD", def[6]);
        Object[] general = find(rows, "utf8mb4_general_ci");
        assertNotNull(general, "8.0 仍列 utf8mb4_general_ci(45)");
        assertEquals("", general[3], "8.0 中 general_ci 不再是默认");
        assertEquals("NO PAD", find(rows, "utf8mb4_0900_bin")[6]);
        assertEquals(309, find(rows, "utf8mb4_0900_bin")[2]);
    }

    /** MariaDB 11.4 persona：utf8mb4 默认仍 utf8mb4_general_ci(45)（官网：uca1400 默认是 11.5 起）；0900 别名行在场；utf8mb3 命名。 */
    @Test
    public void mariadb114PersonaKeepsGeneralCiDefaultWithAliasRowsAndUtf8mb3Naming() {
        List<Object[]> rows = PersonaCollations.collationRows(ServerPersona.MARIADB_11_4);
        assertEquals("Yes", find(rows, "utf8mb4_general_ci")[3], "MariaDB 11.4 utf8mb4 默认 = utf8mb4_general_ci");
        Object[] alias = find(rows, "utf8mb4_0900_ai_ci");
        assertNotNull(alias, "0900 系在 MariaDB 为 uca1400 别名行");
        assertEquals(255, alias[2]);
        assertEquals("", alias[3], "别名行非默认");
        Object[] mb3bin = find(rows, "utf8mb3_bin");
        assertNotNull(mb3bin);
        assertEquals(83, mb3bin[2], "MariaDB 官网：utf8mb3_bin Id=83");
        assertEquals(46, find(rows, "utf8mb4_bin")[2], "MariaDB 官网：utf8mb4_bin Id=46");
        assertTrue(rows.stream().noneMatch(r -> "utf8_general_ci".equals(r[0])),
                "MariaDB 10.6+ 用 utf8mb3 命名，无 utf8_general_ci 行");
        assertTrue(rows.stream().noneMatch(r -> String.valueOf(r[0]).contains("uca1400")),
                "uca1400 属 11.5+，persona 集仅 10.6..11.4，未锚定 id 的行不写入");
    }

    /** MariaDB 10.6 persona：与 11.4 同档（官网：11.5 前默认不变）。 */
    @Test
    public void mariadb106PersonaKeepsGeneralCiDefault() {
        List<Object[]> rows = PersonaCollations.collationRows(ServerPersona.MARIADB_10_6);
        assertEquals("Yes", find(rows, "utf8mb4_general_ci")[3]);
        assertTrue(rows.stream().noneMatch(r -> String.valueOf(r[0]).contains("uca1400")));
    }

    /**
     * 内部一致性（工具消费口径）：CHARACTER_SETS 的 DEFAULT_COLLATE_NAME 必须在同 persona 的
     * COLLATIONS 行集中且唯一标 "Yes"；每字符集恰有一个默认；CCSA 投影逐行对应 COLLATIONS。
     */
    @Test
    public void charsetDefaultsAreConsistentWithCollationRowsForEveryPersona() {
        for (ServerPersona persona : ServerPersona.values()) {
            List<Object[]> collations = PersonaCollations.collationRows(persona);
            List<Object[]> charsets = PersonaCollations.characterSetRows(persona);
            for (Object[] cs : charsets) {
                String defaultCollate = (String) cs[1];
                Object[] row = find(collations, defaultCollate);
                assertNotNull(row, persona + ": CHARACTER_SETS 默认 " + defaultCollate + " 须在 COLLATIONS 在场");
                assertEquals(cs[0], row[1], persona + ": 默认 collation 须属该字符集");
                long defaultsOfCharset = collations.stream()
                        .filter(r -> cs[0].equals(r[1]) && "Yes".equals(r[3])).count();
                assertEquals(1, defaultsOfCharset, persona + ": 字符集 " + cs[0] + " 恰有一个默认 collation");
            }
            List<Object[]> ccsa = PersonaCollations.collationCharsetNameRows(persona);
            assertEquals(collations.size(), ccsa.size(), persona + ": CCSA 须逐行对应 COLLATIONS");
        }
    }

    /** 活 handler 动态化：i_s.COLLATIONS 与 CCSA 的 scanAll 每次求值都按 DI 里的 persona 出数。 */
    @Test
    public void collationsHandlerScansPerRegisteredPersona() {
        registerPersona(ServerPersona.MYSQL_8_0);
        List<Object[]> on80 = MysqlMetadataManager.COLLATIONS_TABLE_HANDLER.scanAll().toList().blockingGet();
        assertTrue(on80.stream().anyMatch(r -> "utf8mb4_0900_ai_ci".equals(r[0])),
                "8.0 persona 下 COLLATIONS 表应含 0900 系");
        assertEquals(on80.size(),
                MysqlMetadataManager.COLLATION_CHARACTER_SET_APPLICABILITY_TABLE_HANDLER.scanAll().toList().blockingGet().size(),
                "CCSA 与 COLLATIONS 行数一致");

        registerPersona(ServerPersona.MYSQL_5_7);
        Observable<Object[]> on57 = MysqlMetadataManager.COLLATIONS_TABLE_HANDLER.scanAll();
        List<Object[]> rows57 = on57.toList().blockingGet();
        assertTrue(rows57.stream().noneMatch(r -> String.valueOf(r[0]).contains("_0900_")),
                "切回 5.7 persona 后 0900 系须消失（静态快照=旧缺陷）");
    }
}
