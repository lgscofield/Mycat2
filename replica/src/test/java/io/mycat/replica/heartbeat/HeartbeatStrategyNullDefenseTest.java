package io.mycat.replica.heartbeat;

import io.mycat.replica.InstanceType;
import io.mycat.replica.PhysicsInstance;
import io.mycat.replica.heartbeat.strategy.MGRHeartBeatStrategy;
import io.mycat.replica.heartbeat.strategy.MHAHeartBeatStrategy;
import io.mycat.replica.heartbeat.strategy.MySQLGaleraHeartBeatStrategy;
import io.mycat.replica.heartbeat.strategy.MySQLMasterSlaveBeatStrategy;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MYCAT CHANGE (I-DEBT-142): 心跳策略族对 NULL/异型字段的防御——
 * NULL（未知）不等于 DOWN，不许把活跃实例误判为 DOWN（fail-safe 方向）；
 * 同时不许把真 DOWN 输入改成 UP（对比用例锁住方向）。
 * 修复前：MySQLMasterSlave/MHA 对 Seconds_Behind_Master=NULL 裸 Long.parseLong 抛 NumberFormatException；
 * MGR 对 BEHIND=NULL/异型裸强转抛 NPE/ClassCastException；Galera 对非 String 列裸强转抛 ClassCastException。
 */
public class HeartbeatStrategyNullDefenseTest {

    private static class StubInstance implements PhysicsInstance {
        private final boolean master;
        private final boolean asSelectRead;

        StubInstance(boolean master, boolean asSelectRead) {
            this.master = master;
            this.asSelectRead = asSelectRead;
        }

        @Override
        public String getName() {
            return "stub_ds";
        }

        @Override
        public boolean isMaster() {
            return master;
        }

        @Override
        public boolean isBackup() {
            return false;
        }

        @Override
        public boolean asSelectRead() {
            return asSelectRead;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int getSessionCounter() {
            return 0;
        }

        @Override
        public int getWeight() {
            return 0;
        }

        @Override
        public InstanceType getType() {
            return InstanceType.READ_WRITE;
        }

        @Override
        public void notifyChangeAlive(boolean alive) {
        }

        @Override
        public void notifyChangeSelectRead(boolean readable) {
        }
    }

    /** 只记录、不消费的策略用 HeartbeatFlow：捕获策略上报的 (status, datasourceStatus)。 */
    private static class RecordingHeartbeatFlow extends HeartbeatFlow {
        DatasourceEnum lastStatus;
        DatasourceStatus lastDatasourceStatus;
        int recordCount;

        RecordingHeartbeatFlow(PhysicsInstance instance, double slaveThreshold) {
            super(instance, 1, 0, 0, slaveThreshold, false);
        }

        @Override
        public void setStatus(DatasourceStatus datasourceStatus, DatasourceEnum status) {
            this.lastStatus = status;
            this.lastDatasourceStatus = datasourceStatus;
            this.recordCount++;
        }

        @Override
        public void heartbeat() {
        }

        @Override
        public void sendDataSourceStatus(DatasourceStatus status) {
        }

        @Override
        public void setTaskquitDetector() {
        }
    }

    private static List<List<Map<String, Object>>> singleRow(Map<String, Object> row) {
        return Collections.singletonList(Collections.singletonList(row));
    }

    //////////////////////////////////////////////////////////////
    // 1) MySQLMasterSlaveBeatStrategy：Seconds_Behind_Master=NULL
    //////////////////////////////////////////////////////////////

