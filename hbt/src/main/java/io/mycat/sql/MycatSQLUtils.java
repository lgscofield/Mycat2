package io.mycat.sql;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.sql.visitor.MycatOutputVisitor;

public class MycatSQLUtils {
    public static String toString(SQLStatement statement) {
        StringBuilder sb = new StringBuilder();
        MycatOutputVisitor mycatOutputVisitor = new MycatOutputVisitor(sb, false);
        statement.accept(mycatOutputVisitor);
        return sb.toString();
    }
}
