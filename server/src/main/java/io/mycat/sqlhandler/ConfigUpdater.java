/**
 * Copyright (C) <2021>  <chen junwen>
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
package io.mycat.sqlhandler;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.*;
import io.mycat.sqlhandler.config.FileStorageManagerImpl;
import io.mycat.sqlhandler.config.StdStorageManagerImpl;
import io.mycat.sqlhandler.config.StorageManager;
import io.mycat.util.JsonUtil;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfigUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigUpdater.class);

    /**
     * I-DEBT-98 自愈产物时间戳（文件名后缀），如 {@code c0.cluster.json.orphan-20261111T101112345}。
     */
    private static final DateTimeFormatter ORPHAN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneId.systemDefault());

    public static MycatRouterConfigOps getOps() {
        return getOps(Options.builder().persistence(true).build());
    }

    public static MycatRouterConfigOps getOps(Options options) {
        StorageManager metadataManager = MetaClusterCurrent.wrapper(StorageManager.class);
        MycatRouterConfig routerConfig = getRuntimeConfigSnapshot();
        return new MycatRouterConfigOps(routerConfig, metadataManager, options);
    }

    @SneakyThrows
    public static void loadConfigFromFile() {
        // MYCAT CHANGE (I-DEBT-98): 孤儿配置自愈先行于装载。活体证据 E24：集群文件 c0/c1 引用的
        // 数据源文件缺失时，启动即抛 IllegalArgumentException: ignored dw0（ReplicaSelectorRuntime
        // ← MycatRouterConfigOps.commit ← load ← 本方法），整机无法重启，运维事故级。
        // 拍板语义：孤儿引用 WARN+跳过+继续启动（自愈式）；全孤儿集群文件移入 orphan/ 子目录并落盘
        // 记录，不再毒化下次启动；部分孤儿引用仅告警、文件保留。运行态兜底在 ReplicaSelectorRuntime。
        quarantineOrphanClusterConfigs();
        load(getFileConfigSnapshot());
    }

    /**
     * I-DEBT-98：扫描文件层孤儿引用。对每个集群配置核对 masters/replicas 引用的数据源是否在场：
     * 逐条 WARN 孤儿引用；全部引用缺失（整文件已死）的集群文件移入 {@code <home>/orphan/} 并
     * 追加 {@code orphan/orphan-record.jsonl} 落盘记录（含缺失引用清单，运维可追溯）。
     * 非文件后端（无基目录）跳过文件级隔离，仅依赖运行态 WARN+跳过兜底。
     */
    @SneakyThrows
    static void quarantineOrphanClusterConfigs() {
        StorageManager storageManager = MetaClusterCurrent.wrapper(StorageManager.class);
        Optional<Path> baseDirectoryOptional = storageManager.getBaseDirectory();
        if (!baseDirectoryOptional.isPresent()) {
            return;
        }
        Path baseDirectory = baseDirectoryOptional.get();
        MycatRouterConfig fileSnapshot = getFileConfigSnapshot();
        Set<String> datasourceNames = fileSnapshot.getDatasources().stream()
                .map(DatasourceConfig::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (ClusterConfig clusterConfig : fileSnapshot.getClusters()) {
            String clusterName = clusterConfig.getName();
            List<String> references = clusterConfig.allDatasources();
            if (references == null || references.isEmpty()) {
                continue;
            }
            List<String> missingReferences = references.stream()
                    .filter(name -> name != null && !datasourceNames.contains(name))
                    .distinct()
                    .collect(Collectors.toList());
            if (missingReferences.isEmpty()) {
                continue;
            }
            for (String missingReference : missingReferences) {
                LOGGER.warn("I-DEBT-98 ignored orphan datasource reference [{}] on cluster [{}]: datasource config missing, skip and continue startup",
                        missingReference, clusterName);
            }
            if (missingReferences.size() < references.size()) {
                // 部分孤儿：集群仍有可用数据源，文件保留（运行态对缺失引用逐条跳过）
                continue;
            }
            quarantineOrphanClusterFile(baseDirectory, clusterName, missingReferences);
        }
    }

    /**
     * I-DEBT-98 自愈落盘：把全孤儿集群文件移入 {@code <base>/orphan/}（文件名追加
     * {@code .orphan-<时间戳>} 防碰撞），并追加一行 JSON 记录到 {@code <base>/orphan/orphan-record.jsonl}。
     */
    @SneakyThrows
    private static void quarantineOrphanClusterFile(Path baseDirectory,
                                                    String clusterName,
                                                    List<String> missingReferences) {
        Path source = baseDirectory.resolve("clusters").resolve(clusterName + ".cluster.json");
        if (!Files.exists(source)) {
            // KV 文件名与约定不符或已不存在：不擅动，运行态兜底继续生效
            return;
        }
        Path orphanDirectory = baseDirectory.resolve("orphan");
        Files.createDirectories(orphanDirectory);
        String stamp = ORPHAN_STAMP.format(Instant.now());
        Path target = orphanDirectory.resolve(source.getFileName() + ".orphan-" + stamp);
        int sequence = 1;
        while (Files.exists(target)) {
            target = orphanDirectory.resolve(source.getFileName() + ".orphan-" + stamp + "-" + (sequence++));
        }
        Files.move(source, target);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", OffsetDateTime.now().toString());
        record.put("kind", "cluster");
        record.put("cluster", clusterName);
        record.put("missingReferences", missingReferences);
        record.put("source", baseDirectory.relativize(source).toString().replace('\\', '/'));
        record.put("quarantinedTo", baseDirectory.relativize(target).toString().replace('\\', '/'));
        String recordLine = JsonUtil.toJson(record) + System.lineSeparator();
        Files.write(orphanDirectory.resolve("orphan-record.jsonl"),
                recordLine.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        LOGGER.warn("I-DEBT-98 orphan cluster config [{}] quarantined: all datasource references missing {}; moved {} -> {}; startup continues",
                clusterName, missingReferences, baseDirectory.relativize(source), baseDirectory.relativize(target));
    }

    @SneakyThrows
    public static void writeDefaultConfigToFile() {
        MycatRouterConfigOps mycatRouterConfigOps = getOps();
        mycatRouterConfigOps.reset();
        mycatRouterConfigOps.commit();
    }

    @SneakyThrows
    public static void load(MycatRouterConfig mycatRouterConfig) {
        StorageManager storageManager = MetaClusterCurrent.wrapper(StorageManager.class);
        MycatRouterConfig orginal = MetaClusterCurrent.exist(MycatRouterConfig.class) ?
                MetaClusterCurrent.wrapper(MycatRouterConfig.class) : new MycatRouterConfig();
        MycatRouterConfigOps mycatRouterConfigOps = new MycatRouterConfigOps(orginal,
                storageManager,
                Options.builder()
                        .persistence(false).build(), mycatRouterConfig);
        mycatRouterConfigOps.commit();
    }

    public static StorageManager createStorageManager(Path basePath) {
        FileStorageManagerImpl fileStorageManager = new FileStorageManagerImpl(basePath);
        return new StdStorageManagerImpl(fileStorageManager);
    }


    @NotNull
    public static MycatRouterConfig getRuntimeConfigSnapshot() {
        return MetaClusterCurrent.exist(MycatRouterConfig.class)? MetaClusterCurrent.wrapper(MycatRouterConfig.class):new MycatRouterConfig();
    }

    @NotNull
    public static MycatRouterConfig getFileConfigSnapshot() {
        MycatRouterConfig mycatRouterConfig = new MycatRouterConfig();
        StorageManager metadataManager = MetaClusterCurrent.wrapper(StorageManager.class);
        List<LogicSchemaConfig> logicSchemaConfigs = metadataManager.get(LogicSchemaConfig.class).values();
        List<ClusterConfig> clusterConfigs = metadataManager.get(ClusterConfig.class).values();
        List<DatasourceConfig> datasourceConfigs = metadataManager.get(DatasourceConfig.class).values();
        List<UserConfig> userConfigs = metadataManager.get(UserConfig.class).values();
        List<SequenceConfig> sequenceConfigs = metadataManager.get(SequenceConfig.class).values();
        List<SqlCacheConfig> sqlCacheConfigs = metadataManager.get(SqlCacheConfig.class).values();

        mycatRouterConfig.setSchemas(logicSchemaConfigs);
        mycatRouterConfig.setClusters(clusterConfigs);
        mycatRouterConfig.setDatasources(datasourceConfigs);
        mycatRouterConfig.setUsers(userConfigs);
        mycatRouterConfig.setSequences(sequenceConfigs);
        mycatRouterConfig.setSqlCacheConfigs(sqlCacheConfigs);
        return mycatRouterConfig;
    }
}
