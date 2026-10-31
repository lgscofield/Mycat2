package io.mycat.newquery;

import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.WarningMessage;

import java.sql.SQLException;
import java.util.List;

public interface MysqlCollector {
    void onColumnDef(MycatRowMetaData mycatRowMetaData);
    void onRow(Object[] row);
    void onComplete();
    void onError(Throwable e);

    /**
     * I-DEBT-69 Blade D 余项（Query 路径）：后端语句产生的诊断条件，在 {@link #onComplete()}
     * 之前回调（告警属连接/语句级状态，必须在 Statement 关闭前取走）。
     * 默认空实现，既有采集器无需改动。
     */
    default void onWarnings(List<WarningMessage> warnings) {
    }
}
