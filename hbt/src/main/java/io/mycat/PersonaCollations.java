package io.mycat;

import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * I-ME-1 · persona 驱动的排序规则表/字符集表元数据（information_schema.COLLATIONS /
 * COLLATION_CHARACTER_SET_APPLICABILITY / CHARACTER_SETS 三张仿真表的唯一数据源）。
 *
 * <p>行值逐字段锚定官网（校准入 2026-10-13，"拿不准查官网"口径）：
 * <ul>
 * <li>MySQL 8.0 utf8mb4 全表 61 行：dev.mysql.com/refman/8.0/en/charset-mysql.html
 * {@code SHOW COLLATION WHERE Charset='utf8mb4'}（0900 系 NO PAD/Sortlen 0；默认 utf8mb4_0900_ai_ci=255）；</li>
 * <li>MySQL latin1 8 行：同页 {@code SHOW COLLATION WHERE Charset='latin1'} 示例；
 * utf8mb3_general_ci=33/binary=63：dev/mysql-server/latest/page_protocol_basic_character_set.html；
 * ascii_general_ci=11/ascii_bin=65/utf8mb3_bin=83：跨引擎同号（MariaDB 官网表亦同值）；
 * utf8mb4_general_ci=45/utf8mb4_bin=46/unicode_ci=224/unicode_520_ci=246 皆 8.0 表内行；</li>
 * <li>MySQL 5.7 默认集：refman/5.7/en/charset-charsets.html（utf8mb4 默认 utf8mb4_general_ci、
 * utf8 默认 utf8_general_ci、ascii_general_ci、latin1_swedish_ci、binary）——5.7 无 0900 系；</li>
 * <li>MariaDB：mariadb.com/docs「Supported Character Sets and Collations」（utf8mb4 默认
 * utf8mb4_general_ci 直到 11.4，<b>11.5 起</b>才翻 utf8mb4_uca1400_ai_ci；utf8mb4_bin=46、
 * utf8mb3_bin=83、utf8mb3_general_ci=33 默认、latin1_bin=47、binary=63；0900 系为 uca1400 别名、Sortlen 8）。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：三引擎全量排序规则各数百行，本表只收录官网当页能直接锚定 id/属性的常用族
 * （utf8mb4 / utf8(mb3) / ascii / latin1 / binary）；锚不到整行的族（utf8mb3 语言特定系）宁缺勿错。
 * SHOW COLLATION 活路径（ShowCollationSQLHandler 回退分支）与 i_s 查询共用本表，口径同源。
 */
public final class PersonaCollations {

    private PersonaCollations() {
    }

    /** 行规格。IS_COMPILED 恒 Yes（官网各行皆 Yes）。 */
    private record Spec(String name, String charset, int id, boolean isDefault, int sortLen, String pad) {
    }

    /** 字符集规格：[CHARACTER_SET_NAME, DEFAULT_COLLATE_NAME, DESCRIPTION, MAXLEN]。 */
    private record CharsetSpec(String name, String defaultCollate, String description, int maxlen) {
    }

    /** 列序对齐 COLLATIONS DDL：[NAME, CHARSET, ID, IS_DEFAULT, IS_COMPILED, SORTLEN, PAD_ATTRIBUTE]。 */
    private static Object[] row(Spec s) {
        return new Object[]{s.name(), s.charset(), s.id(), s.isDefault() ? "Yes" : "", "Yes", s.sortLen(), s.pad()};
    }

