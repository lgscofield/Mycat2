/*
 * Copyright (C) <2026> Mycat2 contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package io.ordinate.engine;

import io.ordinate.engine.builder.ExecuteCompiler;
import io.ordinate.engine.function.Function;
import io.ordinate.engine.record.VectorBatchRecord;
import io.ordinate.engine.schema.InnerType;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;

/**
 * I-DEBT-67 第一刀①（向量腿 DECIMAL 列读取器）行为锁：
 * ordinate 向量化执行器（{@code mycat:setVector} hint 态）此前无法读取任何 DECIMAL 源列——
 * {@link ExecuteCompiler#column(int, Schema)} 对 {@code InnerType.DECIMAL_TYPE} 落 {@code break} 后直坠
 * {@code throw new UnsupportedOperationException()}（无十进制列读取器），且运行态
 * {@link VectorBatchRecord#getObject(int)} 对十进制向量恒返回 {@code null}（读不回值）。
 *
 * <p>本锁钉死这条竖切的<b>真实可观测行为</b>（非仅"不抛"）：给定一个含 {@code 1.2345} 的
 * {@code DECIMAL(19,4)} 批，向量腿应把该列读回精确的 {@link BigDecimal} {@code 1.2345}。
 * 落地前：{@code column()} 抛 UnsupportedOperation → RED；落地后：返回十进制列读取器且值保真 → GREEN。
 *
 * <p>范围严格限于"读取一个已存在的 DECIMAL 列"，不含除法 / CAST-to-DECIMAL（那些是后续刀）。
 */
public class VectorLegDecimalColumnReaderTest {

    @Test
    public void columnReaderShouldReturnExactDecimalFromVectorBatch() {
        ArrowType decimal = new ArrowType.Decimal(19, 4, 128);
        Field field = new Field("x", FieldType.nullable(decimal), null);
        Schema schema = new Schema(Collections.singletonList(field));

        // 计划装配缝：DECIMAL 列应能被 column() 读取（落地前此处抛 UnsupportedOperationException）
        Function col = ExecuteCompiler.column(0, schema);
        Assert.assertNotNull("向量腿应能构造 DECIMAL 列读取器", col);
        Assert.assertEquals(InnerType.DECIMAL_TYPE, col.getType());

        // 运行态读值缝：DecimalVector 批 → VectorBatchRecord → 读回精确 BigDecimal
        try (BufferAllocator alloc = new RootAllocator()) {
            DecimalVector vector = new DecimalVector("x", alloc, 19, 4);
            vector.setSafe(0, new BigDecimal("1.2345"));
            VectorSchemaRoot root = VectorSchemaRoot.of(vector);
            root.setRowCount(1);

            VectorBatchRecord rec = new VectorBatchRecord(root);
            rec.setPosition(0);

            Object value = col.getAsObject(rec);
            Assert.assertEquals("DECIMAL 列应逐值保真读回", new BigDecimal("1.2345"), value);

            root.close();
        }
    }
}
