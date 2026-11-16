package io.mycat.calcite.sqlfunction.datefunction;

import org.apache.calcite.mycat.MycatBuiltInMethodImpl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MYCAT CHANGE (I-DEBT-144): StringToTimestampFunction 三连伤锁——
 * ① 修复前解析成功后恒 return null（函数永远返回 NULL）；
 * ② 裸 parseInt/charAt 对非法输入抛 AIOOBE/NFE；
 * ③ MSS 3 位分支 substring(1,2) 只取 1 位秒（MycatTimeUtil 同源同病，一并修复）。
 * 修复后语义对齐 MycatBuiltInMethodImpl.stringToTimestamp：datetime/date 走 reference 解析，
 * TIME 简写族（[S]S/[M]MSS/[H]HMMSS/[-]DAYS [H]H:MM:SS）走 MycatTimeUtil.timeStringToTimeDuration；
 * 合法格式与两个解析器对拍，非法输入返回 NULL 不抛。
 */
public class StringToTimestampFunctionSemanticsTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_NANO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** reference 输出的 LocalDateTime → 函数的字符串形态（对拍基准）。 */
    private static String format(LocalDateTime dt) {
        return dt.getNano() == 0 ? TS.format(dt) : TS_NANO.format(dt);
    }

    /** MycatTimeUtil 解析出的 Duration → 函数的字符串形态（TIME 族基准）。 */
    private static String format(Duration duration) {
        boolean negative = duration.isNegative();
        Duration abs = negative ? duration.negated() : duration;
        long days = abs.toDays();
        long hours = abs.toHours() % 24;
        int minutes = (int) (abs.toMinutes() % 60);
        int seconds = (int) (abs.getSeconds() % 60);
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        if (days > 0) {
            sb.append(String.format("%d %02d:%02d:%02d", days, hours, minutes, seconds));
        } else {
            sb.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        }
        if (abs.getNano() != 0) {
            sb.append(String.format(".%09d", abs.getNano()));
        }
        return sb.toString();
    }

    ////////////////////////////////////////////////////////////////
    // datetime / date 族：与 MycatBuiltInMethodImpl.stringToTimestamp 对拍
    ////////////////////////////////////////////////////////////////

    @Test
    public void datetimeNoFractionMatchesReference() {
        String input = "2020-01-01 10:00:00";
        LocalDateTime reference = MycatBuiltInMethodImpl.stringToTimestamp(input);
        assertEquals(format(reference), StringToTimestampFunction.stringToTimestamp(input));
        assertEquals("2020-01-01 10:00:00", StringToTimestampFunction.stringToTimestamp(input));
    }

    @Test
    public void datetimeWithFractionMatchesReference() {
        String input = "2020-01-01 10:00:00.123";
        LocalDateTime reference = MycatBuiltInMethodImpl.stringToTimestamp(input);
        assertEquals(format(reference), StringToTimestampFunction.stringToTimestamp(input));
        assertEquals("2020-01-01 10:00:00.123000", StringToTimestampFunction.stringToTimestamp(input));
    }

    @Test
    public void dateOnlyMatchesReference() {
        String input = "2020-01-01";
        LocalDateTime reference = MycatBuiltInMethodImpl.stringToTimestamp(input);
        assertEquals(format(reference), StringToTimestampFunction.stringToTimestamp(input));
        assertEquals("2020-01-01 00:00:00", StringToTimestampFunction.stringToTimestamp(input));
    }

    @Test
    public void timeOnlyMatchesReference() {
        String input = "12:30:45";
        // reference: timestampStringToTimestamp → LocalTime → 函数以 TIME 形态输出
        LocalTime reference = (LocalTime) MycatBuiltInMethodImpl.timestampStringToTimestamp(input);
        assertEquals(format(Duration.between(LocalTime.MIDNIGHT, reference)),
                StringToTimestampFunction.stringToTimestamp(input));
        assertEquals("12:30:45", StringToTimestampFunction.stringToTimestamp(input));
    }

    ////////////////////////////////////////////////////////////////
    // TIME 简写族：与 MycatTimeUtil.timeStringToTimeDuration 对拍
    ////////////////////////////////////////////////////////////////

    @Test
    public void mssThreeDigitsMustParseMinutesAndSeconds() {
        // 修复前 substring(1,2) 只取 1 位秒：123 → 00:01:02（错）；修复后 00:01:23
        assertEquals(format(MycatTimeDurationParseBridge.parse("123")),
                StringToTimestampFunction.stringToTimestamp("123"));
        assertEquals("00:01:23", StringToTimestampFunction.stringToTimestamp("123"));
    }

    @Test
    public void mmssFourDigits() {
        assertEquals("00:11:23", StringToTimestampFunction.stringToTimestamp("1123"));
    }

    @Test
    public void hmmssFiveDigits() {
        assertEquals("01:23:45", StringToTimestampFunction.stringToTimestamp("12345"));
    }

    @Test
    public void hhmmssSixDigits() {
        assertEquals("12:34:56", StringToTimestampFunction.stringToTimestamp("123456"));
    }

    @Test
    public void twoDigitsMeansSeconds() {
        assertEquals("00:00:56", StringToTimestampFunction.stringToTimestamp("56"));
    }

    @Test
    public void mmssColonForm() {
        assertEquals("00:30:45", StringToTimestampFunction.stringToTimestamp("30:45"));
    }

    @Test
    public void daysHoursMinutesSecondsForm() {
        // 修复前 MycatTimeUtil default 分支丢弃 days 前缀；修复后 "1 12:00:00" = 36h
        assertEquals("1 12:00:00", StringToTimestampFunction.stringToTimestamp("1 12:00:00"));
    }

    @Test
    public void negativeDaysForm() {
        // 修复前 '-' 分支 parseInt("-1 12") 抛 NFE；修复后 -36h
        assertEquals("-12:00:00", StringToTimestampFunction.stringToTimestamp("-1 12:00:00"));
    }

    @Test
    public void fractionOnTimeAbbreviation() {
        assertEquals("00:01:23.500000000",
                StringToTimestampFunction.stringToTimestamp("123.5"));
    }

    ////////////////////////////////////////////////////////////////
    // 非法输入：NULL 不抛
    ////////////////////////////////////////////////////////////////

    @Test
    public void illegalInputsReturnNullWithoutThrowing() {
        assertNull(StringToTimestampFunction.stringToTimestamp(null));
        assertNull(StringToTimestampFunction.stringToTimestamp(""));
        assertNull(StringToTimestampFunction.stringToTimestamp("   "));
        assertNull(StringToTimestampFunction.stringToTimestamp("abc"));
        assertNull(StringToTimestampFunction.stringToTimestamp("2020-13-99"));
        assertNull(StringToTimestampFunction.stringToTimestamp("--"));
        assertNull(StringToTimestampFunction.stringToTimestamp("12:aa:bb"));
    }

    /** 对拍桥：直接引用 common 的解析器（修复 MSS 后的基准）。 */
    private static final class MycatTimeDurationParseBridge {
        static Duration parse(String text) {
            return io.mycat.MycatTimeUtil.timeStringToTimeDuration(text);
        }
    }
}
