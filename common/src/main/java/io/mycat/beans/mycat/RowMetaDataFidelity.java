package io.mycat.beans.mycat;

import io.mycat.beans.mysql.packet.ColumnDefPacket;

import java.sql.ResultSetMetaData;

/**
 * 跨片/本地计划结果的忠实列类型归一（I-DEBT-34 跨片列头保真）。
 *
 * <p>MyCat 有两把列头尺子：忠实轨 {@link MycatMySQLRowMetaData}（后端回包 {@code columnDefPackets}，
 * {@code getColumnType} 经 {@code DataType.parseDataType} 保真含 {@code Types.BIT}）与有损轨（计划派生
 * meta，因 fork 无 {@code SqlTypeName.BIT}、{@code Types.BIT→BOOLEAN}/bit(n) 归数值而丢物理类型）。跨片
 * 归并/本地计划在行解码缝与列头装配缝误用有损轨覆盖忠实轨，致 {@code bit(n>1)} 列头降为数值、行值
 * {@code byte[]} 落 {@code BaseRowObservable} 数值分支抛 {@code UnsupportedOperationException} 崩会话。
 *
 * <p>{@link #effective} 在后端列数不少于计划列数且后端为忠实轨时，返回以<em>后端物理类型为准</em>、
 * <em>列名/label 仍以计划逻辑视图为准</em>的归一视图；否则原样返回计划 meta（纯本地计算列的 C 兜底）。
 * 跨片 {@code order by} 会把排序键追加下推到后端 SELECT（后端列数 &gt; 计划列数），计划 project 掉尾部
 * 排序键后列头列数少于后端；前导输出列与后端按位对齐，故守卫放宽为 {@code >=}，多余尾部列被归一视图忽略。
 * 与 {@link CopyMycatRowMetaData}/{@link RenameMycatRowMetaData} 同族。
 */
public final class RowMetaDataFidelity {

    private RowMetaDataFidelity() {
    }

    /**
     * @param backendMeta 后端忠实轨 meta（{@code onColumnDef} 回包，可能为 null）
     * @param planMeta    计划派生（有损）meta，携带逻辑列视图
     * @return 归一后的有效 meta：忠实列类型 + 计划列名；不满足归一条件时返回 {@code planMeta}
     */
    public static MycatRowMetaData effective(MycatRowMetaData backendMeta, MycatRowMetaData planMeta) {
        if (planMeta == null) {
            return backendMeta;
        }
        if (backendMeta instanceof MycatMySQLRowMetaData
                && backendMeta.getColumnCount() >= planMeta.getColumnCount()) {
            return new FaithfulTypeView(backendMeta, planMeta);
        }
        return planMeta;
    }

    /**
     * 终审 Important-1（I-DEBT-45 收口轮）：异构探测权威（{@code probeMergedBackendMeta} 合成 SQL 结果）
     * 列名天然对齐，但物理表名/库名是探测副作用不应透传给客户端；计划 Review Focus#1 “多源异构不得进透传分支”。
     * 本方法保留忠实类型归一，但 {@code rawColumnDef} 一律回 null → 发射缝走重建轨。
     */
    public static MycatRowMetaData effectiveWithSuppressedRaw(MycatRowMetaData backendMeta, MycatRowMetaData planMeta) {
        if (planMeta == null) {
            return backendMeta;
        }
        if (backendMeta instanceof MycatMySQLRowMetaData
                && backendMeta.getColumnCount() >= planMeta.getColumnCount()) {
            return new RawSuppressedView(backendMeta, planMeta);
        }
        return planMeta;
    }

    /**
     * I-DEBT-41 列类型唯一权威·解码缝入口：{@code planTrackAuthority} 置位（多源 Union 合并，单分支
     * 后端轨不是合法权威）时原样返回计划 meta，与列头缝同源；未置位等价 {@link #effective}。
     *
     * @param backendMeta      本扫描后端忠实轨 meta（onColumnDef 回包）
     * @param planMeta         本扫描计划派生 meta
     * @param planTrackAuthority per-query 异构权威决策（发首行前计划期定死）
     * @return 置位 → {@code planMeta}；否则 {@code effective(backendMeta, planMeta)}
     */
    public static MycatRowMetaData decodeAuthority(MycatRowMetaData backendMeta, MycatRowMetaData planMeta,
                                                   boolean planTrackAuthority) {
        if (planTrackAuthority) {
            return planMeta == null ? backendMeta : planMeta;
        }
        return effective(backendMeta, planMeta);
    }

