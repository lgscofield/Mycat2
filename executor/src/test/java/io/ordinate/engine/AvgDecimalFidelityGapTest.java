/*
 * Copyright (C) <2026> Mycat2 contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package io.ordinate.engine;

import io.ordinate.engine.function.aggregate.avg.AvgAggregateFunction;
import io.ordinate.engine.schema.InnerType;
import org.junit.Assert;
import org.junit.Test;

/**
 * knownGap 锚锁（I-DEBT-67）：ordinate 向量化执行器（{@code mycat:setVector} hint 态）的 AVG 聚合是
 * Double 双轨——{@link AvgAggregateFunction} implements DoubleAccumulator，cairo MapValue 累加上下文
 * 只有 LONG(count)+DOUBLE(sum) 两槽，getOutputType()=DOUBLE_TYPE；故 {@code SELECT avg(id)}（id BIGINT）
 * 经该引擎出 "5.0"，而后端真库 MySQL/MariaDB 对 AVG(bigint) 返 DECIMAL scale4（div_precision_increment=4）= "5.0000"。
 *
 * <p>本锁按现状钉死缺口表征（先例：hbt {@code TypeRepresentationThreePathConsistencyTest} 的 knownGap 模式），
 * 使 {@code VectorTest:74} 保留的 {@code 5.0000} <b>目标红锁</b>是显式挂账、而非对现状 {@code 5.0} 的掩盖或迁就。
 * <b>I-DEBT-67 专轮（executor 聚合链 DECIMAL 表征
 * 改造）落地后须删除/翻转本锁</b>：届时断言应改为 DECIMAL 保真输出，5.0 形态即成回归信号。
 *
 * <p>缺口定位证据：{@code AvgAggregateFunction.java} implements DoubleAccumulator、getDouble 直除 sum/count、
 * getOutputType/getInputType/getType 恒 DOUBLE_TYPE；聚合函数族目录仅 Double/Long 两轨，无 BigDecimal/DECIMAL 槽 infra。
 */
public class AvgDecimalFidelityGapTest {

    @Test
    public void avgAccumulatorIsDoubleTrackKnownGapLockedForIDebt67() {
        AvgAggregateFunction avg = new AvgAggregateFunction(0);
        // 现状钉死：命名与输入/输出类型全为 double 轨（真 MySQL 口径应为 DECIMAL scale4，见类注释）
        Assert.assertEquals("avg(double)", avg.name());
        Assert.assertEquals(InnerType.DOUBLE_TYPE, avg.getOutputType());
        Assert.assertEquals(InnerType.DOUBLE_TYPE, avg.getInputType());
        Assert.assertEquals(InnerType.DOUBLE_TYPE, avg.getType());
    }
}
