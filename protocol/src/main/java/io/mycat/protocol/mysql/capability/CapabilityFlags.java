package io.mycat.protocol.mysql.capability;

/**
 * MySQL/MariaDB client-server capability flags.
 *
 * <p>Capability flags are bit fields exchanged during the handshake to negotiate
 * supported features between client and server.
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/group__group__cs__capabilities__flags.html">MySQL Capability Flags</a>
 */
public final class CapabilityFlags {

    private CapabilityFlags() {}

    // MySQL standard capability flags
    public static final long CLIENT_LONG_PASSWORD       = 0x00000001L;
    public static final long CLIENT_FOUND_ROWS          = 0x00000002L;
    public static final long CLIENT_LONG_FLAG           = 0x00000004L;
    public static final long CLIENT_CONNECT_WITH_DB     = 0x00000008L;
    public static final long CLIENT_NO_SCHEMA           = 0x00000010L;
    public static final long CLIENT_COMPRESS            = 0x00000020L;
    public static final long CLIENT_ODBC                = 0x00000040L;
    public static final long CLIENT_LOCAL_FILES         = 0x00000080L;
    public static final long CLIENT_IGNORE_SPACE        = 0x00000100L;
    public static final long CLIENT_PROTOCOL_41         = 0x00000200L;
    public static final long CLIENT_INTERACTIVE         = 0x00000400L;
    public static final long CLIENT_SSL                 = 0x00000800L;
    public static final long CLIENT_IGNORE_SIGPIPE      = 0x00001000L;
    public static final long CLIENT_TRANSACTIONS        = 0x00002000L;
    public static final long CLIENT_RESERVED            = 0x00004000L;
    public static final long CLIENT_SECURE_CONNECTION   = 0x00008000L;
    public static final long CLIENT_MULTI_STATEMENTS    = 0x00010000L;
    public static final long CLIENT_MULTI_RESULTS       = 0x00020000L;
    public static final long CLIENT_PS_MULTI_RESULTS    = 0x00040000L;
    public static final long CLIENT_PLUGIN_AUTH         = 0x00080000L;
    public static final long CLIENT_CONNECT_ATTRS       = 0x00100000L;
    public static final long CLIENT_PLUGIN_AUTH_LENENC  = 0x00200000L;
    public static final long CLIENT_DEPRECATE_EOF       = 0x01000000L;

    // MariaDB-specific capability flags (upper 32 bits)
    public static final long MARIADB_CLIENT_PROGRESS             = 1L << 32;
    public static final long MARIADB_CLIENT_COM_MULTI            = 1L << 33;
    public static final long MARIADB_CLIENT_STMT_BULK_OPERATIONS = 1L << 34;
    public static final long MARIADB_CLIENT_EXTENDED_TYPE_INFO   = 1L << 35;
    public static final long MARIADB_CLIENT_CACHE_METADATA       = 1L << 36;

    /**
     * Compute the intersection of client-requested and server-supported capabilities.
     */
    public static long negotiate(long clientFlags, long serverFlags) {
        return clientFlags & serverFlags;
    }

    /** Check whether a specific capability flag is set. */
    public static boolean has(long flags, long flag) {
        return (flags & flag) != 0;
    }
}
