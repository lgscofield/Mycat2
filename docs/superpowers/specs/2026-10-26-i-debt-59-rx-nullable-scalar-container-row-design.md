# I-DEBT-59 设计：RxJava 可空标量投影的容器行不变式（修法 A）

- 日期：2026-10-26
- 票：DEBT-QUEUE.md §I-DEBT-59（2026-10-20 已裁修法 A、不插队；本轮=专轮）
- 分类：执行层·架构级（与 I-DEBT-8 Rx 泛型化、I-DEBT-10/L1 seam、I-DEBT-19 容器行先例同族）
- 目标不变式：**凡流经 RxJava `Observable` 的行恒为容器形态（`Object[]`），裸标量只在终结处解箱**——因为 RxJava3 契约禁止 `onNext(null)`，而 Mycat 的执行腿确实会在 `.map` 里产出 null。

## 1. 取证结论（本轮全部为当前代码/实跑证据，非沿用记录）

### 1.1 唯一"造 null"的缝
`OBSERVABLE_SELECT`（=`RxBuiltInMethodImpl.select`，内部 `input.map(...)`）在全仓出码期**只有一处发射点**：
[`MycatCalc.implementStream:L361-364`](../../../hbt/src/main/java/io/mycat/calcite/physical/MycatCalc.java)。
其余 Rx 算子（`filter/topN/sort/limit/offset/unionAll/mergeSort/toEnumerable`）皆为元素透传，不新造 item。
→ 结论：崩溃的**产生点**唯一，修此处即断根。

### 1.2 null 为何会出现在 `.map` 返回值里
`MycatCalc.implementStream:L297-300` 的 `PhysTypeImpl.of(typeFactory, getRowType(), pref.prefer(result.format))`
走 3 参重载 → `optimize=true` → fork 的
[`JavaRowFormat.optimize:L335-347`](../../../calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/JavaRowFormat.java)
**对 `getFieldCount()==1` 无条件把 ARRAY 降为 SCALAR**（与 `Prefer` 无关）。
SCALAR 的 `record()` 直出裸标量 → 投影值为 null 时 `.map` 返回 null → `NullPointerException: The mapper function returned a null value`。

### 1.3 离线 codegen 铁证（`mvn -o -pl internalexample -am test -Dtest=ProbeI59CodegenTest`）
| SQL（i_s 虚表） | 计划腿 | 生成码投影 lambda 返回 | 实跑 |
|---|---|---|---|
| `DAYOFWEEK(DATA_TYPE) ... limit 3` | `MycatMemSort`(isSupportStream=false) → **Enumerable** | `Object current()` 直出 `Long` | OK null×3 |
| `select DAYOFWEEK(DATA_TYPE) ...`（无 limit） | **Observable** | `public Long apply(Object[] row){...}` | **NPE** |
| `select distinct DAYOFWEEK(DATA_TYPE) ...` | Observable+`toEnumerable().distinct()` | `public Long apply(Object[] row)` | **NPE** |
| `select distinct DATA_TYPE, ORDINAL_POSITION ...`（双列） | Observable | `public Object[] apply(Object[] row){ return new Object[]{...}; }` + `.distinct(Functions.arrayComparer())` | OK |
双列行是**既有绿路径**，其形态恰等于修法 A 想要把单列变成的形态。

### 1.4 活库铁证（Mycat 8066，persona MYSQL_8_0，后端 m80-a/m80-b；`target/ProbeI59.java`）
纯投影无 limit、纯 `IF(1=2,'a',NULL)` 投影、两形态 distinct、`count(*) group by DAYOFWEEK(...)` **五类全 NPE**；
`limit 3`（Enumerable 腿）与双列 distinct（容器行）OK。
→ **定级订正**：本债不限于"单列 distinct"，而是**任何走 stream 腿的单列可空投影**（含纯投影、含 group by）。

