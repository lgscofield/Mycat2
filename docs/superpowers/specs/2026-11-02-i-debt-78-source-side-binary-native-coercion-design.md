# I-DEBT-78 源侧免 BINARY→CHAR coercion：字符串函数原生按字节（A-2·calcite-core fork 专轮·Spec 先行）

状态：✅ **[2026-11-03 收口·架构级 calcite-core fork 专轮·A-2 源侧免 coercion 落地·离线三锁 RED→GREEN + 全量护栏 549/0F/0E/37skip + 8066 活体 5/0/0·系 I-DEBT-77 §5 残留转正]**（原：🔴 立案 2026-11-02·用户拍板走架构级 fork 专轮「A-2 源侧免 coercion」）
分类：C+（引擎/校验/codegen 三腿·触 calcite-core fork）
对齐北极星：M2 无缝 SELECT 值保真 + 「隐式/显式 CAST 精确区分」——釜底抽薪让隐式 coercion CAST **根本不存在**，deparse 无需区分。

## 1. 背景：为何 77 的 Narrow-A deparse 剥离是权宜
I-DEBT-77（已收口 `e143aab8f`）在 `hbt/.../calcite/SqlImplementor.java:855-869` 加「源族=BINARY、目标族=CHARACTER → 丢弃 CAST 下推裸列」剥离分支。A 取证轮实测钉死：隐式 coercion CAST（`mycatCoerceOperands` 注入）与用户显式 `CAST(b AS CHAR)` 在 Rex 层**同形**——算子身份（`makeCast` 硬编 CAST）、parser pos（双双 `SqlParserPos.ZERO`）、目标类型（均 VARCHAR CHARACTER SET "UTF-8"）三类零 fork 标记均不可区分。后果即 77 spec §5 残留：**显式跨片 `cast(binary AS char)` 被连带误剥**。精确修法唯一道路 = 让隐式 CAST 不在源侧产生（用户拍板 A-2）。

## 2. 机制链（当前代码 file:line 坐实·四腿）
1. **注入腿**（calcite-core）：`org/apache/calcite/mycat/MycatSqlDefinedFunction.java`
   - `checkOperandTypes` L153-161：super 族校验失败 → `mycatCoerceOperands` L163-197 → L191 `SqlStdOperatorTable.CAST.createCall(ZERO, operand, convertTypeToSpec(targetType))` 为 CHARACTER 形参的 BINARY 实 operand 包 `CAST(b AS VARCHAR)`（utf8 charset）。
   - 族校验失败点：`sql/type/FamilyOperandTypeChecker.java:135` `!family.getTypeNames().contains(typeName)`（CHARACTER 族不含 VARBINARY/BINARY）→ 免包 CAST 后必须在此维度放行，否则 signature error。
   - 元数据来源：`GetOperandTypes.invoke()` L93-127 → `InferTypes.explicit(argTypes)` + `OperandTypes.operandMetadata(...)`（`OperandMetadataImpl extends FamilyOperandTypeChecker`，公开可子类覆写 `checkSingleOperandType`）。
