package io.mycat.calcite;

import cn.mycat.vertx.xa.MySQLManager;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.newquery.MysqlCollector;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * I-DEBT-41 切片2 ①：向<b>可跑全句的单一后端</b>取合成查询的归并列元数据（describe），作为
 * 异构多源合并列类型的对外权威——归并规则交还给该 persona 后端自己算（bit∪bigint→DECIMAL(19)
 * 等 wire 原生行为），不内建规则表、不以 Calcite 坍缩类型臆造。
 *
 * <p><b>合成文本</b>：各分支 bind 期登记的下推 SQL（已是物理表名、静态文本）直接以
 * {@code s1 union all s2 ... limit 0} 顶层拼接——与真后端直接跑该 union 的形状同构（列
 * 归并类型/precision 均由后端按自身规则顶层计算），{@code limit 0} 只取列元数据不取行。
 * 子查询包裹会改变派生列形状（实测 precision 19→254），故不采用。
 * 分支自带 order by/limit 会破坏顶层拼接语义 → 调用方登记处已标不可探测（弃探回 ③）。
 *
 * <p><b>门槛（任一不过即放弃探测回 ③ 计划轨，诚实保守面）</b>：分支数与顶层 Union 输入吻合、
 * 全部同 ds、全部无 {@code ?} 参数；调用方（dataContext）保证。本类只管执行与失败吞噬。
 *
 * <p><b>连接</b>：独立走 {@code MySQLManager.getConnection}（不经本查询 limit 队列，避免与分支
 * 共享连接引发协议交错），用毕即 {@code close()} 归还池。同步等待带硬超时——本探测发生在列头
 * 发送点（首行就绪后），毫秒级一次后端往返。
 */
public final class BackendMergedMetaProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendMergedMetaProbe.class);

    private BackendMergedMetaProbe() {
    }

    /** 探测成功返回后端归并列元数据（通常 MycatMySQLRowMetaData 忠实轨）；任何失败/超时返回 null。 */
    public static MycatRowMetaData probe(MySQLManager manager, String dsKey, List<String> branchSqls,
                                         long timeoutMillis) {
        if (manager == null || dsKey == null || branchSqls == null || branchSqls.size() < 2) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < branchSqls.size(); i++) {
            if (i > 0) {
                text.append(" union all ");
            }
            text.append(branchSqls.get(i));
        }
        text.append(" limit 0");
        final String probeSql = text.toString();
        NewMycatConnection connection = null;
        try {
            Future<NewMycatConnection> connectionFuture = manager.getConnection(dsKey);
            connection = connectionFuture.toCompletionStage().toCompletableFuture()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<MycatRowMetaData> metaRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            connection.prepareQuery(probeSql, Collections.emptyList(), new MysqlCollector() {
                @Override
                public void onColumnDef(MycatRowMetaData mycatRowMetaData) {
                    metaRef.set(mycatRowMetaData);
                }

                @Override
                public void onRow(Object[] row) {
                    // limit 0：不应有行；忽略
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable e) {
                    errorRef.set(e);
                    latch.countDown();
                }
            });
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("I-DEBT-41 merged-meta probe timeout ds={} sql={}", dsKey, probeSql);
                return null;
            }
            if (errorRef.get() != null) {
                LOGGER.warn("I-DEBT-41 merged-meta probe backend error: {}", errorRef.get().toString());
                return null;
            }
            return metaRef.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.warn("I-DEBT-41 merged-meta probe failed: {}", e.toString());
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignore) {
                    // 归还失败不影响本次探测结论（结果已取或已弃）
                }
            }
        }
    }
}