### 1.5 "分片表不触发"的机理解释（闭合上轮 probe59b 观测）
分片表下推腿是 `MycatView`，其
[`toRows:L684-708`](../../../hbt/src/main/java/io/mycat/calcite/logical/MycatView.java) 的 I-DEBT-10/L1 seam
在 SCALAR 转换前**先把 Observable 桥成 Enumerable**（`TO_ENUMERABLE`），而 Linq4j `Enumerable` 允许 null 元素。
故只要投影腿落在 View 之后即为 Enumerable → 不触雷。i_s 虚表直接 `root.getTableObservable(...)` 传真 Observable 进 `MycatCalc.implementStream` → 触雷。

### 1.6 活库 e2e oracle 订正（本轮实跑发现，影响判定口径）
Mycat 的 `information_schema` 是**虚表元数据**，其表/列集合与真 MySQL 不同（实测同一 `distinct IF(...), DATA_TYPE`
查询 Mycat 19 行 / MySQL 23 行；`count(*) from i_s.columns` Mycat 2021 / MySQL 3581）。
→ **跨引擎逐值 equals 对 i_s 不是有效 oracle**（会把正确实现判成 FAIL）。本债的活库参照改为三级：
1. **同引擎跨执行腿自对拍**（主）：stream 腿（本次改动面）vs 加 `limit` 后回落的 Enumerable 腿逐值比 multiset；
2. **期望值钉死**：与引擎无关的小基数用例（`distinct` 单列必为 `[null]`、`union all limit 5` 必为 5 个 null）；
3. **真 MySQL 参照**：仅在两引擎口径一致处参与判决，其余只记录；另全局扫描 `[Ljava.lang.Object`（容器行被再包一层=静默错值）。
修复前基线（`target/i59-probe-before2.log`）：12 例中 **10 FAIL / 2 PASS**；纯投影、常量 NULL、聚合 group by、
`union all`（含带 limit 形态）全崩。

**修复后实测（`target/i59-probe-after2.log`，透传修复落用后的 jar）**：探针扩到 18 例，**`CASES=18 VERDICT=PASS`**，
其中 `union all … limit 5` 取值为 `[null, null, null, null, null]`（修复前为 `[Ljava.lang.Object;@…`），
全日志扫描 `[Ljava.lang.Object` **0 命中**；`except`/`intersect`/`union all` 非空单列/`order by 1 limit 50` 四条透传例
（§2.4 的活库面）全部 stream-vs-limit 自对拍一致。唯一 SKIP 是活库无分片逻辑 schema（实测仅 `information_schema/mysql/performance_schema`），
非产品缺陷；分片腿回归证据由 §4.2 的 example 套件承担。

## 2. 修法与缝位（决策完备）

### 2.1 不变式 helper（新增，落在 stream implementor 上）
`io.mycat.calcite.StreamMycatEnumerableRelImplementor` 增静态方法：

```java
/** Observable 态行形态不变式（I-DEBT-59 修法 A）：单列不得塌 SCALAR。 */
public static PhysType streamRowPhysType(JavaTypeFactory typeFactory, RelDataType rowType,
        EnumerableRel.Prefer pref, JavaRowFormat childFormat) {
    JavaRowFormat format = pref.prefer(childFormat).optimize(rowType);
    if (format == JavaRowFormat.SCALAR) {
        format = JavaRowFormat.ARRAY;
    }
    return PhysTypeImpl.of(typeFactory, rowType, format, false);
}
```
- 只在 optimize 结果为 SCALAR 时改判 ARRAY，其余格式（LIST/CUSTOM/多列 ARRAY）**原样保留** → 多列/聚合 key 路径零漂移。
- `optimize=false` 显式传入，防止 `PhysTypeImpl.of` 再降一次。
- 与 I-DEBT-19 在 `MycatView.implementMergeSort:L774-788` 的先例同法（那里已论证"Mycat 运行时契约行恒为 Object[]"）。

