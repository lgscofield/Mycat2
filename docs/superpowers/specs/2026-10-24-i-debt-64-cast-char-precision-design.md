# I-DEBT-64 窄修复设计提案（CAST AS CHAR / DECIMAL 精度语义）

> 状态：**✅ 已落地并复验收口（2026-10-24）**。用户授权"按此顺序继续"后，本会话据 §4-B1 自主闭环（本地/可逆/实证自证），非再待评审。落地含本文 A+B1 两处，另补 §6 记录的**关键哨兵修正**（设计初稿未预见）；TDD 离线类型锁 RED→GREEN + 活库逐值差分 PASS + hbt 239/239 零回归。DECIMAL cast 值面未补尾零的正交残面另立 I-DEBT-65。

## 1. 现象（活库铁证，2026-10-24）

Mycat（persona MYSQL_8_0，8066 直连，本地求值路径）：

| SQL | Mycat 实得 | 真 MariaDB:10.11 | 判定 |
|---|---|---|---|
| `cast(10 as char)` | `"1"` | `"10"` | ✗ |
| `cast('hello' as char)` | `"h"` | `"hello"` | ✗ |
| `cast(1234 as char(10))` | `"1"` | `"1234"` | ✗ |
| `cast('abcdefghij' as char(3))` | (推断 `"a"`) | `"abc"` | ✗（截位数错） |
| `cast(10 as decimal(10,2))` | `10` | `10.00` | ✗（scale 丢） |
| `cast(10 as varchar)` | `"10"` | —（MySQL 非法） | 侥幸对 |

## 2. 根因（当前代码 file:line，锁定单处）

`hbt/src/main/java/io/mycat/calcite/MycatCalciteMySqlNodeVisitor.java` `visit(SQLCastExpr)`：

```java
int scale = -1, precision = -1;
List<SQLExpr> arguments = dataType.getArguments();
if (arguments != null && !arguments.isEmpty()) {
    scale     = arguments.get(0)...;   // ← BUG：长度/第一参数应进 precision
    if (arguments.size() > 1) {
        precision = arguments.get(1)...; // ← BUG：小数位才是第二参数
    }
}
... new SqlBasicTypeNameSpec(sqlTypeName, precision, scale, null, pos);
```

druid 对 `CHAR(n)`/`VARCHAR(n)`/`BINARY(n)` 给 `arguments=[n]`（n 是**长度=precision**）；对 `DECIMAL(M,D)` 给 `arguments=[M,D]`（M=precision、D=scale）。现码把 `arguments[0]` 当 scale：

- `CHAR(n)` → scale=n、precision=-1 → 目标 `RelDataType` 落 Calcite CHAR **缺省精度 1** → `checkExpressionPadTruncate` 按 precision=1 把串截成 1 字符。
- `DECIMAL(M,D)` → precision=D、scale=M 全反 → 列头 scale 错（`10` 而非 `10.00`）。

## 3. MySQL 语义（对拍钉死，须遵）

- `CAST(x AS CHAR)` 无长度 = **不限长**（截到 max_allowed_packet 级），不截 1。
- `CAST(x AS CHAR(n))` = **截到 n、但不右填充**（`cast('10' as char(10))="10"` 非 `"10      "`）。
- 而 Calcite `CHAR(n)` 语义是**定长右填充**（pad=true），与 MySQL 相反。MySQL 的 CAST AS CHAR 结果恰是 Calcite **VARCHAR(n)（变长不 pad、截到 n）** 语义。

## 4. 拟修（A 直改 + B 表示决策）

**A. 参数映射纠正**（无歧义、必做）：
```java
precision = arguments.get(0)...;                 // 长度/M
if (arguments.size() > 1) scale = arguments.get(1)...; // D
```
修后 `CHAR(n)` 得 precision=n、`DECIMAL(M,D)` 得 (M,D) 正确。

**B. 字符串 cast 目标按"不填充 + 无长度不限长"表征**（含取舍，待你定）：
- 选项 B1（推荐）：`visit(SQLCastExpr)` 里 MySQL `CHAR`→ 映射为 Calcite `VARCHAR`（长度进 precision），无长度 CHAR → precision 取一个足够大值（如 `RelDataType.PRECISION_NOT_SPECIFIED`→ 令 `checkExpressionPadTruncate` L697 `targetPrecision<0` 直接放行不截不填）。**代价**：结果列对外类型由 CHAR 变 VARCHAR——真 MySQL `CAST AS CHAR` 上报的列类型本也是变长串（`varchar`/`var_string`），故更贴真库、非劣化；但须核 golden（`DruidOutputGolden`/`CalciteValidatorGolden` 可能存 cast CHAR 类型串）与既有断言。
- 选项 B2（保守）：保留 CHAR 类型但在 `checkExpressionPadTruncate` 门控关 pad（改 fork，爆炸半径大，**不推荐**）。

## 5. 验证（若获批实施，按 TDD）

1. **离线 codegen+值锁**：扩展 `ScalarStdFuncLocalCodegenTest`（现仅断 `planAndCodegen!=null`，**不验值**——正是本次漏网根因），加值断言 `cast(10 as char)="10"`、`cast('abcdefghij' as char(3))="abc"`、`cast(10 as decimal(10,2))` scale=2。
2. **活库差分锁**：`ci/` 差分仪对拍真 my57/mariadb 后端，覆盖 char/char(n)/varchar/binary/decimal(M,D) 多形态逐值。
3. 全量 `hbt` + golden（`CalciteValidatorGolden`/`DruidOutputGolden`）零回归；DECIMAL 列头 scale 修复与 §5.3「列头元数据」残面对表（可能顺带收窄该残面）。

## 6. Ruling（本会话）

- 推翻通宵初判"纯架构级跨切不可动"：根因实为 `visit(SQLCastExpr)` 单处参数错配，**修复位置局部**。
- 但 B 段含表示取舍（CHAR→VARCHAR / golden / 列头类型），属正确性关键面 + 外溢客户端元数据 → **先出此设计待你评审**，不擅自 ship。cost if wrong：你若认为应径直按 B1 落地，我即刻执行 TDD；若你有其它口径（如坚持保 CHAR 靠门控关 pad），我按你定改。
