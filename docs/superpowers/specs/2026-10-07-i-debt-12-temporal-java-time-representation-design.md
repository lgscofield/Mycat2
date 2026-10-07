# I-DEBT-12 设计：时间类型规范表示统一为 java.time（方向 A）

- 状态：**已审定（方向 A）+ 已实现（Evidenced-Done，凭实测）**。附带并入 `BaseRowObservable` CLOB-null 守卫（经用户点头跨护栏）；case1 剥出的第三层正交缺陷 `timestamp()/OTHER assign` 另立 I-DEBT-13。
- 归属工包：I-DEBT-12（由 I-DEBT-3 NoSuchMethodError×8 消解后露出的下一层）
- 触发用例：`UserCaseTest#case1`、`PushDownFunctionShardingTableTest#testCastFunction`
- 决策：锁定 **java.time** 为 DATE/TIMESTAMP/TIME 的规范 Java 表示，改 codegen 的类型工厂使其与全栈一致

## 1. 问题（凭当前代码实证）

同一个时间列，三处对"它该是什么 Java 类型"回答不一致：

| 端 | 位置 | 对 DATE / TIMESTAMP / TIME 的假定 |
|---|---|---|
| codegen 投影强转 | `calcite-core` `JavaTypeFactoryImpl.getJavaClass`（187-210 行） | `Integer`(int-days) / `Long`(millis) / `Integer` —— **Calcite 内部数值表示（异类）** |
| 物理行源 | `hbt` `BaseRowObservable.convertSqlValue`（85-91 行） | `LocalDate` / `OffsetDateTime` / `LocalTime` —— **java.time** |
| 结果消费端 | `common` `MycatDataType`（77-82 行）、`AbstractObjectRowIterator.getTimestamp`（121-125 行） | `LocalDate` / `LocalDateTime` / `Duration` —— **java.time** |

janino 生成的裸列投影码按第一行把 `Object[]` 槽位强转 `(Long)`，而槽位实际是 `LocalDateTime` → **ClassCastException**；DATE 方向则走 `SqlFunctions.toInt("2018-01-01")` → **Cannot convert**。二者是同一 seam 的两面。

关键旁证：影子 `RexToLixTranslator` 对**字面量/case-when/null 字面量**的时间处理**早已**覆写成 java.time（DATE→`LocalDate` 2223、TIMESTAMP→`LocalDateTime` 2241），整坨 cast 矩阵 `MycatBuiltInMethodImpl` 也全站在 java.time。**唯一没跟上的是裸列投影的 storage type**，它经 `getJavaClass` 取到 `Long/Integer`。

## 2. 决策与理由

**方向 A（已选）：让 codegen 认 java.time。** 在 `hbt` `MycatRelDataTypeFactory` 构造器内那个匿名 `getJavaClass` 覆写（现仅特判 `OTHER→String`，49-54 行）扩展时间映射：

- `DATE` → `LocalDate`
- `TIMESTAMP` / `TIMESTAMP_WITH_LOCAL_TIME_ZONE` → `LocalDateTime`
- `TIME` / `TIME_WITH_LOCAL_TIME_ZONE` → `Duration`（对齐 `MycatDataType`/结果端；见 §4 TIME 一致性待办）

理由：证据显示 Mycat 全栈（行源、结果端、字面量、cast 矩阵）**一致地把时间当 java.time**，`getJavaClass→Long/Integer` 是 fork 1.42 默认遗留、是唯一异类。A 是"把异类扳回主流"，治根；B（行源归一成 int/long）逆全栈设计、必撞 cast 矩阵与结果端 `(LocalDateTime)` 强转，回归面更大；C（接缝双向适配）只治标。

## 3. 接线前提（已核实）

- `DrdsSqlCompiler:153` 将 `MycatCalciteSupport.TypeFactory`（即 `MycatRelDataTypeFactory` 单例）设为 cluster typeFactory；
- codegen 的 `implementor.getTypeFactory()` → `PhysTypeImpl.getJavaFieldType(i)` → `format.javaFieldClass(typeFactory,…)` → `typeFactory.getJavaClass(...)`。