### 2.2 落点 1：`MycatCalc.implementStream`（null 产生点）
L297-300 由 `PhysTypeImpl.of(..., pref.prefer(result.format))` 改为 `StreamMycatEnumerableRelImplementor.streamRowPhysType(typeFactory, getRowType(), pref, result.format)`。
**`implement()`（Enumerable 态）不动**——Enumerable 容 null，改它属无收益扩面。

### 2.3 落点 2：`MycatUnion.implementStream`（两支边界，第三轮实测订正）
Union 是元素透传者，但 `implementStream` 内部分**两支**，其行形态不变式相反，不得一概而论：

- **`toEnumerate` 支（子腿不全是 Observable）**：本支要把各子拼成一条 `enumerableOrObservableConcat`/
  `UNION`，其输出元素类型必须统一。目标形态取则分两种：
  - **混有 Observable 子（Rx 腿 + Enumerable 腿，典型 i_s 虚表腿 ⊕ 分片 View 腿）**：Rx 腿禁 null、恒发容器行，
    输出仍是 Observable → **目标形态 = `JavaRowFormat.ARRAY`**，对 Enumerable 标量子腿 `result.physType.convertTo(childExp, ARRAY)`
    装箱归一（`Observable` 子已 ARRAY，`convertTo` 见同形态原样返回，绝不会被套 Enumerable `SELECT` 错码，
    见 [`PhysTypeImpl#convertTo:L256-259`](../../../calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java)）。
    **不归一 → 声明≠实发**（CodeReview 抓出的 **M-1**，第四轮实测坐实：`select DATA_TYPE from information_schema.columns
    union all select user_id from db1.travelrecord` 出码根 `getElementType()=Object[]` 而 View 子腿实发
    `public String apply(Object[] row){return row[0].toString();}`，消费端按 `Object[]` 解 `String` → 运行期
    `ClassCastException`；离线 codegen 铁证见 §4 锁 M-1）。
  - **全为 Enumerable 子（无 Rx 腿，典型纯分片 View union）**：无 null 风险 → 沿用首子形态
    `results.get(0).format` 并 `convertTo` 归一其余（同 `implement()` 的透传不变式 §2.4），**不得硬抬 ARRAY**；否则根
    `getElementType()=Object[]` 而元素为标量，`order by` 的 collation lambda 会对元素插 `(Object[])` 强转，
    运行期抛 `ClassCastException: java.lang.Long cannot be cast to [Ljava.lang.Object;`。
    （**第三轮活库/example 铁证**：example `CrossShardUnionHeaderDifferentialTest` 的跨源异构 union，
    堆栈 `Baz$3.apply ← PrepareExecutor:127 result-set execution failed (streaming)`；诊断打印实测子腿
    `result.format=SCALAR javaRowClass=String` 而根 `MycatMemSort result.format=ARRAY childJavaRow=Object[]`。）
  非 `all` 形态的去重 comparer 亦统一取自归一后的 `physType.comparer()`（原逐子 `result.physType.comparer()` 在混态下不一致）。
- **`else` 支（两支皆 Observable，典型 i_s 虚表腿）**：RxJava3 禁 null，`getObservable` 运行时契约恒发
  `Observable<Object[]>`，故保留 `streamRowPhysType(..., JavaRowFormat.ARRAY)` 强制容器行（§2.1）。

> 订正说明：本节初版曾写「两支均改用 §2.1 helper 强制 ARRAY」——该写法在 `else` 支正确、在 `toEnumerate`
> 支制造了新错配（上述 ClassCastException），第三轮据实测拆支修正（改为“沿用首子形态”）；但第三轮“沿用首子
> 形态”仍未对**混有 Observable 子的混合腿**做 `convertTo` 归一，留下 **M-1**（声明 `Object[]`/实发标量）；
> 第四轮按可达性实测坐实后补“混存 Observable 子则目标取 ARRAY + 各子 `convertTo` 归一”（见上）。`implement()`
>（Enumerable 态）不变，见 §2.4。

