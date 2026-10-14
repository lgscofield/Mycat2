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
            return backend.getColumnType(column);
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
            return backend.getColumnDisplaySize(column);
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
