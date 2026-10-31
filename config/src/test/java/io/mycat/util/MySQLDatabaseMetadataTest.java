package io.mycat.util;

import io.mycat.config.ServerPersona;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本串解析契约锁（I-DEBT-70 旁证缺陷）。
 *
 * <p>历史缺陷：{@link MySQLDatabaseMetadata#parse(String)} 假定 MariaDB 自述串一律带
 * {@code "5.5.5-"} 兼容前缀、无条件从下标 6 起切——而 {@link ServerPersona} 的 MariaDB
 * 默认串（如 {@code "10.11.8-MariaDB"}）并无该前缀，切出来只剩单段残余 {@code "8"}：
 * major 先被赋成 8，再取 minor 时数组越界被 catch 吞掉，于是留下半截伪版本
 * （{@code 10.11.8} → {@code 8.0.0}、{@code 10.6.14} → {@code 4.0.0}、{@code 11.4.2} →
 * {@code 0.0.0}）。消费方 {@code MycatdbCommand.serverVersionCode()} 因此对 MariaDB
 * persona 得出错误版本码，把 mysqldump 前导 {@code /*!40100 SET @@SQL_MODE='' *}{@code /}
 * 当"版本未达标"保留成纯注释吞掉（10.6/11.4 画像）。
 */
public class MySQLDatabaseMetadataTest {

    @Test
    public void mariaDbVersionWithoutLegacyPrefixIsParsed() {
        // RED 复现点：旧实现固定从下标 6 切，得 "8" → major=8、minor 越界被吞 → 8.0.0
        MySQLDatabaseMetadata md = MySQLDatabaseMetadata.parse("10.11.8-MariaDB");
        assertEquals("MariaDB", md.productName());
        assertEquals(10, md.majorVersion());
        assertEquals(11, md.minorVersion());
        assertEquals(8, md.microVersion());
    }

    @Test
    public void mariaDbVersionWithLegacyPrefixStillParsed() {
        // 兼容前缀形态（Connector/J 自述串常见）必须保持解析正确
        MySQLDatabaseMetadata md = MySQLDatabaseMetadata.parse("5.5.5-10.11.8-MariaDB-1:10.11.8+maria~ubu2204");
        assertEquals("MariaDB", md.productName());
        assertEquals(10, md.majorVersion());
        assertEquals(11, md.minorVersion());
        assertEquals(8, md.microVersion());
    }

    @Test
    public void mariaDbVersionWithSuffixIsParsed() {
        MySQLDatabaseMetadata md = MySQLDatabaseMetadata.parse("10.6.14-MariaDB-log");
        assertEquals(10, md.majorVersion());
        assertEquals(6, md.minorVersion());
        assertEquals(14, md.microVersion());
    }

    @Test
    public void mysqlVersionsAreUnchanged() {
        MySQLDatabaseMetadata release = MySQLDatabaseMetadata.parse("8.0.34");
        assertEquals("MySQL", release.productName());
        assertEquals(8, release.majorVersion());
        assertEquals(0, release.minorVersion());
        assertEquals(34, release.microVersion());

        MySQLDatabaseMetadata mycat = MySQLDatabaseMetadata.parse("5.7.33-mycat-2.0");
        assertEquals(5, mycat.majorVersion());
        assertEquals(7, mycat.minorVersion());
        assertEquals(33, mycat.microVersion());
    }

    @Test
    public void everyPersonaDefaultVersionStringIsParsed() {
        assertPersona(ServerPersona.MYSQL_5_7, 5, 7, 33);
        assertPersona(ServerPersona.MYSQL_8_0, 8, 0, 34);
        assertPersona(ServerPersona.MYSQL_8_4, 8, 4, 0);
        assertPersona(ServerPersona.MYSQL_9_X, 9, 0, 1);
        assertPersona(ServerPersona.MARIADB_10_6, 10, 6, 14);
        assertPersona(ServerPersona.MARIADB_10_11, 10, 11, 8);
        assertPersona(ServerPersona.MARIADB_11_4, 11, 4, 2);
    }

    @Test
    public void deprecateEofCapabilityIsUnchangedByTheFix() {
        // MariaDB 各档解析出 10.x/11.x（修复前是 0.x）：两者都不是 5.7.5 之前的 5.x，
        // 故 CLIENT_DEPRECATE_EOF 结论同为 true——本次修复不得改变握手能力位。
        for (ServerPersona persona : new ServerPersona[]{ServerPersona.MARIADB_10_6,
                ServerPersona.MARIADB_10_11, ServerPersona.MARIADB_11_4}) {
            assertTrue(MySQLDatabaseMetadata.parse(persona.defaultVersionString()).isClientDeprecateEof(),
                    persona + " 应保留 CLIENT_DEPRECATE_EOF");
        }
        assertTrue(MySQLDatabaseMetadata.parse("8.0.34").isClientDeprecateEof());
        // 5.7.5 之前的 5.x 不支持该能力位，保持 false
        assertEquals(false, MySQLDatabaseMetadata.parse("5.7.4").isClientDeprecateEof());
    }

    private static void assertPersona(ServerPersona persona, int major, int minor, int micro) {
        MySQLDatabaseMetadata md = MySQLDatabaseMetadata.parse(persona.defaultVersionString());
        assertEquals(major, md.majorVersion(), persona + " major");
        assertEquals(minor, md.minorVersion(), persona + " minor");
        assertEquals(micro, md.microVersion(), persona + " micro");
    }
}
