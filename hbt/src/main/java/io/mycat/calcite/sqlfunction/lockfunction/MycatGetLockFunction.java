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
package io.mycat.calcite.sqlfunction.lockfunction;

import io.mycat.calcite.MycatBindable;
import org.apache.calcite.adapter.enumerable.RexImpTable;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.mycat.MycatSqlDefinedFunction;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

import java.util.List;

public class MycatGetLockFunction extends MycatSqlDefinedFunction {
    public final static MycatGetLockFunction INSTANCE = new MycatGetLockFunction();

    public MycatGetLockFunction() {
        super("GET_LOCK",
                ReturnTypes.BIGINT,
              null, OperandTypes.VARIADIC, null, SqlFunctionCategory.SYSTEM);
    }

    @Override
    public Expression implement(RexToLixTranslator translator, RexCall call, RexImpTable.NullAs nullAs) {
        // I-DEBT-32-FAMILY·GET_LOCK 接线：字面量经 druid 折成动态参后实参表达式类型为 Object，
        // 旧版用字符串方法名式 Expressions.call(ROOT,"getLock",Object,Object) 在 NewMycatDataContext
        // 上找不到 getLock(Object,Object)（只有 getLock(String,long)）→ "Unable to implement MycatProject"。
        // 改为显式 Types.lookupMethod 定签 + 实参 convert_ 到形参类型（与 MycatReleaseLockFunction 同范式）。
        // 注：timeout 形参取 long 而非 int——Mycat 动态参数值统一装箱为 Long，codegen convert_ 到 int 会触发
        // 运行期 (int)Long 强转 ClassCastException；对齐 long 后 (Long)→long 拆箱合法，且下游 getLock(String,long) 原生接受。
        List<Expression> argValueList = translator.translateList(call.getOperands(), nullAs);
        return Expressions.call(MycatBindable.ROOT,
                Types.lookupMethod(NewMycatDataContext.class, "getLock", String.class, long.class),
                Expressions.convert_(argValueList.get(0), String.class),
                Expressions.convert_(argValueList.get(1), long.class));
    }
}