### 2.4 落点 3（活库门抓出的第二处不变式）：**透传型 rel 的声明必须逐字等于子节点实发形态**
落点 1/2 只修了「生产者」。活库对拍随即抓到反向错值：`select ... union all select ... limit 3` 返回
`[Ljava.lang.Object;@...`。机理（离线 codegen 铁证 + `StreamMycatEnumerableRelImplementor` 代码）：
- `visitChild:L123-131` 只要子节点 `isSupportStream()` 就调 `implementStream`，**所以根即便是 Enumerable
  （limit 回落的 MemSort/TopN），流经元素也已是 stream 腿容器行**；
- 根 `getElementType()` 取自根 physType 声明（`implementHybridRoot:L109-114`）；
- 而**不重录行、直接转发子元素**的透传型 rel 用三参 `PhysTypeImpl.of(..., result.format)`（= optimize=true）
  声明，单列 ARRAY 被塌回 SCALAR → 声明 `Long` / 实发 `Object[]` → 终结端多包一层。

透传型判定（逐个读码得出，不靠猜）：
| rel | 是否重录行 | 处置 |
|---|---|---|
| `MycatUnion.implement`（L91-121） | 否（concat/union 转发） | 改 `passThroughPhysType` + 子行 `convertTo` 归一 |
| `MycatMemSort.implement`（L103-131） | 否（order by 后 skip/take 转发） | 改 `passThroughPhysType` |
| `MycatTopN.implement`（L91-120） | 否（同上） | 改 `passThroughPhysType` |
| `MycatMinus.implement` / `MycatIntersect.implement` | 否（except/intersect 转发） | 改 `passThroughPhysType` + 子行归一 |
| `MycatCalc.implement` / `MycatWindow` / 聚合 / `implementHashJoin` | **是**（`record(...)`/`joinSelector` 自行造行） | 不动（声明即实发） |

helper（同文件新增）：

```java
/** 透传型 rel 的行形态不变式（I-DEBT-59）：声明逐字沿用子节点，禁二次 optimize。 */
public static PhysType passThroughPhysType(JavaTypeFactory typeFactory, RelDataType rowType,
        JavaRowFormat childFormat) {
    return PhysTypeImpl.of(typeFactory, rowType, childFormat, false);
}
```
零漂移性质：子声明 SCALAR（纯 Enumerable 单列腿）时仍声明 SCALAR；ARRAY/LIST/CUSTOM 原样沿用。
多子节点的集合操作以**首个子的形态**为目标并 `PhysType.convertTo` 归一其余子行（同形态时 `convertTo`
原样返回，不产生额外代码，见 [`PhysTypeImpl:L256-259`](../../../calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java)）。

### 2.5 明确不做（范围闸）
- 不动 fork 的 `JavaRowFormat.optimize`（全局影响 Enumerable/下推/CUSTOM 各族，越界）。
- 不动 `MycatHashAggregate/MycatSortAgg`（其 `childExp=toEnumerate(...)`，聚合与退化 distinct 实际跑在 Enumerable 态，null 合法）。
- 不动 `MycatView.implementViewStream`（§1.5 已证其经 `toRows` 桥接，声明与实际自洽）。
- 不引入 null 过滤/`ofType`（会丢行 → 聚合与 distinct 结果错，非仅崩）。
- 不在 `implementStream` 的纯 Observable 分支做 `convertTo` 归一：`convertTo` 生成的是
  `BuiltInMethod.SELECT`（Enumerable 语义），套在 Observable 上是错码；该分支靠 §2.1 强制容器行即可。

### 2.6 顺带锤出、另票跟踪（本轮不修，避免静默扩面）
`MycatSQLTableLookup.implementStream:L188-190` 用 `pref.preferArray()`（optimize=true，单列→SCALAR），
而 `dispatchRightObservable` 实发 `Observable<Object[]>` —— **既存**声明≠实际错配，与本债正交，登记新行独立裁定。

