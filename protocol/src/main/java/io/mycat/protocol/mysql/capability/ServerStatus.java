package io.mycat.protocol.mysql.capability;

/**
 * MySQL/MariaDB server status flags returned in OK and EOF packets.
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/mysql__com_8h.html">Server Status Flags</a>
 */
public final class ServerStatus {

    private ServerStatus() {}

    public static final int SERVER_STATUS_IN_TRANS             = 0x0001;
    public static final int SERVER_STATUS_AUTOCOMMIT           = 0x0002;
    public static final int SERVER_MORE_RESULTS_EXISTS         = 0x0008;
    public static final int SERVER_STATUS_NO_GOOD_INDEX_USED   = 0x0010;
    public static final int SERVER_STATUS_NO_INDEX_USED        = 0x0020;
    public static final int SERVER_STATUS_CURSOR_EXISTS        = 0x0040;
    public static final int SERVER_STATUS_LAST_ROW_SENT        = 0x0080;
    public static final int SERVER_STATUS_DB_DROPPED           = 0x0100;
    public static final int SERVER_STATUS_NO_BACKSLASH_ESCAPES = 0x0200;
    public static final int SERVER_STATUS_METADATA_CHANGED     = 0x0400;
    public static final int SERVER_QUERY_WAS_SLOW              = 0x0800;
    public static final int SERVER_PS_OUT_PARAMS               = 0x1000;
    public static final int SERVER_STATUS_IN_TRANS_READONLY    = 0x2000;
    public static final int SERVER_SESSION_STATE_CHANGED       = 0x4000;

    /** Check whether a specific status flag is set. */
    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
