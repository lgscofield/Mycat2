package io.mycat;

import io.mycat.api.collector.MySQLColumnDef;
import io.mycat.api.collector.MysqlPayloadObject;
import io.mycat.beans.mycat.MycatMySQLRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import io.mycat.calcite.MycatBindable;
import io.mycat.calcite.PrepareExecutor;
import io.reactivex.rxjava3.core.Observable;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-34 跨片列头保真 · 列头缝保真契约锁（in-process）。
 *
 * <p>钉 Task 1 生产装配：{@link PrepareExecutor#getMysqlPayloadObjectObservable} 发出的
 * {@link MySQLColumnDef} 列头类型须以 per-query 上下文 {@link AsyncMycatDataContextImpl#getBackendRowMeta()}
 * 里的<em>后端忠实轨</em>（{@code Types.BIT}）为准，而非有损计划轨（BIGINT）；carrier 首写者胜。
 *
 * <p>这是列头缝（缝一）的生产级证据：喂真 context + 假 bindable 发一行，断言实际发出的列头 meta
 * 取后端 BIT。行解码缝（缝二）仍由 {@code CrossShardBitRowDecodeTest} 钉原语，两缝同移的端到端
 * 权威见证是活 e2e {@code caseCrossShardBitColumnFidelity}（Task 2）。
 */
public class CrossShardHeaderFidelityTest {

    private static final int FIELD_TYPE_BIT = 16;

    private static MycatMySQLRowMetaData faithfulBitMeta() {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName("c_bit8".getBytes());
        c.setColumnType(FIELD_TYPE_BIT);
        c.setColumnFlags(0);
        c.setColumnCharsetSet(63);
        List<ColumnDefPacket> list = new ArrayList<>();
        list.add(c);
        return new MycatMySQLRowMetaData(list);
    }

    private static MycatRowMetaData numericPlanMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT)
                .build().getMetaData();
    }

    /** 发一行（值任意）的假 bindable，让列头惰性发送门（headerSent）在首行触发。 */
    private static MycatBindable oneRowBindable() {
        return new MycatBindable() {
            @Override
            public Object bind(NewMycatDataContext root) {
                return Observable.just(new Object[]{new byte[]{(byte) 0xB3}});
            }

            @Override
            public Class getElementType() {
                return Object[].class;
            }
        };
    }

    @Test
    public void publishBackendRowMetaIsFirstWriterWins() {
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        MycatMySQLRowMetaData metaA = faithfulBitMeta();
        MycatMySQLRowMetaData metaB = faithfulBitMeta();
        ctx.publishBackendRowMeta(metaA);
        ctx.publishBackendRowMeta(metaB);
        Assertions.assertSame(metaA, ctx.getBackendRowMeta().get(),
                "identity 各片同构，carrier 须首写者胜、不被后续片覆盖");
    }

    @Test
    public void headerUsesBackendBitWhenCarrierHoldsFaithful() {
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        ctx.publishBackendRowMeta(faithfulBitMeta()); // 后端忠实轨 Types.BIT

        List<MysqlPayloadObject> out = PrepareExecutor
                .getMysqlPayloadObjectObservable(oneRowBindable(), ctx, numericPlanMeta())
                .toList().blockingGet();

        MySQLColumnDef def = (MySQLColumnDef) out.get(0);
        Assertions.assertEquals(Types.BIT, def.getMetaData().getColumnType(0),
                "列头缝须经 effective(carrier后端忠实轨, 计划有损轨) 取 Types.BIT，不被 BIGINT 覆盖");
    }

    @Test
    public void headerFallsBackToPlanTrackWhenFlaggedHeterogeneous() {
        // I-DEBT-41 列类型唯一权威：异构多源合并置位后，单分支 carrier 不再是合法权威，
        // 列头缝须退计划轨（与解码缝同源），不得继续用首写者分支的忠实轨。
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        ctx.publishBackendRowMeta(faithfulBitMeta()); // carrier 持有 BIT 忠实轨
        ctx.markMergedHeterogeneousOutput();          // 异构决策：降级 carrier

        List<MysqlPayloadObject> out = PrepareExecutor
                .getMysqlPayloadObjectObservable(oneRowBindable(), ctx, numericPlanMeta())
                .toList().blockingGet();

        MySQLColumnDef def = (MySQLColumnDef) out.get(0);
        Assertions.assertEquals(Types.BIGINT, def.getMetaData().getColumnType(0),
                "异构标志置位时列头须退计划轨（BIGINT），不再取单分支 carrier 的 BIT");
    }

    @Test
    public void emptyResultSetHeaderUsesPlanTrackWhenFlaggedHeterogeneous() {
        // Review Focus#4：零行时列头经空集补发路径求值，同样须遵异构权威决策退计划轨，不得两套行为。
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        ctx.publishBackendRowMeta(faithfulBitMeta());
        ctx.markMergedHeterogeneousOutput();

        MycatBindable emptyBindable = new MycatBindable() {
            @Override
            public Object bind(org.apache.calcite.runtime.NewMycatDataContext root) {
                return Observable.empty();
            }

            @Override
            public Class getElementType() {
                return Object[].class;
            }
        };

        List<MysqlPayloadObject> out = PrepareExecutor
                .getMysqlPayloadObjectObservable(emptyBindable, ctx, numericPlanMeta())
                .toList().blockingGet();

        MySQLColumnDef def = (MySQLColumnDef) out.get(0);
        Assertions.assertEquals(Types.BIGINT, def.getMetaData().getColumnType(0),
                "空集补发的列头亦须遵异构权威决策退计划轨");
    }

    @Test
    public void headerFallsBackToPlanWhenCarrierEmpty() {
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl ctx =
                new AsyncMycatDataContextImpl.SqlMycatDataContextImpl(null, null, null);
        // 不 publish：carrier 空（非分片源）→ effective(null, plan) 原样回计划轨（零回归兜底）

        List<MysqlPayloadObject> out = PrepareExecutor
                .getMysqlPayloadObjectObservable(oneRowBindable(), ctx, numericPlanMeta())
                .toList().blockingGet();

        MySQLColumnDef def = (MySQLColumnDef) out.get(0);
        Assertions.assertEquals(Types.BIGINT, def.getMetaData().getColumnType(0),
                "carrier 空时列头须原样用计划轨，行为与改动前一致（零回归）");
    }
}
