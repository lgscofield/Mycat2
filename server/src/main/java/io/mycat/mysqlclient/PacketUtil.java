/**
 * Copyright (C) <2022>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */

package io.mycat.mysqlclient;

import io.mycat.MySQLPacketUtil;
import io.mycat.vertx.ReadView;
import io.netty.handler.codec.DecoderException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.mysqlclient.impl.protocol.Packets;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.temporal.ChronoField.*;

public class PacketUtil {
    private static final java.time.format.DateTimeFormatter DATETIME_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .appendFraction(MICRO_OF_SECOND, 0, 6, true)
            .toFormatter();
    public static Throwable handleErrorPacketPayload(Buffer payload) {
        ReadView readView = new ReadView(payload);
        readView.skipInReading(1); // skip ERR packet header
        int errorCode = (int) readView.readFixInt(2);
        // CLIENT_PROTOCOL_41 capability flag will always be set
        readView.skipInReading(1); // SQL state marker will always be #
        String sqlState = readView.readFixString(5);
        String errorMessage = readView.readEOFString();
        return (new SQLException(errorMessage,sqlState, errorCode ));
    }

    // simplify the ok packet as those properties are actually not used for now
    public static Packets.OkPacket decodeOkPacketPayload(Buffer payload) {
        ReadView readView = new ReadView(payload);
        readView.skipInReading(1);
        long affectedRows = readView.readLenencInt();
        long lastInsertId = readView.readLenencInt();
        int serverStatusFlags = readView.readLenencInt().intValue();
        String statusInfo = null;
        String sessionStateInfo = null;
        // MYCAT CHANGE: Vert.x 4.5.14 OkPacket constructor changed to (int, long, int)
        return new Packets.OkPacket((int) affectedRows, lastInsertId, serverStatusFlags);
    }

    /**
     * I-DEBT-69 Blade G（刀2）：解析 OK 包第 4 段 warnings（PROTOCOL_41 定长 2 字节）。
     *
     * <p>既有 {@code decodeOkPacketPayload} 注释自证丢弃该字段，导致 NATIVE 型
     * datasource 的 DML 告警计数在代理侧整体失踪。本方法按协议正解独立解析：
     * header(1) + affectedRows/lastInsertId(lenenc) + status(定长 2)，残长不足 2 字节
     * 或提前 EOF（旧协议无此段）一律退化 0，绝不反抛。
     */
    public static int decodeOkPacketWarnings(Buffer payload) {
        ReadView readView = new ReadView(payload);
        readView.skipInReading(1);
        readView.readLenencInt();
        readView.readLenencInt();
        readView.readFixInt(2);
        if (readView.readFinished() || readView.readable() < 2) {
            return 0;
        }
        return (int) readView.readFixInt(2);
    }

    /**
     * I-DEBT-69·残 NATIVE SELECT 腿：解析结果集终止包（EOF/OK）携带的 warnings 计数。
     *
     * <p>终止包有两种形，取决于连接是否协商 {@code CLIENT_DEPRECATE_EOF}（本代理经
     * {@code ConnectHandler} 对全目标 persona 默认置位，故多为 OK 形）：
     * <ul>
     *   <li>{@code deprecateEof=true}：OK-body 形 {@code 0xFE + affected_rows(lenenc) +
     *       last_insert_id(lenenc) + status(fixint 2) + warnings(fixint 2)}——warnings 在 status 后；</li>
     *   <li>{@code deprecateEof=false}：经典 EOF 形 {@code 0xFE + warnings(fixint 2) + status(fixint 2)}
     *       ——warnings 在 status 前。</li>
     * </ul>
     * 残长不足 2 字节或提前 EOF 一律退化 0，绝不反抛。仅取计数，明细由连接层同串行队列接力
     * {@code SHOW WARNINGS} 补齐（承重复绿禁令：计数不得合成明细）。
     */
    public static int decodeEofPacketWarnings(Buffer payload, boolean deprecateEof) {
        ReadView readView = new ReadView(payload);
        readView.skipInReading(1); // skip packet header 0xFE
        if (deprecateEof) {
            if (readView.readFinished()) {
                return 0;
            }
            readView.readLenencInt(); // affected_rows
            if (readView.readFinished()) {
                return 0;
            }
            readView.readLenencInt(); // last_insert_id
            if (readView.readable() < 2) {
                return 0;
            }
            readView.readFixInt(2);   // status_flags
        }
        // 两种形走到此处：deprecate 时余下为 warnings；经典 EOF 时紧接 header 即 warnings。
        if (readView.readFinished() || readView.readable() < 2) {
            return 0;
        }
        return (int) readView.readFixInt(2);
    }

    public static byte writeQueryText(NetSocket socket,String text){
        socket.write(Buffer.buffer( MySQLPacketUtil.generateComQueryPacket(text)));
        return (byte) 0;
    }

    public static long decodeDecStringToLong(int index, int len, Buffer buff) {
        long value = 0;
        if (len > 0) {
            int to = index + len;
            boolean neg = false;
            if (buff.getByte(index) == '-') {
                neg = true;
                index++;
            }
            while (index < to) {
                byte ch = buff.getByte(index++);
                byte nibble = (byte) (ch - '0');
                value = value * 10 + nibble;
            }
            if (neg) {
                value = -value;
            }
        }
        return value;
    }

    public static Duration textDecodeTime(String timeString) {
        // HH:mm:ss or HHH:mm:ss
        boolean isNegative = timeString.charAt(0) == '-';
        if (isNegative) {
            timeString = timeString.substring(1);
        }

        String[] timeElements = timeString.split(":");
        if (timeElements.length != 3) {
            throw new DecoderException("Invalid time format");
        }

        int hour = Integer.parseInt(timeElements[0]);
        int minute = Integer.parseInt(timeElements[1]);
        int second = Integer.parseInt(timeElements[2].substring(0, 2));
        long nanos = 0;
        if (timeElements[2].length() > 2) {
            double fractionalSecondsPart = Double.parseDouble("0." + timeElements[2].substring(3));
            nanos = (long) (1000000000 * fractionalSecondsPart);
        }
        if (isNegative) {
            return Duration.ofHours(-hour).minusMinutes(minute).minusSeconds(second).minusNanos(nanos);
        } else {
            return Duration.ofHours(hour).plusMinutes(minute).plusSeconds(second).plusNanos(nanos);
        }
    }
    public static LocalDateTime textDecodeDateTime(String value) {
        if (value.equals("0000-00-00 00:00:00")) {
            // Invalid datetime will be converted to zero
            return null;
        }
        return LocalDateTime.parse(value, DATETIME_FORMAT);
    }

    public static long decodeBit(Buffer buffer, int index, int length) {
        byte[] value = buffer.getBytes(index,length);
        long result = 0;
        for (byte b : value) {
            result = (b & 0xFF) | (result << 8);
        }
        return result;
    }
}
