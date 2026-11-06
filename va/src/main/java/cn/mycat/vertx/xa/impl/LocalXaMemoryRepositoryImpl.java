package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.ImmutableCoordinatorLog;
import cn.mycat.vertx.xa.ImmutableParticipantLog;
import cn.mycat.vertx.xa.MySQLManager;
import com.alibaba.druid.util.JdbcUtils;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 0：把"提交意向"从纯内存升级为持久。
 *
 * <p>历史上本类仅继承 {@link MemoryRepositoryImpl} 的内存实现：{@code writeCommitLog} 默认只 {@code put}
 * 到 {@code ConcurrentHashMap}，进程/会话一死提交意向即消失；而 {@link XaLogImpl#readXARecoveryLog} 靠
 * {@code select xid from mycat.xa_log} 判"已决提交→COMMIT"，但该表在生产从不 INSERT，导致 past-prepare 的
 * 已决提交被误回滚（丢已提交数据）。
 *
 * <p>本覆写在保留内存快路径语义（先 {@code super}）的前提下，把提交意向同步落盘到各参与者后端的
 * {@code mycat.xa_log}：{@code writeCommitLog} INSERT（失败即抛，让 commit 明确失败——宁可拒绝提交，不可
 * 假装持久）；{@code cancelCommitLog}/{@code remove}（决定回滚 / 完成清理）条件 DELETE（失败仅 WARN，交
 * recovery 端最终一致收敛）。表结构/读写 SQL 单一真源在 {@link XaLogSchema}。
 */
public class LocalXaMemoryRepositoryImpl extends MemoryRepositoryImpl {
    private final static Logger LOGGER = LoggerFactory.getLogger(LocalXaMemoryRepositoryImpl.class);
    private final Supplier<MySQLManager> mySQLManagerSupplier;
    private final long mycatId;
    /** 每个后端 DS 只需建库建表一次；INSERT 失败时回退标记以便下次重试补建。 */
    private final Set<String> schemaEnsuredTargets = ConcurrentHashMap.newKeySet();

    public static LocalXaMemoryRepositoryImpl createLocalXaMemoryRepository(Supplier<MySQLManager> mySQLManagerSupplier) {
        return createLocalXaMemoryRepository(mySQLManagerSupplier, 0);
    }

    public static LocalXaMemoryRepositoryImpl createLocalXaMemoryRepository(Supplier<MySQLManager> mySQLManagerSupplier, long mycatId) {
        LocalXaMemoryRepositoryImpl localXaMemoryRepository = new LocalXaMemoryRepositoryImpl(mySQLManagerSupplier, mycatId);
        localXaMemoryRepository.init();
        return localXaMemoryRepository;
    }

    private LocalXaMemoryRepositoryImpl(Supplier<MySQLManager> mySQLManagerSupplier, long mycatId) {
        this.mySQLManagerSupplier = mySQLManagerSupplier;
        this.mycatId = mycatId;
    }

    @Override
    public Future<Void> init() {
        return super.init();
    }

    @Override
    public Future<Void> close() {
        return super.close();
    }

    @Override
    public void writeCommitLog(ImmutableCoordinatorLog coordinatorLog) {
        // 先落内存（保持原有快路径/读回语义），再持久化提交意向。
        super.writeCommitLog(coordinatorLog);
        String xid = coordinatorLog.getXid();
        for (String target : targetsOf(coordinatorLog)) {
            Connection conn = null;
            try {
                conn = mySQLManagerSupplier.get().getWriteableConnection(target);
                ensureSchemaOnce(target, conn);
                XaLogSchema.insertCommitIntent(conn, xid, System.currentTimeMillis(), mycatId);
            } catch (Throwable t) {
                // 建表可能因异常回退，下次提交重试补建。
                schemaEnsuredTargets.remove(target);
                // 宁可拒绝提交，不可假装持久：抛出 → BaseXaSqlConnection commit 收口为失败并 cancelCommit。
                throw new IllegalStateException(
                        "write XA commit-intent to xa_log failed for target " + target + ", xid=" + xid, t);
            } finally {
                JdbcUtils.close(conn);
            }
        }
    }

    @Override
    public void cancelCommitLog(String xid) {
        ImmutableCoordinatorLog coordinatorLog = get(xid);
        super.cancelCommitLog(xid);
        if (coordinatorLog != null) {
            deleteCommitIntent(xid, targetsOf(coordinatorLog));
        }
    }

    @Override
    public void remove(String xid) {
        // 先取参与者目标（super.remove 可能把该 xid 从内存移除），完成后再清持久意向。
        ImmutableCoordinatorLog coordinatorLog = get(xid);
        super.remove(xid);
        if (coordinatorLog != null) {
            deleteCommitIntent(xid, targetsOf(coordinatorLog));
        }
    }

    private void deleteCommitIntent(String xid, Set<String> targets) {
        for (String target : targets) {
            Connection conn = null;
            try {
                conn = mySQLManagerSupplier.get().getWriteableConnection(target);
                XaLogSchema.deleteCommitIntent(conn, xid);
            } catch (Throwable t) {
                // 意向删除失败不阻断（recovery 端条件删/最终一致收敛），仅记 WARN。
                LOGGER.warn("delete XA commit-intent from xa_log failed for target " + target + ", xid=" + xid, t);
            } finally {
                JdbcUtils.close(conn);
            }
        }
    }

    private void ensureSchemaOnce(String target, Connection conn) throws SQLException {
        if (schemaEnsuredTargets.contains(target)) {
            return;
        }
        XaLogSchema.ensureSchema(conn);
        schemaEnsuredTargets.add(target);
    }

    private static Set<String> targetsOf(ImmutableCoordinatorLog coordinatorLog) {
        return coordinatorLog.getParticipants().stream()
                .map(ImmutableParticipantLog::getTarget)
                .collect(Collectors.toSet());
    }
}