    // ---- 三引擎一致基座：latin1 全 8 行 + ascii + binary（官网 SHOW COLLATION 示例逐行） ----
    private static final List<Spec> BASE_ROWS = List.of(
            new Spec("latin1_german1_ci", "latin1", 5, false, 1, "PAD SPACE"),
            new Spec("latin1_swedish_ci", "latin1", 8, true, 1, "PAD SPACE"),
            new Spec("latin1_danish_ci", "latin1", 15, false, 1, "PAD SPACE"),
            new Spec("latin1_german2_ci", "latin1", 31, false, 2, "PAD SPACE"),
            new Spec("latin1_bin", "latin1", 47, false, 1, "PAD SPACE"),
            new Spec("latin1_general_ci", "latin1", 48, false, 1, "PAD SPACE"),
            new Spec("latin1_general_cs", "latin1", 49, false, 1, "PAD SPACE"),
            new Spec("latin1_spanish_ci", "latin1", 94, false, 1, "PAD SPACE"),
            new Spec("ascii_general_ci", "ascii", 11, true, 1, "PAD SPACE"),
            new Spec("ascii_bin", "ascii", 65, false, 1, "PAD SPACE"),
            new Spec("binary", "binary", 63, true, 1, "PAD SPACE"));

    // ---- MySQL utf8mb3（5.7 名 utf8 / 8.0 名 utf8mb3 / MariaDB 10.6+ 名 utf8mb3），常用 3 行 ----
    private static final List<Spec> MYSQL80_UTF8MB3 = List.of(
            new Spec("utf8mb3_general_ci", "utf8mb3", 33, true, 1, "PAD SPACE"),
            new Spec("utf8mb3_bin", "utf8mb3", 83, false, 1, "PAD SPACE"),
            new Spec("utf8mb3_unicode_ci", "utf8mb3", 192, false, 8, "PAD SPACE"));
    private static final List<Spec> MYSQL57_UTF8 = List.of(
            new Spec("utf8_general_ci", "utf8", 33, true, 1, "PAD SPACE"),
            new Spec("utf8_bin", "utf8", 83, false, 1, "PAD SPACE"),
            new Spec("utf8_unicode_ci", "utf8", 192, false, 8, "PAD SPACE"));

