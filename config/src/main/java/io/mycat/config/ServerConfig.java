/**
 * Copyright (C) <2021>  <gaozhiwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */

package io.mycat.config;

import io.mycat.util.JsonUtil;
import io.mycat.util.MySQLDatabaseMetadata;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Data
@EqualsAndHashCode
public class ServerConfig {
    private int mycatId = 1;
    private String ip = "localhost";
    private int port = 8066;
    private int reactorNumber = Runtime.getRuntime().availableProcessors();
    private boolean proxy = true;
    private ThreadPoolExecutorConfig workerPool = ThreadPoolExecutorConfig
            .builder()
            .corePoolSize(Runtime.getRuntime().availableProcessors())
            .maxPoolSize(1024)
            .keepAliveTime(1)
            .timeUnit(TimeUnit.MINUTES.name())
            .maxPendingLimit(65535)
            .taskTimeout(1)
            .build();
    private ThreadPoolExecutorConfig timeWorkerPool = ThreadPoolExecutorConfig
            .builder()
            .corePoolSize(0)
            .maxPoolSize(2)
            .keepAliveTime(1)
            .timeUnit(TimeUnit.MINUTES.name())
            .maxPendingLimit(65535)
            .taskTimeout(1)
            .build();
    private BufferPoolConfig bufferPool = new BufferPoolConfig();
    private TimerConfig idleTimer = new TimerConfig(3, 15, TimeUnit.SECONDS.name());
    private String tempDirectory;
    private String pathCacheDirectory;
    private int mergeUnionSize = 5;
    private boolean joinClustering = true;
    private String serverVersion = "5.7.33-mycat-2.0";
    // I-VE-1: 版本画像 persona，缺省 MYSQL_5_7 保证零回归；serverVersion 作为可选覆盖串（非空则优先）
    private ServerPersona persona = ServerPersona.MYSQL_5_7;
    // R4 前端 TLS：缺省 DISABLED 零回归（不广告 CLIENT_SSL）。tlsCertFile/tlsKeyFile 为 PEM 证书/私钥，
    // 为空时运行期回落自签证书（仅测试/dev）。
    private TlsMode tlsMode = TlsMode.DISABLED;
    private String tlsCertFile;
    private String tlsKeyFile;
    private boolean ignoreCast = false;
    private int fullTableScanLimit = 1024;
    private boolean fullTableScanException = false;
    //BROADCAST
    private boolean forcedPushDownBroadcast = false;
    private boolean bkaJoin = true;
    private boolean sortMergeJoin = true;
    private boolean partitionKeyJoin = true;
    private long bkaJoinLeftRowCountLimit = 1000;
    private boolean pstmtStringVal = false;
    private String pushDownSelectDual = "hackRouter";


    private boolean pushShowOnPrototype = false;
    private boolean useProxyProtocol = false;
    private boolean asyncDDL = false;
    private int rewriteInsertBatchedStatementBatch = 1000;
    private boolean promoteUnsignedType = true;
    private boolean stickySession = true;
    private long stickySessionTime = -1;
    private int sendBufferSize = -1;
    private int receiveBufferSize = -1;
    // I-DEBT-84 事务腿 Phase 2 阶段 1：独立事务 reaper。周期扫 past-prepare 的 in-doubt XA 事务并收敛
    // （已决提交者据 xa_log 意向 COMMIT、未决者过 grace 才 ROLLBACK）。<=0 禁用（零常驻后台线程、零回归）。
    private long xaReaperIntervalMillis = 30_000L;
    // grace 窗口：未决 prepared 分支须滞留超此时长才被判为"协调器已失联"进而回滚，避免误杀正在跑的 2PC。
    // 默认 2× 语句超时量级（30s）。
    private long xaReaperGraceMillis = 60_000L;
    public static void main(String[] args) {
        System.out.println(JsonUtil.toJson(new ServerConfig()));
    }

    @SneakyThrows
    public String getTempDirectory() {
        pathCacheDirectory = Files.createTempDirectory("").toAbsolutePath().toString();
        String mycat_temp_directory = "mycat_temp_directory";
        if (tempDirectory == null) {
            try {
                Path target = Optional.ofNullable(ServerConfig.class.getClassLoader())
                        .map(i -> i.getResource(""))
                        .map(i -> {
                            try {
                                return i.toURI();
                            } catch (URISyntaxException e) {
                                return null;
                            }
                        })
                        .map(i -> i.resolve("target"))
                        .map(Paths::get)
                        .orElse(null);
                if (target != null && Files.exists(target)) {
                    tempDirectory = target.toString();
                } else {
                    tempDirectory = Files.createTempDirectory(mycat_temp_directory).toAbsolutePath().toString();
                }
            } catch (Throwable e) {
                try {
                    tempDirectory = Files.createTempDirectory(mycat_temp_directory).toAbsolutePath().toString();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            System.out.println("tempDirectory:" + tempDirectory);
        }
        return tempDirectory;
    }

    public boolean computeClientDeprecateEof() {
        return MySQLDatabaseMetadata.parse(getServerVersion()).isClientDeprecateEof();
    }
}
