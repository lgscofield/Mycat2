package io.mycat.vertxmycat;

import io.vertx.jdbcclient.impl.JDBCRow;
import io.vertx.sqlclient.data.Numeric;
import io.vertx.sqlclient.impl.RowDesc;

public class MycatRow extends JDBCRow {
    // MYCAT CHANGE: Vert.x 4.5.14 JDBCRow no longer has copy constructor
    public MycatRow(io.vertx.sqlclient.impl.RowDesc desc) {
        super(desc);
    }

    @Override
    public Numeric getNumeric(int pos) {
        Object val = this.getValue(pos);
        if (val instanceof Numeric) {
            return (Numeric)val;
        }else if (val instanceof Boolean){
            return (Boolean) val ?Numeric.create(1):Numeric.create(0);
        }else {
            return val instanceof Number ? Numeric.create((Number)val) : null;
        }
    }
}
