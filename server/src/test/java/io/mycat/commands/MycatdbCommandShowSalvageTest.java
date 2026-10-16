/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * I-DEBT-53 · MySQL 8.4 复制状态 SHOW 改名命令的透传分类器（回归锁）。
 *
 * <p>背景（官网实证，非臆测）：MySQL 8.4 移除（非仅弃用）两条旧命令并改名：
 * <ul>
 *   <li>{@code SHOW MASTER STATUS → SHOW BINARY LOG STATUS}（§15.7.7.23）</li>
 *   <li>{@code SHOW SLAVE STATUS → SHOW REPLICA STATUS}（§15.7.7.36）</li>
 * </ul>
 * 新语法解析层即被 druid {@code MySqlStatementParser} 拒绝（见
 * {@code ShowBinlogStatusRoutingTest} 的 ParserException 锁），请求冒泡到
 * {@code MycatdbCommand.execute} 的 catch 分支。旧语法（MASTER/SLAVE）能解析、
 * 走 {@code instanceof MySqlShowStatement} 的 L497 透传；改名后的新语法无 AST，
 * 必须靠 Mycat 侧文本分类器识别并 {@code proxySelectToPrototype} 透传原文，
 * 才能对 8.4+/9.0 后端做到无缝替代（现代 mysqldump 只发新语法）。
 *
 * <p>本锁只钉分类器的判据边界：识别改名后的复制状态 SHOW，不误伤旧语法
 * （旧语法另有 AST 路径）、普通 SHOW、非 SHOW 语句与垃圾串。
 */
public class MycatdbCommandShowSalvageTest {

    // ===== 应识别：MySQL 8.4 改名后的复制状态 SHOW =====

    @Test
    public void recognizesShowBinaryLogStatus() {
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW BINARY LOG STATUS"),
                "SHOW BINARY LOG STATUS 是 SHOW MASTER STATUS 在 8.4 的替代，必须透传");
    }

    @Test
    public void recognizesShowReplicaStatus() {
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW REPLICA STATUS"),
                "SHOW REPLICA STATUS 是 SHOW SLAVE STATUS 在 8.4 的替代，必须透传");
    }

    @Test
    public void recognizesForChannelVariant() {
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW BINARY LOG STATUS FOR CHANNEL 'grp1'"),
                "FOR CHANNEL 变体必须一并识别");
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW REPLICA STATUS FOR CHANNEL 'grp1'"),
                "FOR CHANNEL 变体必须一并识别");
    }

    @Test
    public void recognizesCaseAndWhitespaceInsensitive() {
        assertTrue(MycatdbCommand.isReplicationStatusShow("  show binary log status  "),
                "小写/首尾空白鲁棒");
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW   REPLICA   STATUS"),
                "关键字间多空白鲁棒");
        assertTrue(MycatdbCommand.isReplicationStatusShow("SHOW BINARY LOG STATUS;"),
                "结尾分号容忍");
    }

    // ===== 不应识别：旧语法走 AST 路径、非复制状态、垃圾串 =====

    @Test
    public void doesNotMatchLegacySyntaxHandledByAstPath() {
        // SHOW MASTER/SLAVE STATUS 能被 druid 解析，走 L497，不经此 catch 分类器
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW MASTER STATUS"),
                "旧语法另有 AST 透传路径，不属本分类器");
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW SLAVE STATUS"),
                "旧语法另有 AST 透传路径，不属本分类器");
    }

    @Test
    public void doesNotMatchUnrelatedShowOrDml() {
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW BINARY LOGS"),
                "SHOW BINARY LOGS 是列 binlog 文件，非状态命令，且 druid 可解析");
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW TABLES"), "无关 SHOW 拒绝");
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW STATUS"), "无关 SHOW 拒绝");
        assertFalse(MycatdbCommand.isReplicationStatusShow("SELECT 1"), "非 SHOW 拒绝");
        assertFalse(MycatdbCommand.isReplicationStatusShow("SHOW BINARY LOG STATUSXYZ"),
                "后缀垃圾不得误判（须整词匹配）");
        assertFalse(MycatdbCommand.isReplicationStatusShow(null), "null 安全");
    }
}
