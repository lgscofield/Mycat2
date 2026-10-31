package io.mycat.newquery;

import io.mycat.beans.mycat.WarningMessage;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class SqlResult {
    long affectRows;
    long lastInsertId;

    /**
     * I-DEBT-69 Blade D：后端语句产生的诊断条件（按产生顺序）。
     * 计数由列表派生，避免"计数与明细各算各的"。
     */
    private List<WarningMessage> warnings = Collections.emptyList();

    public int getWarningCount() {
        return getWarnings().size();
    }

    public void setWarnings(List<WarningMessage> warnings) {
        this.warnings = warnings == null ? Collections.emptyList() : warnings;
    }

    public long[] toLongs() {
        return new long[]{affectRows, lastInsertId};
    }

    public static SqlResult of(long affectRows,
                               long lastInsertId) {
        return of(affectRows, lastInsertId, Collections.emptyList());
    }

    public static SqlResult of(long affectRows,
                               long lastInsertId,
                               List<WarningMessage> warnings) {
        SqlResult sqlResult = new SqlResult();
        sqlResult.affectRows = affectRows;
        sqlResult.lastInsertId = lastInsertId;
        sqlResult.setWarnings(warnings);
        return sqlResult;
    }
}