## 3. 正确性论证（修法 A 的两个致命疑点，均已排除）
1. **distinct 会不会退化成引用等值？** 不会。`JavaRowFormat.ARRAY.comparer()` 返 `Linq4j.arrayComparer()`
   （[`JavaRowFormat:L309-311`](../../../calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/JavaRowFormat.java)），
   `MycatHashAggregate:L319-329` 的退化 distinct 分支据此生成内容等值比较器；双列 distinct 实跑（19 行去重正确）即该路径的既有背书。
   单列聚合自身 physType 仍会 optimize 成 SCALAR，`convertTo` 生成 `Enumerable.select(o->o[0])` 后 `.distinct()`——Enumerable 态 null 合法且语义正确（MySQL 视 null 彼此相等）。
2. **终结端能否吃 `Object[]`？** 能。`MycatBindable` 契约本就要求流式 bind 返 `Observable<Object[]>`（L33 javadoc），
   `getElementType()` 变 `Object[].class` 后 `asObjectArray` 自然变 no-op（L43-45），比修复前"标量再升箱"更贴契约。

代价：单列 stream 投影每行多一个 `Object[]` 分配（装箱）。与 I-DEBT-19 已接受的同类代价一致，且正确性优先。

## 4. 验证门（缺一不得宣称完成）
1. **RED→GREEN 离线 codegen 锁**（internalexample `RxNullableScalarProjectionContainerRowTest`，**13 例**，沿用 `ScalarStdFuncLocalCodegenTest` 的 `planAndCodegen/codegenSource` 惯例）：
   - **不变式本体**（四例）：Rx `select` 的 lambda 必须返 `Object[]` 且码中含 `new Object[] {`；
   - **仅 Rx 根**（纯投影 ×2、`union all` 单列）：`getElementType()` 必为 `java.lang.Object[].class`；
     实证订正——以 `MycatHashAggregate` 为根的 distinct 其根仍为标量属正当（聚合先 `toEnumerable`
     桥接再 `.select(o->o[0]).distinct()`，Enumerable 容 null），故该例改钉“解箱发生在桥接之后”而非根形态；
   - **透传锁（§2.4，四例）**：`union all … limit 3`、`union all … order by 1 limit 3`、`except`、`intersect`
     ——先断言码中确实有 `RxBuiltInMethodImpl.select`（证实混态），再断根声明为 `Object[].class`；
     RED 实测这 4 例均声明 `java.lang.Long.class`（即错值根因）；
   - 控制组（三例，防过度修复/零漂移）：双列 distinct 形态不变（仍 `arrayComparer`）、`limit 3` 腿不入 Rx、
     纯 Enumerable 单列 `limit` 腿根声明仍为 `java.lang.String.class`（不得被强改成 Object[]）；
   - **锁 E（§2.3 分片/View 腿边界，第三轮新增）**：`select user_id from db1.travelrecord union all … order by 1`
     的计划含 `MycatUnion`+`MycatView`，断言出码根声明为 `return java.lang.String.class;`（View 腿经 `toRows`
     解箱后的合法标量），**不得硬抬成 `Object[].class`**；RED 实测错码为根 `getElementType()=Object[]` 而
     collation lambda 插 `(Object[]) row` 强转（即 ClassCastException 根因）。
   - **锁 M-1（§2.3 混合腿归一，第四轮新增）**：`select DATA_TYPE from information_schema.columns union all
     select user_id from db1.travelrecord`（i_s 虚表 Observable 腿 ⊕ 分片 View Enumerable 腿），计划含
     `MycatUnion`+`MycatView`+`information_schema`；断根声明 `return java.lang.Object[].class;`（含 Observable 子→容器行），
     并断**每条子腿都实发容器行**（`return new Object[] {` 发射数 ≥ 2，即 View 标量腿已被 `convertTo` 归一）；
     RED 实测该值为 1（View 腿未归一、实发 `String`），GREEN 后为 ≥ 2。
