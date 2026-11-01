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

    /**
     * I-DEBT-69 Blade G（刀2）：后端 OK 包原始告警计数（协议第 4 段）。
     * 仅作 NATIVE 池明细往返回路的触发源与对账记录，不参与派生计数；
     * 行数与计数不一致时如实保留行数，绝不合成假条目补差。
     */
    private int backendWarningCount;

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
