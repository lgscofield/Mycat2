/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-54 D2b · MySQL 版本注释语句 {@code /*!NNNNNN body *&#47;} 的解包回归锁。
 *
 * <p>背景（活体实证，非臆测）：mysqldump 预处理首条语句即
 * {@code /*!40100 SET @@SQL_MODE='' *&#47;}（服务端日志 p60-srv-out.log 原文可查），
 * 真 MySQL 服务端 lexer 会把版本达标(≤自述版本)的 {@code /*!} 注释剥掉标记、按普通语句执行；
 * 而 druid 把整条当纯注释解出**空语句表**，MycatdbCommand 走 I-DEBT-57 的空表→Query OK 分支
 * 静默吞掉 → 会话 sql_mode 未切宽松 → 后续 i_s.FILES tablespaces 形态被本地严格校验拒绝
 * （活体 mysqldump stderr: CalciteContextException: Expression 'EXTRA' is not being grouped）。
 *
 * <p>本锁钉：①mysqldump 形态解包并可 parse 成 SET AST；②版本未达标保留原文；
 * ③MariaDB 方言 {@code /*M!} 与本债正交、不触碰；④纯注释空表行为不变（I-DEBT-57 现状）。
 */
public class MycatdbCommandVersionCommentTest {

    // ===== unwrapVersionComments =====

    @Test
    public void unwrapsMysqldumpSqlModeStatement() {
        String out = MycatdbCommand.INSTANCE.unwrapVersionComments("/*!40100 SET @@SQL_MODE='' */");
        assertEquals("SET @@SQL_MODE=''", out.trim(),
                "mysqldump 预处理首语句的版本注释标记必须剥除，保留可执行体");
    }

    @Test
    public void unwrapsTimeZoneAndMidStatementComments() {
        assertEquals("SET TIME_ZONE='+00:00'",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!40103 SET TIME_ZONE='+00:00' */").trim());
        assertEquals("SELECT 1",
                MycatdbCommand.INSTANCE.unwrapVersionComments("/*!50000 SELECT 1 */").trim());
    }

    @Test
    public void keepsCommentWhenVersionNotMet() {
        String text = "/*!999999 FUTURE_FEATURE_OFF */";
        assertEquals(text, MycatdbCommand.INSTANCE.unwrapVersionComments(text),
                "自述版本未达标的版本注释须保持注释原样（真 MySQL 不执行之）");
    }

    @Test
    public void doesNotTouchMariaDbDialectOrPlainComments() {
        String mariadb = "/*M!100100 SET @@MAX_STATEMENT_TIME=0 */";
        assertEquals(mariadb, MycatdbCommand.INSTANCE.unwrapVersionComments(mariadb),
                "MariaDB 方言 /*M! 形态归 I-DEBT-57 现状路径，本解包不扩面");
        String plain = "/* hello */";
        assertEquals(plain, MycatdbCommand.INSTANCE.unwrapVersionComments(plain), "纯注释不变");
    }

    // ===== parse 接线 =====

    @Test
    public void parsesVersionCommentedSetIntoSetStatementAst() {
        MycatdbCommand cmd = MycatdbCommand.INSTANCE;
        var statements = cmd.parse("/*!40100 SET @@SQL_MODE='' */");
        assertEquals(1, statements.size(), "解包后须产出恰一条语句（进入 SET 处理器而非空表吞掉）");
        assertInstanceOf(SQLSetStatement.class, statements.get(0));
    }

    @Test
    public void plainCommentStillParsesToEmptyList() {
        assertTrue(MycatdbCommand.INSTANCE.parse("/* just a comment */").isEmpty(),
                "I-DEBT-57 空语句表→Query OK 现状不受影响");
    }
}