2. **全量回归**：hbt 全量 + server + common + config + proxy + router + example（`UserCaseTest` 48、`CrudResultMatrixTest` 12）。
   - **第三轮实测（`target/i59-reg3.log`，JDK21）**：hbt 243/0、server 84/0、common 43/0、config 23/0、router 18/0、
     proxy 9/0、datasource 7/0、va 12/0、executor、calcite-core 均 **SUCCESS**；example 套件 5 失败类 + 1 compat 类（见下）。
   - **example 红的 pristine 基线定性（本轮新增铁证）**：将 7 个 hbt 改动 `git stash`、从干净 main重建 fat-jar、
     重启代理后单跑这些类（`target/i59-baseline-tests.log`）——`LockTest`(getLock 方法解析失败于
     `MycatGetLockFunction.implement`)、`ManagerHintTest.testErrorSyntax`、`ClientTest.testDBeaver`(变量字符集逗号格式)、
     `PushDownFunctionShardingTableTest.testTimeFunction`(下推 SQL 文本 `AS \`WEEK(?)\`` 占位符未绑)、
     `VectorTest.testBase`(`avg(id)` 5.0 vs 5.000 数值精度) **在基线上同签名同计数地失败**；`MariadbEd25519BackendTest`
     为 3308 ed25519 容器缺失（连接拒绝）。→ **六类均与 I-DEBT-59 正交**（签名全在函数实现/SQL 文本/数值格式/连接，
     不可能由 `JavaRowFormat` 声明改变引发）；本轮引入的 union 族 ClassCastException 已由 `CrossShardUnionHeaderDifferentialTest` 等 18 例转绿。
     基线代理启动未带 `--add-opens java.base/java.nio`，导致 `VectorTest` 额外报 `InaccessibleObjectException` 挂起（启动工件，非代码）。
3. **活库 e2e 值对拍**（oracle 见 §1.6）：重建 fat-jar 重启 Mycat，`ProbeI59` 逐例 PASS（自对拍 + 期望值钉死 +
   null 保留行数==`count(*)` + 无 `[Ljava.lang.Object` 嵌套），并保留真 MySQL 3307 参照行。
4. **独立 CodeReview subagent 门禁**（架构级改动，Spec 与本节为准绳）。
5. **commit/push + CI 逐步骤认证**（防伪绿：全 job success + 逐步骤零 skipped + 时长画像）后升 Evidenced-Done。

## 5. 风险与回退
- 风险面：“stream 腿单列不得塌 SCALAR”（生产者）+“透传型 rel 声明逐字等于子节点”（消费者）两条不变式；
  两者都与既有双列绿路径同形，错形最可能表现为 janino 编译失败或列值形态异常，均会被 §4.1 锁与 §4.2/§4.3 立刻抓住（非静默）。
  实际发生过的正是这一类：§4.3 活库门抓出消费侧错值（崩→嵌套错值），已入 §2.4 与锁。
- 回退：`StreamMycatEnumerableRelImplementor` 撤两个 helper；`MycatCalc`/`MycatUnion`/`MycatMemSort`/
  `MycatTopN`/`MycatMinus`/`MycatIntersect` 六文件的 physType 调用改回原三参 `PhysTypeImpl.of`（Union/Minus/Intersect
  同时去掉 `convertTo` 归一行）；无数据/协议面残留。回归锁与探针可单独保留作探针记录。

### 5.1 已排除的两个误判方向（本轮靠实测推翻，留痕防后人重踏）
1. **“分片 View 腿实发容器行”——错。** 加临时诊断打印实测：`MycatUnion implementStream` 下分片腿 `result.format=SCALAR
   javaRowClass=String`（View 腿经 `toRows` 桥成 Enumerable 后已解箱为合法标量），非容器行。若据此把 `toEnumerate`
   支硬抬 ARRAY，只会造成 §2.3 的 ClassCastException。教训：不在推理里绕，加诊断打印/离线 codegen 实测钉死。
