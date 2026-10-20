/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.mycat.MycatBuiltInMethod;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * I-DEBT-30 面③ Phase A 扩容（用户选 B/Other，遵循"最小侵入改 fork、注释清楚"原则）：Mycat 宽松
 * （MySQL 语义）CAST 的类型转换分派，经 {@link MycatCalciteRuntime.Hooks#translateCastOverride} 中立
 * seam 回填进 fork 单一版本。
 *
 * <p>
 * 历史：旧整类影子 {@code RexToLixTranslator.translateCast} 内以 {@code switch(targetType)→switch(sourceType)}
 * 展开约 1900 行、逐分支调用 {@link MycatBuiltInMethod}{@code .X_TO_Y.method}（该枚举驻留 fork 的
 * {@code org.apache.calcite.mycat}，故本生成器零 io.mycat 依赖、不违 fork 闸门）。删影子、归一到 fork 1.42 后，
 * fork 原生 {@code translateCast} 的严格 {@code EnumUtils.convert} 使 {@code int→LocalDate} 运行期崩。
 *
 * <p>
 * 本类以查表方式**忠实复刻**该矩阵的分派集合：矩阵的每个分支恰调用一个已存在的 {@code X_TO_Y} 枚举常量，
 * 因此"源/目标 SqlTypeName → 规范 token → {@code SRC_TO_TGT} 枚举名查找"与逐分支硬编码等价——
 * <ul>
 *   <li>命中常量 → 返回 {@code Expressions.call(X_TO_Y.method, operand)}（Mycat 宽松转换，作 fork 的 convert）；</li>
 *   <li>NULL 类型（源或目标）→ {@code constant(null)}（同影子 {@code case NULL}）；</li>
 *   <li>ANY → 原 operand（同影子 {@code case ANY}）；</li>
 *   <li>源/目标含结构/复杂类型，或该 {@code SRC_TO_TGT} 组合影子本就未定义（如同宽 int→int 走 identity）
 *       → 返回 {@code null}，完全交回 fork 原生路径（fork 1.42 的 VARIANT/ROW/ARRAY/GEOMETRY/UUID 等
 *       复杂转换由此兜底，且 pad/truncate/scale/safe 后处理仍在 fork {@code translateCast} 中执行）。</li>
 * </ul>
 */
public final class MycatCastOverride {

  private MycatCastOverride() {
  }

  /**
   * 若该 {@code sourceType→targetType} 属 Mycat 宽松 CAST 分派集合，返回对应 {@code X_TO_Y} 调用表达式；
   * 否则返回 {@code null}（交回 fork 原生）。
   */
  public static Expression translate(RelDataType sourceType, RelDataType targetType,
      Expression operand) {
    SqlTypeName srcName = sourceType.getSqlTypeName();
    SqlTypeName tgtName = targetType.getSqlTypeName();

    // 影子：源或目标为 NULL 类型 → 常量 null。
    if (srcName == SqlTypeName.NULL || tgtName == SqlTypeName.NULL) {
      return Expressions.constant(null);
    }
    // 影子：源或目标为 ANY → 原样透传。
    if (srcName == SqlTypeName.ANY || tgtName == SqlTypeName.ANY) {
      return operand;
    }

    String src = canonical(srcName);
    // MYCAT CHANGE (I-DEBT-60 补全): getJavaClass 现把 TINYINT/SMALLINT/INTEGER 一律表征为 Long
    // ——物理行 BaseRowObservable 对整数组（含 BIGINT）恒解码为 Long，四者 wire-truth 相同。矩阵里
    // 源为整数组的 X_TO_Y 方法（integerToDouble(Integer)/tinyintToX(Byte)/smallintToX(Short)…）形参
    // 未随之更新，Long operand 派发不到 Integer 参数 → codegen 抛 "No applicable constructor/method
    // found for actual parameters \"long\"; candidates ... integerToDouble(Integer)"（I-DEBT-60 把整数组
    // 表征改 Long 后引入的 CAST 回归）。源侧归一到 BIGINT：bigintToY(Long) 收 Long（primitive long 经
    // autobox 亦可），且各 bigintToY 与整数组同名方法体一致（均委托 doubleValue()/toString() 等），数值语义零漂移。
    // 仅归一"源"；目标侧不动——矩阵本就无 *_TO_INTEGER 项，cast 到整型一贯走 fork EnumUtils.convert，行为不变。
    if ("TINYINT".equals(src) || "SMALLINT".equals(src) || "INTEGER".equals(src)) {
      src = "BIGINT";
    }
    String tgt = canonical(tgtName);
    if (src == null || tgt == null) {
      // 复杂/结构类型（ARRAY/MULTISET/ROW/MAP/VARIANT/GEOMETRY/UUID/... ）交回 fork。
      return null;
    }

    MycatBuiltInMethod method = lookup(src + "_TO_" + tgt);
    if (method == null) {
      // 该组合影子本就未定义（如同宽基本类型 identity），交回 fork 原生（严格但等价于原样转换）。
      return null;
    }
    return Expressions.call(method.method, operand);
  }

  /** 把 SqlTypeName 归到 Mycat 矩阵的规范 token；非 Mycat 处理范围返回 null。 */
  private static String canonical(SqlTypeName name) {
    switch (name) {
    case BOOLEAN:
      return "BOOLEAN";
    case TINYINT:
      return "TINYINT";
    case SMALLINT:
      return "SMALLINT";
    case INTEGER:
      return "INTEGER";
    case BIGINT:
      return "BIGINT";
    case DECIMAL:
      return "DECIMAL";
    case FLOAT:
      return "FLOAT";
    case REAL:
      return "REAL";
    case DOUBLE:
      return "DOUBLE";
    case DATE:
      return "DATE";
    case TIME:
    case TIME_WITH_LOCAL_TIME_ZONE:
      return "TIME";
    case TIMESTAMP:
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return "TIMESTAMP";
    case CHAR:
    case VARCHAR:
      return "STRING";
    case BINARY:
    case VARBINARY:
      return "BYTESTRING";
    case INTERVAL_YEAR:
    case INTERVAL_MONTH:
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_WEEK:
    case INTERVAL_QUARTER:
      return "PERIOD";
    case INTERVAL_DAY:
    case INTERVAL_DAY_HOUR:
    case INTERVAL_DAY_MINUTE:
    case INTERVAL_DAY_SECOND:
    case INTERVAL_HOUR:
    case INTERVAL_HOUR_MINUTE:
    case INTERVAL_HOUR_SECOND:
    case INTERVAL_MINUTE:
    case INTERVAL_MINUTE_SECOND:
    case INTERVAL_SECOND:
    case INTERVAL_DAY_MICROSECOND:
    case INTERVAL_HOUR_MICROSECOND:
    case INTERVAL_MINUTE_MICROSECOND:
    case INTERVAL_SECOND_MICROSECOND:
    case INTERVAL_MICROSECOND:
      return "DURATION";
    default:
      return null;
    }
  }

  /** 按枚举名安全查找 {@link MycatBuiltInMethod} 常量，不存在返回 null。 */
  private static MycatBuiltInMethod lookup(String name) {
    try {
      return MycatBuiltInMethod.valueOf(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
