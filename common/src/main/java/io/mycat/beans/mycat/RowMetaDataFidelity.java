package io.mycat.beans.mycat;

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

    /** 委托计划 meta 的一切，唯独 {@code getColumnType} 取后端忠实轨物理类型。 */
    private static final class FaithfulTypeView implements MycatRowMetaData {
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