    @Test
    public void masterSlaveNullSecondsBehindMustNotThrowNorMarkError() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, true), 10);
        MySQLMasterSlaveBeatStrategy strategy = new MySQLMasterSlaveBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Slave_IO_Running", "Yes");
        row.put("Slave_SQL_Running", "Yes");
        row.put("Seconds_Behind_Master", null);
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertNotNull(flow.lastStatus);
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus, "复制线程 Yes + 延迟未知 ≠ DOWN");
        assertFalse(flow.lastDatasourceStatus.isSlaveBehindMaster());
    }

    @Test
    public void masterSlaveNonNumericSecondsBehindMustNotThrow() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, true), 10);
        MySQLMasterSlaveBeatStrategy strategy = new MySQLMasterSlaveBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Slave_IO_Running", "Yes");
        row.put("Slave_SQL_Running", "Yes");
        row.put("Seconds_Behind_Master", "n/a");
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus);
    }

    @Test
    public void masterSlaveGenuineDownStillSynError() {
        // 对比用例：真 DOWN（IO 线程 No）仍报 DB_SYN_ERROR，不许把 DOWN 改成 UP
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, true), 10);
        MySQLMasterSlaveBeatStrategy strategy = new MySQLMasterSlaveBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Slave_IO_Running", "No");
        row.put("Slave_SQL_Running", "No");
        row.put("Seconds_Behind_Master", null);
        strategy.process(singleRow(row), false);
        assertNotNull(flow.lastDatasourceStatus);
        assertFalse(flow.lastDatasourceStatus.isDbSynStatusNormal(), "真 DOWN 必须仍报同步错误");
    }

    //////////////////////////////////////////////////////////////
    // 2) MHAHeartBeatStrategy：同族同病
    //////////////////////////////////////////////////////////////

    @Test
    public void mhaNullSecondsBehindMustNotThrowNorMarkError() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, true), 10);
        MHAHeartBeatStrategy strategy = new MHAHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Slave_IO_Running", "Yes");
        row.put("Slave_SQL_Running", "Yes");
        row.put("Seconds_Behind_Master", null);
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertNotNull(flow.lastStatus);
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus, "复制线程 Yes + 延迟未知 ≠ DOWN");
        assertFalse(flow.lastDatasourceStatus.isSlaveBehindMaster());
    }

    @Test
    public void mhaGenuineDownStillSynError() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, true), 10);
        MHAHeartBeatStrategy strategy = new MHAHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Slave_IO_Running", "No");
        row.put("Slave_SQL_Running", "No");
        row.put("Seconds_Behind_Master", null);
        strategy.process(singleRow(row), false);
        assertNotNull(flow.lastDatasourceStatus);
        assertFalse(flow.lastDatasourceStatus.isDbSynStatusNormal(), "真 DOWN 必须仍报同步错误");
    }

    //////////////////////////////////////////////////////////////
    // 3) MGRHeartBeatStrategy：BEHIND=NULL / 异型
    //////////////////////////////////////////////////////////////

    @Test
    public void mgrNullBehindMustNotThrowNorMarkError() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MGRHeartBeatStrategy strategy = new MGRHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("MEMBER_STATE", "ONLINE");
        row.put("READ_ONLY", "0");
        row.put("BEHIND", null);
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertNotNull(flow.lastStatus);
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus, "ONLINE + GTID 延迟未知 ≠ DOWN");
        assertFalse(flow.lastDatasourceStatus.isSlaveBehindMaster());
    }

    @Test
    public void mgrNonNumberBehindMustNotThrow() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MGRHeartBeatStrategy strategy = new MGRHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("MEMBER_STATE", "ONLINE");
        row.put("READ_ONLY", "0");
        row.put("BEHIND", "not-a-number");
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus);
    }

    @Test
    public void mgrOfflineStillError() {
        // 对比用例：真 DOWN（MEMBER_STATE != ONLINE）仍报 ERROR_STATUS
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MGRHeartBeatStrategy strategy = new MGRHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("MEMBER_STATE", "OFFLINE");
        row.put("READ_ONLY", "0");
        row.put("BEHIND", null);
        strategy.process(singleRow(row), false);
        assertEquals(DatasourceEnum.ERROR_STATUS, flow.lastStatus, "真 DOWN 必须仍报 ERROR");
    }

    @Test
    public void mgrNumericBehindStillComputesDelay() {
        // 对比用例：正常 Number 输入仍然参与延迟判定
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MGRHeartBeatStrategy strategy = new MGRHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("MEMBER_STATE", "ONLINE");
        row.put("READ_ONLY", "0");
        row.put("BEHIND", 100);
        strategy.process(singleRow(row), false);
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus);
        assertTrue(flow.lastDatasourceStatus.isSlaveBehindMaster(), "超阈值延迟必须标记 slaveBehindMaster");
    }

    //////////////////////////////////////////////////////////////
    // 4) MySQLGaleraHeartBeatStrategy：异型列盲转
    //////////////////////////////////////////////////////////////

    @Test
    public void galeraNonStringValueMustNotThrow() {
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MySQLGaleraHeartBeatStrategy strategy = new MySQLGaleraHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Variable_name", "wsrep_cluster_status");
        row.put("Value", 1); // 非 String，修复前 (String) 强转抛 ClassCastException
        assertDoesNotThrow(() -> strategy.process(singleRow(row), false));
        assertNotNull(flow.lastStatus);
    }

    @Test
    public void galeraHealthyClusterStillOk() {
        // 对比用例：健康 Galera 集群仍报 OK + DB_SYN_NORMAL
        RecordingHeartbeatFlow flow = new RecordingHeartbeatFlow(new StubInstance(false, false), 10);
        MySQLGaleraHeartBeatStrategy strategy = new MySQLGaleraHeartBeatStrategy(flow);
        Map<String, Object> row = new HashMap<>();
        row.put("Variable_name", "wsrep_cluster_status");
        row.put("Value", "Primary");
        Map<String, Object> row2 = new HashMap<>();
        row2.put("Variable_name", "wsrep_connected");
        row2.put("Value", "ON");
        Map<String, Object> row3 = new HashMap<>();
        row3.put("Variable_name", "wsrep_ready");
        row3.put("Value", "ON");
        Map<String, Object> row4 = new HashMap<>();
        row4.put("Variable_name", "wsrep_flow_control_paused");
        row4.put("Value", "0.000000");
        List<List<Map<String, Object>>> resultList = Collections.singletonList(
                java.util.Arrays.asList(row, row2, row3, row4));
        strategy.process(resultList, false);
        assertEquals(DatasourceEnum.OK_STATUS, flow.lastStatus);
        assertTrue(flow.lastDatasourceStatus.isDbSynStatusNormal(), "健康集群必须仍报同步正常");
    }
}
