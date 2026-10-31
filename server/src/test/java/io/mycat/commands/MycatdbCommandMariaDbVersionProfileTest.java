/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.ServerPersona;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;

/**
 * I-DEBT-70 旁证缺陷的客户端可见后果锁：{@code /*!NNNNNN body *&#47;} 的达标判定必须与
 * 真 MariaDB 一致。
 *
 * <p>活库实测（mariadb 10.6.28 / 10.11.19 / 11.4.13 三版一致，2026-10-31）：
 * <ul>
 *   <li>注释号 &lt; 100000 走 MySQL 兼容轨道，上限 <b>50699</b>（50699 执行，50700 起跳过）；</li>
 *   <li>注释号 ≥ 100000 走 MariaDB 自身轨道，按 MMSSDD 与自述版本比较（10.6.28 → 100628，
 *       100629 跳过）。</li>
 * </ul>
 * 旧实现在此之前还有一层缺陷：MariaDB 自述串不带 {@code "5.5.5-"} 前缀时被切成半截伪版本
 * （10.6.14 → 4.0.0、11.4.2 → 0.0.0、10.11.8 → 8.0.0）。本测试同时锁住解析与分派两层。
 */
public class MycatdbCommandMariaDbVersionProfileTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    @Test
    public void mariaDb106PersonaUnwrapsMysqldumpPrelude() {
        // 旧实现解析出 4.0.0（码 40000 < 40100）→ 前导被当纯注释保留
        usePersona(ServerPersona.MARIADB_10_6);
        assertEquals("SET @@SQL_MODE=''",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!40100 SET @@SQL_MODE='' */").trim(),
                "40100 属 MySQL 兼容轨道且 <= 50699，必须剥标记");
    }

    @Test
    public void mariaDb114PersonaUnwrapsMysqldumpPrelude() {
        // 旧实现解析出 0.0.0（码 0）→ 前导被当纯注释保留
        usePersona(ServerPersona.MARIADB_11_4);
        assertEquals("SET @@SQL_MODE=''",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!40100 SET @@SQL_MODE='' */").trim(),
                "40100 属 MySQL 兼容轨道且 <= 50699，必须剥标记");
    }

    @Test
    public void mariaDb1011PersonaKeepsMysqlCompatCommentAboveCeiling() {
        // 真库实测：10.11.19 对 /*!80000*/ 跳过（50700/80000/80034/80035 全部不执行）
        usePersona(ServerPersona.MARIADB_10_11);
        String text = "/*!80000 SELECT 1 */";
        assertEquals(text, MycatdbCommand.INSTANCE.unwrapVersionComments(text),
                "80000 > 兼容轨道上限 50699 且 < 100000，MariaDB 不执行，必须原样保留");
    }

    @Test
    public void mariaDb1011PersonaKeepsMysqlCompatCommentAtCeilingPlusOne() {
        usePersona(ServerPersona.MARIADB_10_11);
        String text = "/*!50700 SELECT 1 */";
        assertEquals(text, MycatdbCommand.INSTANCE.unwrapVersionComments(text),
                "50700 是兼容轨道上限 50699 的下一号，真库实测跳过");
    }

    @Test
    public void mariaDb1011PersonaUnwrapsMariaDbOwnTrackComment() {
        // 旧实现伪码 80000 < 100000 → 保留；须按 MariaDB 自身轨道 101108 >= 100000 解包
        usePersona(ServerPersona.MARIADB_10_11);
        assertEquals("SELECT 1",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!100000 SELECT 1 */").trim(),
                "100000 进入 MariaDB 自身轨道，10.11.8 的 101108 达标");
    }

    @Test
    public void mariaDb1011PersonaKeepsCommentAboveOwnVersion() {
        usePersona(ServerPersona.MARIADB_10_11);
        String text = "/*!101120 FUTURE_FEATURE_OFF */";
        assertEquals(text, MycatdbCommand.INSTANCE.unwrapVersionComments(text),
                "101120 > 10.11.8 的 101108，须原样保留");
    }

    @Test
    public void mariaDb106PersonaUnwrapsOwnTrackCommentAtItsVersion() {
        // 旧实现伪码 40000 → 保留；修了解析但漏分派也会被 50699 上限挡下
        usePersona(ServerPersona.MARIADB_10_6);
        assertEquals("SELECT 1",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!100614 SELECT 1 */").trim(),
                "10.6.14 的自身码是 100614，恰等于注释号须解包");
    }

    @Test
    public void mariaDb106PersonaKeepsOwnTrackCommentAboveItsVersion() {
        usePersona(ServerPersona.MARIADB_10_6);
        String text = "/*!100615 FUTURE_FEATURE_OFF */";
        assertEquals(text, MycatdbCommand.INSTANCE.unwrapVersionComments(text),
                "100615 比 10.6.14 高一个补丁号，须原样保留");
    }

    @Test
    public void mariaDb106PersonaUnwrapsCompatCommentAtCeiling() {
        usePersona(ServerPersona.MARIADB_10_6);
        assertEquals("SELECT 1",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!50699 SELECT 1 */").trim(),
                "50699 是兼容轨道实测上限，须解包");
    }

    private void usePersona(ServerPersona persona) {
        Map<Class, Object> ctx = new IdentityHashMap<>(savedContext);
        ctx.put(VersionProfile.class, VersionProfileFactory.of(persona, null));
        MetaClusterCurrent.register(ctx);
    }
}