故改 `MycatRelDataTypeFactory` 的 `getJavaClass` 会**如实传导**到投影 storage type。

## 4. 影响面 / 风险（反查结论 + 实现期须验）

改 `getJavaClass` 是核心类型映射接口，凡调用方都受影响。逐面：

- **裸列投影**（目标修复面）：storage type 由 `Long/Integer` 变 java.time → `(Long)` 强转消失，与行对象一致。✔
- **排序 / 比较**：`LocalDate`/`LocalDateTime`/`Duration` 均 `Comparable`，自然序成立；需确认生成码对时间列的比较走 compareTo 而非数值减。⚠ 实现期验
- **Join key**：`MycatSortMergeJoin:142 keyClass=getJavaClass` → 变 java.time；值本就是 java.time，等值/hash 成立。⚠ 实现期验
- **Window**：`MycatWindow:1005 desiredKeyType` 同步变 java.time。⚠ 实现期验
- **常量折叠**：`RexExecutorImpl:186 storageType=getJavaClass` → 折出的常量须与 java.time storage 兼容。⚠ 实现期验
- **`EnumUtils.toInternal(value, storageType)`**：当 storageType 为 java.time 时须直通不尝试数值转换。⚠ 实现期验（关键）
- **TIME 表示不一致**：影子 `getTypedNullLiteral` TIME→`LocalTime`（2601）vs 字面量值 TIME→`Duration`（2227）vs `MycatDataType` TIME→`Duration`。本工包以 **Duration** 为准，须顺带订正 `getTypedNullLiteral` 的 `LocalTime` 分支或明确其为遗留。⚠ 待办

**范围护栏**：本工包只改 `MycatRelDataTypeFactory.getJavaClass` 的时间分支 +（如需）`getTypedNullLiteral` TIME 一致性；不触碰非时间类型映射，不改行源/结果端。

## 5. 验证计划（凭实测，不信旧记录）

1. **编译门**：`mvn -o -pl hbt -am compile`。
2. **靶测**（Docker m1-mysql-a/b + JDK21 重打 server jar）：
   - `UserCaseTest#case1`（原 CCE）→ 期望转绿；
   - `PushDownFunctionShardingTableTest#testCastFunction`（原 Cannot convert）→ 期望转绿或推进到真实语义断言；
   - `caseSelectSessionValueInt`、`case20220407`（上轮已过）→ 保持绿（防回归）。
3. **影子单测回归门**：`mvn -o test -pl hbt`（43 单测须全绿，含 CollationNullGuard/View 系列）。
4. **时间族扩散面抽查**：涉及 DATE/TIMESTAMP 排序、join、window 的 example 用例做定向差分，确认无新增红。
5. 据 1-4 结果裁定是否需要全量 sweep；如 §4 任一 ⚠ 项实现期证伪（如 toInternal 不容 java.time），**立即停下上报并考虑降级为方向 C**（接缝适配），不硬推。

## 6. 落库

- 实现改动预计集中在 1 文件（`MycatRelDataTypeFactory`）± 1（`RexToLixTranslator.getTypedNullLiteral` TIME 一致性）。
- 打 `MYCAT CHANGE` 注释说明为何扳 getJavaClass（引本 spec）。
- 看板 `2026-09-30-mysql-mariadb-compat-PROGRESS.md` 的 I-DEBT-12 条目随进度回写。

## 7. 决策留痕（供追溯）

- 本设计经用户 `/brainstorming` 会话拍板选方向 A；既有记忆《时间类型架构决策：codegen 适配 java.time》《时间类型内部表示法分歧(I-DEBT-12)》与本 spec 一致。
- 边界：实现属可逆/本地/自证，按授权可自主推进；合并/push 仍待用户。
