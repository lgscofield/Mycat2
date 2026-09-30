package io.mycat.protocol.codec;

/**
 * Protocol codec abstraction for MySQL/MariaDB packet encoding and decoding.
 *
 * <p>Implementations handle the wire-format differences between MySQL and MariaDB
 * protocol variants.
 */
public interface ProtocolCodec {

    /**
     * Detect the protocol variant from a handshake packet.
     *
     * @param handshakePacket the initial handshake packet bytes from the server
     * @return the detected protocol variant
     */
    ProtocolVariant detect(byte[] handshakePacket);

    /**
     * Encode a logical packet to wire-format bytes.
     *
     * @param sequenceId the MySQL packet sequence number
     * @param payload    the packet payload
     * @return complete packet bytes including 4-byte header
     */
    byte[] encode(int sequenceId, byte[] payload);

    /**
     * Decode wire-format bytes to a logical packet payload.
     *
     * @param data complete packet bytes including 4-byte header
     * @return the decoded payload (without header)
     */
    byte[] decode(byte[] data);

    /** Supported MySQL/MariaDB protocol variants. */
    enum ProtocolVariant {
        MYSQL_57,
        MYSQL_80,
        MYSQL_84,
        MYSQL_9X,
        MARIADB_1011,
        MARIADB_11X
    }
}
