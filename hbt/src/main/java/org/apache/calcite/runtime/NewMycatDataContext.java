package org.apache.calcite.runtime;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.DrdsSqlWithParams;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatDataContext;
import io.mycat.newquery.NewMycatConnection;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.Future;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.util.SqlString;

import java.util.List;
import java.util.Map;

public interface NewMycatDataContext extends DataContext {

    Observable<Object[]> getTableObservable(String schmea, String table);

    public Future<NewMycatConnection> getConnection(String key);

    public void recycleConnection(String key, Future<NewMycatConnection> connectionFuture);

    public List<Observable<Object[]>> getObservableList(String node);

    Observable<Object[]> getObservable(String node,
                                       org.apache.calcite.linq4j.function.Function1 function1,
                                       java.util.Comparator comparator, int offset, int fetch);


    Observable<Object[]> getObservable(String node);

    public Object getSessionVariable(String name);

    public Object getGlobalVariable(String name);

    public String getDatabase();

    public Long getLastInsertId();

    public Long getRowCount();

    public Long getConnectionId();

    public Object getUserVariable(String name);

    public String getCurrentUser();

    public String getUser();

    public DrdsSqlWithParams getDrdsSql();

    public MycatDataContext getContext();

    public Integer getLock(String name,long timeout);

    public Integer releaseLock(String name);

    public Integer isFreeLock(String name);

    /**
     * MYCAT CHANGE (I-DEBT-97/F5)：本地 codegen 求值诊断槽——{@code ThreadLocal<MycatDataContext>}。
     *
     * <p>背景：本地 codegen 除法经 {@code MycatCalciteRuntimeHooks.remapOperator} 出码为对
     * {@code MysqlDivideFunction.mysqlDivide(a,b)} 静态纯函数的无上下文调用（值语义锁
     * {@code MysqlDivideFunctionTest}），函数体拿不到会话诊断缓冲，除零只能静默返 NULL，
     * {@code @@warning_count} 不涨（真库另有一条 SQLWARNING 1365 {@code Division by 0}）。
     * 本票选方案B：执行器在 Bindable 求值边界把当前会话的 {@link MycatDataContext} 装进本槽，
     * 纯函数除零分支读槽写入告警（挂摘点取证与选型见 {@code PrepareExecutor} 的 I-DEBT-97 注释）。
     *
     * <p>线程串会话防线（本票最大风险点）：槽只在"正执行该会话求值的线程"上有值，挂/摘必须
     * 走 {@link #installDiagnosticContext}/{@link #restoreDiagnosticContext} 成对置于
     * <b>严格 try/finally</b>——求值体任何出口（正常/异常/错误回调注册后）都要摘回；
     * {@code previous==null} 时调 {@link ThreadLocal#remove()} 而非 {@code set(null)}，
     * 保证线程池 worker 复用时不残留上一个会话的上下文引用（既防串会话也防会话对象延迟释放）。
     * 嵌套求值（求值体内再触发求值）由 install 的返回值（previous）+ restore 成对恢复，不互相踩。
     *
     * <p>无槽执行路径（远程/异步消费线程、内部驱动、单测直调）读到 {@code null} 即降级静默，
     * 不得抛——与既有远程腿（I-DEBT-69 后端告警中继）互补而非替代。
     */
    ThreadLocal<MycatDataContext> DIAGNOSTIC_CONTEXT_SLOT = ThreadLocal.withInitial(() -> null);

    /** 读当前线程诊断槽；无槽（含线程池复用未挂载）返回 {@code null}，调用方降级静默。 */
    static MycatDataContext currentDiagnosticContext() {
        return DIAGNOSTIC_CONTEXT_SLOT.get();
    }

    /**
     * 挂载：把 {@code context} 装入本线程诊断槽，返回先前的槽值（供 restore 成对恢复）。
     * 必须与 {@link #restoreDiagnosticContext} 置于同一 try/finally 内。
     */
    static MycatDataContext installDiagnosticContext(MycatDataContext context) {
        MycatDataContext previous = DIAGNOSTIC_CONTEXT_SLOT.get();
        DIAGNOSTIC_CONTEXT_SLOT.set(context);
        return previous;
    }

    /**
     * 摘回：恢复到 install 前的槽值。{@code previous==null} 时 {@link ThreadLocal#remove()}——
     * 不留 {@code null} 占位 Entry，防线程池 worker 残留会话引用（串会话/延迟释放双防）。
     */
    static void restoreDiagnosticContext(MycatDataContext previous) {
        if (previous == null) {
            DIAGNOSTIC_CONTEXT_SLOT.remove();
        } else {
            DIAGNOSTIC_CONTEXT_SLOT.set(previous);
        }
    }
}
