package io.mycat.calcite;

import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;

/**
 * I-DEBT-25: a placeholder SqlNode that carries a raw SQL text fragment (used by the
 * MycatTransientSQLTableScan / fromSql / fromRelToSql path).
 *
 * <p>It historically extended SqlLiteral with SqlTypeName.VARCHAR, but Calcite's
 * {@link SqlLiteral#valueMatchesType} has no VARCHAR branch (VARCHAR is intentionally
 * "not allowed -- use Char"), so constructing it threw an AssertionError at build time.
 * SqlNode's own constructor is package-private, so we cannot extend SqlNode directly.
 *
 * <p>Fix: construct as CHAR + {@link NlsString} (which valueMatchesType accepts), while
 * overriding {@link #unparse} to emit the raw SQL verbatim. This keeps the semantic
 * contract that the wrapped text is inlined as-is into the physical statement rather
 * than quoted as a string literal.
 */
public class TextSqlNode extends SqlLiteral {

    private final String rawSql;

    public TextSqlNode(Object value) {
        super(new NlsString(String.valueOf(value), null, null), SqlTypeName.CHAR, SqlParserPos.ZERO);
        this.rawSql = String.valueOf(value);
    }

    /**
     * Emits the wrapped SQL text verbatim, bypassing the default CHAR literal quoting.
     */
    @Override
    public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
        writer.literal(rawSql);
    }

    public String getRawSql() {
        return rawSql;
    }
}