    /**
     * I-DEBT-41 终审 Important-2：异构多源合并（{@code mergedHeterogeneousOutput} 置位）时，解码缝一律走
     * 计划轨 numeric（{@code isPlanTrackDecode}）。若列头探测权威取到的后端归并列是 <b>binary 族</b>
     * （某些 persona 如 5.7/MariaDB 把 {@code bit∪bigint} 归并为 VARBINARY），拿它重建 binary 列头与
     * numeric 行值分叉——客户端在该 binary 列头上把 numeric 值读成 {@code null}（实测：Mycat 5.7 跨源
     * union 行值全 null，严格劣于改前响亮崩）。故命中 binary 族即须<b>整体拒绝</b>该探测权威、回 ③ 计划轨，
     * 使两缝同 numeric 保持一致（仅失列头类型忠实，值数据正确不丢）。数值族（如 8.0 的 DECIMAL）返回
     * false，保留 ① 后端为准的保真胜利。
     *
     * @param probeMeta 单后端 describe 取回的归并列元数据（可为 null）
     * @return 任一列为 {@code BINARY/VARBINARY/LONGVARBINARY/BLOB} 族 → true（应拒绝回 ③）；否则 false
     */
    public static boolean isBinaryFamilyAuthority(MycatRowMetaData probeMeta) {
        if (probeMeta == null) {
            return false;
        }
        for (int i = 0; i < probeMeta.getColumnCount(); i++) {
            switch (probeMeta.getColumnType(i)) {
                case java.sql.Types.BINARY:
                case java.sql.Types.VARBINARY:
                case java.sql.Types.LONGVARBINARY:
                case java.sql.Types.BLOB:
                    return true;
                default:
                    // 其余列继续扫描
                    break;
            }
        }
        return false;
    }

    /** 终审 Important-1：异构探测权威屏蔽 rawColumnDef 的变体视图，保留类型忠实但禁止透传。 */
    private static final class RawSuppressedView extends FaithfulTypeView {
        RawSuppressedView(MycatRowMetaData backend, MycatRowMetaData plan) {
            super(backend, plan);
        }

        @Override
        public ColumnDefPacket rawColumnDef(int column) {
            // 多源异构探测的列包携带合成 SQL 的物理表名/库名，不得透传（计划 Review Focus#1）。
            return null;
        }
    }

    /** 委托计划 meta 的一切，唯独 {@code getColumnType} 取后端忠实轨物理类型。 */
    private static class FaithfulTypeView implements MycatRowMetaData {
        private final MycatRowMetaData backend;
        private final MycatRowMetaData plan;

        FaithfulTypeView(MycatRowMetaData backend, MycatRowMetaData plan) {
            this.backend = backend;
            this.plan = plan;
        }

        @Override
        public int getColumnType(int column) {
            // I-DEBT-42：忠实覆写只在“该位后端列名 == 计划列名”时成立。跨片单表直通（含 ORDER BY 下推
            // 的尾部排序键）前导列逐位对齐，覆写安全；但 join/聚合/带计算列投影的计划，carrier 里的后端
            // 轨是某个“输入叶子扫描”的形状，与计划输出不按位对应——无守卫直取后端类型会把输入列的物理
            // 类型（如 traveldate=DATE/BIT）沉默套到语义完全不同的输出列（如 companyname=VARCHAR）上，
            // 造成列头与行值错位的沉默数据损坏。异名列一律回计划轨（Calcite 派生，对非 BIT 类型本就正确）。
            String backendCol = backend.getColumnName(column);
            String planCol = plan.getColumnName(column);
            if (backendCol != null && backendCol.equalsIgnoreCase(planCol)) {
                return backend.getColumnType(column);
            }
            return plan.getColumnType(column);
        }

        @Override
        public ColumnDefPacket rawColumnDef(int column) {
            // I-DEBT-45：与 getColumnType 同源的列名对齐守卫——仅当该位后端列名 == 计划列名时，
            // 才透传后端原始 column-def 包（消灭 VARBINARY→LONGBLOB 形态失真）；异名列返回 null 回重建轨，
            // 避免把输入叶子的物理元数据沉默套到语义不同的输出列（I-DEBT-42 教训同源）。
            String backendCol = backend.getColumnName(column);
            if (backendCol != null && backendCol.equalsIgnoreCase(plan.getColumnName(column))) {
                return backend.rawColumnDef(column);
            }
            return null;
        }

        @Override
        public int getColumnCount() {
            return plan.getColumnCount();
        }

        @Override
        public boolean isAutoIncrement(int column) {
            return plan.isAutoIncrement(column);
        }

        @Override
        public boolean isCaseSensitive(int column) {
            return plan.isCaseSensitive(column);
        }

        @Override
        public boolean isSigned(int column) {
            return plan.isSigned(column);
        }

        @Override
        public int getColumnDisplaySize(int column) {
            // 物理表征忠实：位长/长度取后端忠实轨（如 bit(8)=8），供列头重建生成正确的 wire column_length；
            // 否则跨片列头被重建为默认长度会误导客户端类型解释（bit(n>1) 降为 Boolean）。
            // I-DEBT-42：同样受列名对齐守卫约束——异名位的后端 displaySize 来自输入叶子，不属本输出列，应回计划轨。
            String backendCol = backend.getColumnName(column);
            if (backendCol != null && backendCol.equalsIgnoreCase(plan.getColumnName(column))) {
                return backend.getColumnDisplaySize(column);
            }
            return plan.getColumnDisplaySize(column);
        }

        @Override
        public String getColumnName(int column) {
            return plan.getColumnName(column);
        }

        @Override
        public String getSchemaName(int column) {
            return plan.getSchemaName(column);
        }

        @Override
        public int getPrecision(int column) {
            return plan.getPrecision(column);
        }

        @Override
        public int getScale(int column) {
            return plan.getScale(column);
        }

        @Override
        public String getTableName(int column) {
            return plan.getTableName(column);
        }

        @Override
        public String getColumnLabel(int column) {
            return plan.getColumnLabel(column);
        }

        @Override
        public ResultSetMetaData metaData() {
            return plan.metaData();
        }

        @Override
        public boolean isNullable(int column) {
            return plan.isNullable(column);
        }
    }
}