2. **真注入腿（实现轮探针归因坐实·纠正 77 spec §2.1 旧断言）**：隐式 CAST 实际出自 **TypeCoercion 腿**——`FamilyOperandTypeChecker.checkOperandTypes` L161-168 族失败时调 `TypeCoercion.builtinFunctionCoercion`（`TypeCoercionImpl.java:764-781`，经 `AbstractTypeCoercion.canImplicitTypeCast/implicitCast`）在校验期就把 CAST 包好并令 `super.checkOperandTypes` 直接返回 true——`mycatCoerceOperands` 的 L191 在此形序列上从未轮到执行（铁证：先落 3.1（mycatCoerceOperands skip+宽容）+3.3（deparse 剔离移除）后离线 L1 仍红 `length(CAST(b AS CHAR))`，仅补 3.2 后计划树即刻裸化 `length($1)`）。Mycat 的 TypeCoercion 实现在 `hbt/.../calcite/MycatCalciteSupport.java`（withTypeCoercionFactory 匿名 `TypeCoercionImpl` 子类，已覆写 `implicitCast/coerceOperandType` 等·I-DEBT-31/32）。`mycatCoerceOperands` 同向 skip 仍保留作防御纵深（防 canCastFrom 判 false 时其自家包 CAST）。
3. **deparse 腿**（hbt）：`SqlImplementor.java:855-869` 77 剥离分支——源侧免 coercion 后隐式 CAST 不复存在，该分支**整体移除**，跨族 CAST 一律保留（回到 I-DEBT-76 收口后形态）→ 显式 `cast(b as char)` 保真。
4. **本地求值腿**（executor/HBT·Arrow）：`io/ordinate/engine/builder/ExecuteCompiler.java:100-196`
   - 单描述符路径 L105-118：`length` 现仅 `"length(string)"`（`LengthFactory.java` L48-51 `arg.getString(rec)`→`LengthFunction.length(String)`），BINARY 实参被 `cast(arg, STRING)` 自动转字符串（与旧 plan 内 CAST 同为字符计数语义，非 MySQL 字节数）。
   - 多描述符路径 L120-140 **精确匹配优先**：新增 `"length(bytes)"` 描述符后，BINARY 实参精确命中按字节求值，STRING 实参仍精确命中旧腿。
   - 运行时表征：BINARY 列在 HBT = Arrow VarBinary（`InnerType.BINARY_TYPE`·`BinaryColumn` L73-74）；codegen 视角 calcite `JavaTypeFactoryImpl.java:224-226` BINARY/VARBINARY→`ByteString`。字节/toString 细节实现轮实测坐实（记忆裁定：BINARY/BLOB 运行时表征必须实测）。

## 3. 修法（A-2 三处刀 + 一处 HBT 腿）
### 3.1 calcite-core（fork 刀·作用域仅 Mycat 自定义函数）
`MycatSqlDefinedFunction`：
- **新增同族谓词**（私有静态）：`isBinaryOverCharacter(targetType, operandType)` = 目标∈CHARACTER 族 且 operand∈BINARY 族（`SqlTypeUtil.isBinary` 覆盖 BINARY/VARBINARY，BLOB 映射 VARBINARY 实现轮实测确认）。
- `mycatCoerceOperands` L182-190 判据段：命中谓词 → `continue`（**不包 CAST**，视同已满足）。
- 校验放宽：`GetOperandTypes.invoke()` 的 `operandMetadata` 换用新内部类 `MycatOperandMetadataImpl extends OperandMetadataImpl`，覆写 `checkSingleOperandType(...)`：`family==CHARACTER && SqlTypeUtil.isBinary(derivedType)` → `return true`；其余全权委托 super。放宽仅作用于 Mycat 函数实例，不触 `FamilyOperandTypeChecker` 全局。
### 3.2 hbt · TypeCoercion 防御纵深
`MycatCalciteSupport` 的 TypeCoercion 实现覆写 `implicitCast(in, expected)`：`isBinary(in) && expected==SqlTypeFamily.CHARACTER` → `return null`（该方向宣告"无隐式 cast"，杜绝第二腿回灌）。
### 3.3 hbt · deparse 刀
删除 `SqlImplementor.java:855-869`（I-DEBT-77 剥离分支连同注释），保留 I-DEBT-76 注释块；CAST 分支回到「同族丢弃、跨族保留 getCastSpec」形态。
### 3.4 executor · HBT 按字节腿（本刀不做·实测后裁定降档）
实现轮裁定：shape3 的本地求值腿走 **pushdown 非本地 codegen**（跨片 fan-out 把 length(b) 下推后端逐片求值）；删源侧 CAST 后若 HBT 真吃到裸 binary，`ExecuteCompiler` 单描述符路径 `cast(arg, STRING)` 行为与旧 plan 内 CAST 同构（不崩、不变差）；新描述符会引入多候选 tie-break 风险（numeric 实参可能被绑 bytes 腿）。故首轮不动 HBT，以全量护栏+活体实测坐实无回归；家族式逐函数 bytes 补腿另轮（同 §6 挂账）。