2. **“锁 E 断言应禁 `public String apply(Object[] row)`”——方向错。** 首版锁 E 断言码中不含该 lambda；实测发现该
   lambda 是 `MycatView.toRows` 自身的合法产物（与 Union 根声明无关）。据实改写为断言根声明 `return java.lang.String.class;`（
   即“根跟子形态”）。真正错的是根 `getElementType()`，不是子 lambda。

### 5.2 环境注记（非产品缺陷，但影响构建判决可信度）
- **JDK 铁律**：本仓唯一许可 `D:\Program Files\BellSoft\LibericaJDK-21-Full`。`mvn` 前必须设 `$env:JAVA_HOME` 指向该路径；
否则误用 JDK25（LibericaJDK-25-Full）会使 **Lombok 注解处理器失效**，`common` 等模块满屏“找不到符号”（getters 未生成）。
  本轮曾因此一度污染构建，用 JDK21 复验后 `common` 秒绿（并验 jar 内 `MycatField.getName()` 存在，活库门 PASS 未受污染）。
- **jar 原地覆盖假象**：example 兼容腿曾报 `NoClassDefFoundError: CompositeOperandTypeChecker$1`/`NetClientBuilder`，但类实测
  均在 jar 内——根因为 JVM 存活期间原地覆盖 fat-jar、后续按需类加载失败。**重启服务端前必先停进程再覆盖 jar**。
- **example compat 族红的定性**：3308 MariaDB ed25519 容器缺失（环境缺口，非产品）+ 上述 jar 覆盖假象；与本轮 union 族真回归（已修）无关。

### 5.3 CodeReview 反馈逐条处置（第四轮，按"核实而非盲从"）
评审（无 Blocker）抛 **M-1（Major）** + 若干 Minor。逐条独立实测后的处置：
- **M-1（toEnumerate 支未对各子 `convertTo` 归一）——采纳并已修**。靠离线 codegen dump 坐实可达且声明≠实发：
  `i_s(Observable) ⊕ 分片 View(Enumerable 标量)` 的 union 根声明 `Object[]` 而 View 子腿实发 `String`。修法：混有
  Observable 子时目标取 ARRAY + 各子 `convertTo` 归一（`implement()` 同法）；新锁 M-1（§4.1）RED→GREEN。
- **m-1（锁③ distinct 的 `indexOf` 大小比较可假过）——采纳**。当 `toEnumerable(` 缺失返 -1 而 `.distinct()`
  在位时 `-1 < pos` 会空过。已补前置断两标记均在位再比序。
- **m-2/m-3（建议加反向断言护栏 / 直测 MemSort passThrough）——不改**。现有锁已由“根声明 == 子腿
  实发"的正方向钉住不变式；MemSort 透传由锁 A/B（union-under-limit/orderby）间接覆盖且已绿。额外断言不
  新增可测行为，属无收益扩测试面（接受但不强推）。
- **m-4（Plan §回写与设计同步）——采纳**。`2026-10-26-...-row.md` Task 2 step 3 已从初版"两支均强制 ARRAY"
  改写为两支边界分立 + M-1 归一。
- **m-5（MycatCalc 建议把 `streamRowPhysType` 计算移进 else 支）——拒绝**。L302 的 physType 在 L310 非
  Observable 回退分支会被 `return implement()` 弃用（implement() 自算 physType），仅 else 支使用；提前
  计算只是浪费、无正确性隐患。移动属纯 cosmetic、零行为收益，不改以控 blast 面。
- **n-1（MycatUnion 删了一个空行）——无实质影响**。属样式微差，已随 M-1 重写自然消失，不单独处理。
- **评审声称改过产品码（MycatView/MycatBindable）——经 `git diff --stat` 证伪**：本任务产品面仅 7 个 hbt 文件，
  MycatView/MycatBindable 未在列（上一段续接摘要失真）。未引入任何未审计的评审改动。
