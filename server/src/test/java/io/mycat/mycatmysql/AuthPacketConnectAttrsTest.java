package io.mycat.mycatmysql;

import io.mycat.beans.mysql.packet.AuthPacket;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter;
import io.mycat.vertx.ReadView;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class AuthPacketConnectAttrsTest {

    private int attrsOffset(byte[] bytes) {
        ReadView view = new ReadView(Buffer.buffer(bytes));
        int capabilities = (int) view.readFixInt(4);
        view.readFixInt(4);
        view.readByte();
        view.readBytes(AuthPacket.getRESERVED().length);
        view.readNULStringBytes();
        if (MySQLServerCapabilityFlags.isPluginAuthLenencClientData(capabilities)) {
            view.readLenencStringBytes();
        } else if (MySQLServerCapabilityFlags.isCanDo41Anthentication(capabilities)) {
            view.readBytes(view.readByte());
        } else {
            view.readNULStringBytes();
        }
        if (MySQLServerCapabilityFlags.isConnectionWithDatabase(capabilities)) {
            view.readNULStringBytes();
        }
        if (MySQLServerCapabilityFlags.isPluginAuth(capabilities)) {
            view.readNULStringBytes();
        }
        return bytes.length - view.readable();
    }

    private byte[] writePacket(Map<String, String> attrs) {
        return writePacket(attrs, MySQLServerCapabilityFlags.CLIENT_CONNECT_ATTRS);
    }

    private byte[] writePacket(Map<String, String> attrs, int capabilities) {
        AuthPacket packet = new AuthPacket();
        packet.setCapabilities(capabilities);
        packet.setUsername("u");
        packet.setPassword(new byte[0]);
        packet.setClientConnectAttrs(attrs);
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter()) {
            packet.writePayload(writer);
            return writer.toByteArray();
        }
    }

    private long readLenenc(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xff;
        if (first < 251) {
            return first;
        }
        if (first == 0xfc) {
            return (bytes[offset + 1] & 0xffL) | ((bytes[offset + 2] & 0xffL) << 8);
        }
        if (first == 0xfd) {
            return (bytes[offset + 1] & 0xffL)
                    | ((bytes[offset + 2] & 0xffL) << 8)
                    | ((bytes[offset + 3] & 0xffL) << 16);
        }
        throw new AssertionError("unexpected lenenc marker");
    }

    private int lenencSize(long value) {
        if (value < 251) {
            return 1;
        }
        if (value < (1 << 16)) {
            return 3;
        }
        if (value < (1 << 24)) {
            return 4;
        }
        return 9;
    }

    @Test
    void writesTotalLengthInUtf8BytesIncludingLenencPrefixes() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("键", "值🙂");
        attrs.put("program", "mycat");
        byte[] bytes = writePacket(attrs);

        long declared = readLenenc(bytes, attrsOffset(bytes));
        long actual = bytes.length - attrsOffset(bytes) - lenencSize(declared);
        assertEquals(actual, declared);
    }

    @Test
    void roundTripsMultipleNonAsciiAttributes() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("键", "值🙂");
        attrs.put("program", "mycat");

        AuthPacket packet = new AuthPacket();
        packet.readPayload(new ReadView(Buffer.buffer(writePacket(attrs))));

        assertEquals(attrs, packet.getClientConnectAttrs());
    }

    @Test
    void readsAttributesUsingByteLengthInsteadOfCharacterCount() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("键", "值🙂");
        attrs.put("program", "mycat");
        byte[] bytes = writePacket(attrs);
        int prefixSize = lenencSize(readLenenc(bytes, attrsOffset(bytes)));
        long actual = bytes.length - attrsOffset(bytes) - prefixSize;
        for (int i = 0; i < prefixSize; i++) {
            bytes[attrsOffset(bytes) + i] = (byte) (actual >>> (8 * i));
        }
        if (prefixSize == 1) {
            bytes[attrsOffset(bytes)] = (byte) actual;
        }

        AuthPacket packet = new AuthPacket();
        packet.readPayload(new ReadView(Buffer.buffer(bytes)));

        assertEquals(attrs, packet.getClientConnectAttrs());
    }

    @Test
    void totalLengthIncludesThreeByteLenencPrefixForLongKey() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("k".repeat(260), "v");
        byte[] bytes = writePacket(attrs);

        int offset = attrsOffset(bytes);
        long declared = readLenenc(bytes, offset);
        long actual = bytes.length - offset - lenencSize(declared);
        assertEquals(35, offset, "packet length " + bytes.length);
        assertEquals(265, actual);
        assertEquals(actual, declared);
    }

    @Test
    void emptyAttributeValueDoesNotConsumeItsLengthPrefixTwice() {
        Map<String, String> attrs = Map.of("k", "");

        AuthPacket packet = new AuthPacket();
        assertDoesNotThrow(() -> packet.readPayload(new ReadView(Buffer.buffer(writePacket(attrs)))));

        assertEquals(attrs, packet.getClientConnectAttrs());
    }

    @Test
    void acceptsZeroPaddingAfterDeclaredAttributesFromLegacyConnector() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("_runtime_version", "21.0.6");
        attrs.put("_client_version", "5.1.49");
        attrs.put("_client_license", "GPL");
        attrs.put("_runtime_vendor", "BellSoft");
        attrs.put("_client_name", "MySQL Connector Java");
        byte[] bytes = writePacket(attrs);
        int offset = attrsOffset(bytes);
        byte[] padded = Arrays.copyOf(bytes, bytes.length + 26);
        ReadView view = new ReadView(Buffer.buffer(Arrays.copyOfRange(padded, offset, padded.length)));

        assertEquals(attrs, AuthPacket.readConnectAttrs(view));
        assertEquals(0, view.readable());
    }

    @Test
    void trailingBytesOutsideDeclaredAttributesAreRejected() {
        ReadView view = new ReadView(Buffer.buffer(new byte[]{2, 1, 'k', 0, 99}));

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
    }

    @Test
    void zeroLengthAttributesWithTrailingBytesAreRejected() {
        ReadView view = new ReadView(Buffer.buffer(new byte[]{0, 99}));

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
    }

    @Test
    void attributeLengthPrefixCannotCrossDeclaredBlock() {
        boolean[] crossedDeclaredBlock = {false};
        ReadView view = new ReadView(Buffer.buffer(new byte[]{1, (byte) 0xfc, 0, 0})) {
            @Override
            public long readFixInt(int length) {
                crossedDeclaredBlock[0] = true;
                return super.readFixInt(length);
            }
        };

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
        assertFalse(crossedDeclaredBlock[0], "length prefix crossed the declared attributes block");
    }

    @Test
    void declaredAttributesLengthIsCheckedBeforeReadingEntries() {
        CountingReadView view = new CountingReadView(Buffer.buffer(new byte[]{10, 1, 'k', 1, 'v'}));

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
        assertEquals(0, view.lenencStringReads);
    }

    @Test
    void hugeAttributeKeyLengthIsRejectedWithoutAllocation() {
        NoAllocationReadView view = new NoAllocationReadView(Buffer.buffer(new byte[]{
                9, (byte) 0xfe, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0
        }));

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
    }

    @Test
    void truncatedAttributesLengthIsRejected() {
        ReadView view = new ReadView(Buffer.buffer(new byte[]{(byte) 0xfc}));

        assertThrows(IllegalArgumentException.class, () -> AuthPacket.readConnectAttrs(view));
    }

    @Test
    void hugeAuthenticationLengthIsRejectedWithoutAllocation() {
        int capabilities = MySQLServerCapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;
        byte[] bytes = writePacket(Map.of(), capabilities);
        int passwordOffset = 4 + 4 + 1 + AuthPacket.getRESERVED().length + 2;
        byte[] hugeLength = {(byte) 0xfe, 0x7f, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, 0, 0, 0, 0};
        byte[] malformed = Arrays.copyOf(bytes, bytes.length + hugeLength.length - 1);
        System.arraycopy(hugeLength, 0, malformed, passwordOffset, hugeLength.length);
        NoAllocationReadView view = new NoAllocationReadView(Buffer.buffer(malformed));

        assertThrows(IllegalArgumentException.class, () -> new AuthPacket().readPayload(view));
    }

    @Test
    void secureConnectionPasswordLengthIsUnsigned() {
        byte[] password = new byte[255];
        Arrays.fill(password, (byte) 0xa5);
        AuthPacket source = new AuthPacket();
        source.setCapabilities(MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION);
        source.setUsername("u");
        source.setPassword(password);
        byte[] bytes;
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter()) {
            source.writePayload(writer);
            bytes = writer.toByteArray();
        }

        AuthPacket parsed = new AuthPacket();
        parsed.readPayload(new ReadView(Buffer.buffer(bytes)));

        assertArrayEquals(password, parsed.getPassword());
    }

    @Test
    void truncatedSecureConnectionPasswordIsRejectedWithoutAllocation() {
        int capabilities = MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION;
        byte[] bytes = writePacket(Map.of(), capabilities);
        int passwordOffset = 4 + 4 + 1 + AuthPacket.getRESERVED().length + 2;
        bytes[passwordOffset] = (byte) 255;
        bytes = Arrays.copyOf(bytes, passwordOffset + 1);
        NoAllocationReadView view = new NoAllocationReadView(Buffer.buffer(bytes));

        assertThrows(IllegalArgumentException.class, () -> new AuthPacket().readPayload(view));
    }

    private static final class CountingReadView extends ReadView {
        private int lenencStringReads;

        private CountingReadView(Buffer buffer) {
            super(buffer);
        }

        @Override
        public byte[] readLenencStringBytes() {
            lenencStringReads++;
            return super.readLenencStringBytes();
        }
    }

    private static final class NoAllocationReadView extends ReadView {
        private NoAllocationReadView(Buffer buffer) {
            super(buffer);
        }

        @Override
        public byte[] readLenencStringBytes() {
            fail("malformed length reached string allocation");
            return null;
        }

        @Override
        public byte[] readFixStringBytes(int length) {
            fail("malformed length reached byte allocation");
            return null;
        }
    }
}