    // ---- MySQL 5.7 utf8mb4 常用行（id 与 8.0 表同值；无 0900 系；默认 general_ci，官网 5.7 charset-charsets） ----
    private static final List<Spec> MYSQL57_UTF8MB4 = List.of(
            new Spec("utf8mb4_general_ci", "utf8mb4", 45, true, 1, "PAD SPACE"),
            new Spec("utf8mb4_bin", "utf8mb4", 46, false, 1, "PAD SPACE"),
            new Spec("utf8mb4_unicode_ci", "utf8mb4", 224, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_unicode_520_ci", "utf8mb4", 246, false, 8, "PAD SPACE"));

    // ---- MySQL 8.0 utf8mb4 全表 61 行（官网 charset-mysql.html 逐行转录） ----
    private static final List<Spec> MYSQL80_UTF8MB4 = List.of(
            new Spec("utf8mb4_0900_ai_ci", "utf8mb4", 255, true, 0, "NO PAD"),
            new Spec("utf8mb4_0900_as_ci", "utf8mb4", 305, false, 0, "NO PAD"),
            new Spec("utf8mb4_0900_as_cs", "utf8mb4", 278, false, 0, "NO PAD"),
            new Spec("utf8mb4_0900_bin", "utf8mb4", 309, false, 1, "NO PAD"),
            new Spec("utf8mb4_bin", "utf8mb4", 46, false, 1, "PAD SPACE"),
            new Spec("utf8mb4_croatian_ci", "utf8mb4", 245, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_cs_0900_ai_ci", "utf8mb4", 266, false, 0, "NO PAD"),
            new Spec("utf8mb4_cs_0900_as_cs", "utf8mb4", 289, false, 0, "NO PAD"),
            new Spec("utf8mb4_czech_ci", "utf8mb4", 234, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_danish_ci", "utf8mb4", 235, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_da_0900_ai_ci", "utf8mb4", 267, false, 0, "NO PAD"),
            new Spec("utf8mb4_da_0900_as_cs", "utf8mb4", 290, false, 0, "NO PAD"),
            new Spec("utf8mb4_de_pb_0900_ai_ci", "utf8mb4", 256, false, 0, "NO PAD"),
            new Spec("utf8mb4_de_pb_0900_as_cs", "utf8mb4", 279, false, 0, "NO PAD"),
            new Spec("utf8mb4_eo_0900_ai_ci", "utf8mb4", 273, false, 0, "NO PAD"),
            new Spec("utf8mb4_eo_0900_as_cs", "utf8mb4", 296, false, 0, "NO PAD"),
            new Spec("utf8mb4_estonian_ci", "utf8mb4", 230, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_esperanto_ci", "utf8mb4", 241, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_es_0900_ai_ci", "utf8mb4", 263, false, 0, "NO PAD"),
            new Spec("utf8mb4_es_0900_as_cs", "utf8mb4", 286, false, 0, "NO PAD"),
            new Spec("utf8mb4_es_trad_0900_ai_ci", "utf8mb4", 270, false, 0, "NO PAD"),
            new Spec("utf8mb4_es_trad_0900_as_cs", "utf8mb4", 293, false, 0, "NO PAD"),
            new Spec("utf8mb4_et_0900_ai_ci", "utf8mb4", 262, false, 0, "NO PAD"),
            new Spec("utf8mb4_et_0900_as_cs", "utf8mb4", 285, false, 0, "NO PAD"),
            new Spec("utf8mb4_general_ci", "utf8mb4", 45, false, 1, "PAD SPACE"),
            new Spec("utf8mb4_german2_ci", "utf8mb4", 244, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_hr_0900_ai_ci", "utf8mb4", 275, false, 0, "NO PAD"),
            new Spec("utf8mb4_hr_0900_as_cs", "utf8mb4", 298, false, 0, "NO PAD"),
            new Spec("utf8mb4_hungarian_ci", "utf8mb4", 242, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_hu_0900_ai_ci", "utf8mb4", 274, false, 0, "NO PAD"),
            new Spec("utf8mb4_hu_0900_as_cs", "utf8mb4", 297, false, 0, "NO PAD"),
            new Spec("utf8mb4_icelandic_ci", "utf8mb4", 225, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_is_0900_ai_ci", "utf8mb4", 257, false, 0, "NO PAD"),
            new Spec("utf8mb4_is_0900_as_cs", "utf8mb4", 280, false, 0, "NO PAD"),
            new Spec("utf8mb4_ja_0900_as_cs", "utf8mb4", 303, false, 0, "NO PAD"),
            new Spec("utf8mb4_ja_0900_as_cs_ks", "utf8mb4", 304, false, 24, "NO PAD"),
            new Spec("utf8mb4_latvian_ci", "utf8mb4", 226, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_la_0900_ai_ci", "utf8mb4", 271, false, 0, "NO PAD"),
            new Spec("utf8mb4_la_0900_as_cs", "utf8mb4", 294, false, 0, "NO PAD"),
            new Spec("utf8mb4_lithuanian_ci", "utf8mb4", 236, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_lt_0900_ai_ci", "utf8mb4", 268, false, 0, "NO PAD"),
            new Spec("utf8mb4_lt_0900_as_cs", "utf8mb4", 291, false, 0, "NO PAD"),
            new Spec("utf8mb4_lv_0900_ai_ci", "utf8mb4", 258, false, 0, "NO PAD"),
            new Spec("utf8mb4_lv_0900_as_cs", "utf8mb4", 281, false, 0, "NO PAD"),
            new Spec("utf8mb4_persian_ci", "utf8mb4", 240, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_pl_0900_ai_ci", "utf8mb4", 261, false, 0, "NO PAD"),
            new Spec("utf8mb4_pl_0900_as_cs", "utf8mb4", 284, false, 0, "NO PAD"),
            new Spec("utf8mb4_polish_ci", "utf8mb4", 229, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_romanian_ci", "utf8mb4", 227, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_roman_ci", "utf8mb4", 239, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_ro_0900_ai_ci", "utf8mb4", 259, false, 0, "NO PAD"),
            new Spec("utf8mb4_ro_0900_as_cs", "utf8mb4", 282, false, 0, "NO PAD"),
            new Spec("utf8mb4_ru_0900_ai_ci", "utf8mb4", 306, false, 0, "NO PAD"),
            new Spec("utf8mb4_ru_0900_as_cs", "utf8mb4", 307, false, 0, "NO PAD"),
            new Spec("utf8mb4_sinhala_ci", "utf8mb4", 243, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_sk_0900_ai_ci", "utf8mb4", 269, false, 0, "NO PAD"),
            new Spec("utf8mb4_sk_0900_as_cs", "utf8mb4", 292, false, 0, "NO PAD"),
            new Spec("utf8mb4_slovak_ci", "utf8mb4", 237, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_slovenian_ci", "utf8mb4", 228, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_sl_0900_ai_ci", "utf8mb4", 260, false, 0, "NO PAD"),
            new Spec("utf8mb4_sl_0900_as_cs", "utf8mb4", 283, false, 0, "NO PAD"),
            new Spec("utf8mb4_spanish2_ci", "utf8mb4", 238, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_spanish_ci", "utf8mb4", 231, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_sv_0900_ai_ci", "utf8mb4", 264, false, 0, "NO PAD"),
            new Spec("utf8mb4_sv_0900_as_cs", "utf8mb4", 287, false, 0, "NO PAD"),
            new Spec("utf8mb4_swedish_ci", "utf8mb4", 232, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_tr_0900_ai_ci", "utf8mb4", 265, false, 0, "NO PAD"),
            new Spec("utf8mb4_tr_0900_as_cs", "utf8mb4", 288, false, 0, "NO PAD"),
            new Spec("utf8mb4_turkish_ci", "utf8mb4", 233, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_unicode_520_ci", "utf8mb4", 246, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_unicode_ci", "utf8mb4", 224, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_vietnamese_ci", "utf8mb4", 247, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_vi_0900_ai_ci", "utf8mb4", 277, false, 0, "NO PAD"),
            new Spec("utf8mb4_vi_0900_as_cs", "utf8mb4", 300, false, 0, "NO PAD"),
            new Spec("utf8mb4_zh_0900_as_cs", "utf8mb4", 308, false, 0, "NO PAD"));

    // ---- MariaDB utf8mb4：常用行 + 0900 别名行（官网：别名 Sortlen 8；默认 general_ci 直到 11.4） ----
    private static final List<Spec> MARIADB_UTF8MB4 = List.of(
            new Spec("utf8mb4_general_ci", "utf8mb4", 45, true, 1, "PAD SPACE"),
            new Spec("utf8mb4_bin", "utf8mb4", 46, false, 1, "PAD SPACE"),
            new Spec("utf8mb4_unicode_ci", "utf8mb4", 224, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_unicode_520_ci", "utf8mb4", 246, false, 8, "PAD SPACE"),
            new Spec("utf8mb4_0900_ai_ci", "utf8mb4", 255, false, 8, "NO PAD"),
            new Spec("utf8mb4_0900_as_ci", "utf8mb4", 305, false, 8, "NO PAD"),
            new Spec("utf8mb4_0900_as_cs", "utf8mb4", 278, false, 8, "NO PAD"),
            new Spec("utf8mb4_0900_bin", "utf8mb4", 309, false, 1, "NO PAD"));
    private static final List<Spec> MARIADB_UTF8MB3 = List.of(
            new Spec("utf8mb3_general_ci", "utf8mb3", 33, true, 1, "PAD SPACE"),
            new Spec("utf8mb3_bin", "utf8mb3", 83, false, 1, "PAD SPACE"));

    private static List<Object[]> rowsOf(List<Spec>... groups) {
        List<Object[]> out = new ArrayList<>();
        for (List<Spec> g : groups) {
            for (Spec s : g) {
                out.add(row(s));
            }
        }
        return List.copyOf(out);
    }

    /** COLLATIONS / SHOW COLLATION 行：[NAME, CHARSET, ID, IS_DEFAULT, IS_COMPILED, SORTLEN, PAD_ATTRIBUTE]。 */
    public static List<Object[]> collationRows(ServerPersona persona) {
        return switch (persona) {
            case MYSQL_5_7 -> rowsOf(BASE_ROWS, MYSQL57_UTF8, MYSQL57_UTF8MB4);
            case MYSQL_8_0, MYSQL_8_4, MYSQL_9_X -> rowsOf(BASE_ROWS, MYSQL80_UTF8MB3, MYSQL80_UTF8MB4);
            // 官网：utf8mb4 默认 general_ci 直到 11.4（uca1400 默认属 11.5+，persona 集外），三档同表。
            case MARIADB_10_6, MARIADB_10_11, MARIADB_11_4 -> rowsOf(BASE_ROWS, MARIADB_UTF8MB3, MARIADB_UTF8MB4);
        };
    }

    /** COLLATION_CHARACTER_SET_APPLICABILITY 投影：[COLLATION_NAME, CHARACTER_SET_NAME]，与 COLLATIONS 逐行同源。 */
    public static List<Object[]> collationCharsetNameRows(ServerPersona persona) {
        return collationRows(persona).stream()
                .map(r -> new Object[]{r[0], r[1]})
                .toList();
    }

    // ---- 字符集表（DEFAULT_COLLATE_NAME 与同 persona 的 COLLATIONS 默认行严格一致） ----
    private static final List<CharsetSpec> MYSQL57_CHARSETS = List.of(
            new CharsetSpec("utf8", "utf8_general_ci", "UTF-8 Unicode", 3),
            new CharsetSpec("utf8mb4", "utf8mb4_general_ci", "UTF-8 Unicode", 4),
            new CharsetSpec("ascii", "ascii_general_ci", "US ASCII", 1),
            new CharsetSpec("latin1", "latin1_swedish_ci", "cp1252 West European", 1),
            new CharsetSpec("binary", "binary", "Binary pseudo charset", 1));
    private static final List<CharsetSpec> MYSQL80_CHARSETS = List.of(
            new CharsetSpec("utf8mb3", "utf8mb3_general_ci", "UTF-8 Unicode", 3),
            new CharsetSpec("utf8mb4", "utf8mb4_0900_ai_ci", "UTF-8 Unicode", 4),
            new CharsetSpec("ascii", "ascii_general_ci", "US ASCII", 1),
            new CharsetSpec("latin1", "latin1_swedish_ci", "cp1252 West European", 1),
            new CharsetSpec("binary", "binary", "Binary pseudo charset", 1));
    private static final List<CharsetSpec> MARIADB_CHARSETS = List.of(
            new CharsetSpec("utf8mb3", "utf8mb3_general_ci", "UTF-8 Unicode", 3),
            new CharsetSpec("utf8mb4", "utf8mb4_general_ci", "UTF-8 Unicode", 4),
            new CharsetSpec("ascii", "ascii_general_ci", "US ASCII", 1),
            new CharsetSpec("latin1", "latin1_swedish_ci", "cp1252 West European", 1),
            new CharsetSpec("binary", "binary", "Binary pseudo charset", 1));

    /** CHARACTER_SETS 行：[NAME, DEFAULT_COLLATE_NAME, DESCRIPTION, MAXLEN]。 */
    public static List<Object[]> characterSetRows(ServerPersona persona) {
        List<CharsetSpec> specs = switch (persona) {
            case MYSQL_5_7 -> MYSQL57_CHARSETS;
            case MYSQL_8_0, MYSQL_8_4, MYSQL_9_X -> MYSQL80_CHARSETS;
            case MARIADB_10_6, MARIADB_10_11, MARIADB_11_4 -> MARIADB_CHARSETS;
        };
        return specs.stream()
                .map(s -> (Object[]) new Object[]{s.name(), s.defaultCollate(), s.description(), s.maxlen()})
                .toList();
    }

    /**
     * 当前生效 persona：DI 读 VersionProfile（进程级，同 VERSION() 取用点）；
     * 未注册（引导早期/单测）回落 MYSQL_5_7 默认画像——零回归锚点。
     */
    public static ServerPersona currentPersona() {
        return MetaClusterCurrent.exist(VersionProfile.class)
                ? MetaClusterCurrent.wrapper(VersionProfile.class).persona()
                : VersionProfileFactory.defaultProfile().persona();
    }
}