## 4. 锁计划（TDD·先 RED 后 GREEN）
- **L1（既存·转绿不回头）**：`internalexample/.../CrossShardBinaryLengthPushdownFidelityTest`（跨片 `length(b)` fragment 无 `CAST(`）。删除 3.3 剥离分支后**必须仍绿** → 证明源侧天然免 coercion（此前绿是 deparse 兜底假绿）。
- **L2（新·显式 cast 保留锁·当前必红）**：新离线锁（同包姿势）：跨片 `select id, cast(b as char) as c from shgb order by id` fragment **必须含 `CAST(`**（当前被 77 分支连带剥离=77 spec §5 残留钉死为红）。
- **L3（降档为护栏看护·本刀不新增锁）**：HBT/janino 本地求值腿不改（无 plan 内 CAST 后，本地若真吃到裸 binary 由 ExecuteCompiler 自动 cast 腿接管，行为与旧同构）；既有本地 codegen 锁（`ScalarStdFuncLocalCodegenTest` 族 + example 全量）作回归面；若护栏暴露本地新红即补 bytes 腿（§3.4 裁定回照）。
- **L4（回归防护）**：numeric/string 实参 `length` 现行为（`length(123)`=3 等）由既存 testsuite + 全量护栏看护；多描述符 tie-break 漂移即红即修。
- **活体**：重打 fat jar（`mvn -o -pl server -am package -DskipTests`）+ 重启 8066（persona=MARIADB_10_11）→ `BinaryRepresentationFidelityDifferentialTest` 4/0/0 维持；补一发生成对拍：跨片显式 `cast(b as char)` 代理 vs 直连值一致（utf8 非法序列按 MySQL 原生替换符语义，后端逐片求值）。
- **全量护栏**：`mvn -o -pl example -am test`（不带 compat.db）→ 基线 549/0F/0E/37skip 零回归；internalexample testsuite 全跑。

## 5. blast 面与回归面（诚实枚举）
- 所有 Mycat 自定义函数（MycatSqlDefinedFunction 全体子类）对 **BINARY 实参列引用/dynamic param** 的 plan 形态改变：不再包 CAST → ① 跨片 deparse 出裸参（后端 MySQL 原生字节语义——多数函数此形态更贴真库，如 `substring(b,1,2)` 后端按字节截）；② HBT 本地求值落到各函数既有签名自动 cast 腿（行为=原 CAST 后字符串语义，除新增 bytes 腿者）。
- calcite-core fork 面积：1 个类（MycatSqlDefinedFunction）+ 1 个新内部类；不动 FamilyOperandTypeChecker/OperandTypes 全局。
- HBT 函数注册表 `length` 名从单描述符变多描述符 → 匹配路径由 L105-118 变 L120-196（tie-break/顺序敏感），L4 看护。
- 不回退 I-DEBT-76（跨族承重 CAST 保留）与 I-DEBT-77（活体 shape3 值保真）：L1+L2 同跑即为双向锁。

## 6. 残留挂账（不伪绿）
- **逐函数 bytes 腿审计**：首轮仅 `length` 补按字节本地腿；其余 CHARACTER 形参函数（char_length/substring/concat…）的 HBT 本地语义维持自动 cast 现状，家族式逐函数补腿另轮（同 I-DEBT-32-family 姿势）。
- `CHAR_LENGTH(binary)` 多方言字符计数语义（依赖 charset 解码）另评。
- Calcite 内置函数（非 Mycat 注册）的 coercion 腿不在本轮范围。
- BLOB 列族映射（SqlTypeUtil.isBinary 覆盖面）实现轮活体逐列坐实，若有漏型即补锁。

## 7. 账本
- DEBT-QUEUE：新增 I-DEBT-78 行（本 spec 落码收口后转正·系 77 §5 残留转正）；I-DEBT-77 行尾追加「残留已由 I-DEBT-78 收口」交叉引用。
- PROGRESS/progress 按刀记录；commit 不 push。

## 8. 北极星对齐
隐式与显式不再同形，因为**隐式不复存在**：deparse 只可能遇见用户显式 CAST（一律保留=显式保真）与同族冗余 CAST（丢弃=原有行为）。77 的「方向剥离权宜」升级为 78 的「源侧根治 + 显式保留」，值保真与语法保真同时成立。
