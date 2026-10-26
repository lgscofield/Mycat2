# I-DEBT-59 实施计划：stream 腿容器行不变式

Spec：`docs/superpowers/specs/2026-10-26-i-debt-59-rx-nullable-scalar-container-row-design.md`
环境：JDK=`D:\Program Files\BellSoft\LibericaJDK-21-Full`；后端 docker `m80-a`(3306)/`m80-b`(3307)；Mycat 8066（fat-jar，persona MYSQL_8_0）。
构建惯例：internalexample 单模块必须带 `-am`（点号僵尸目录债，另票）。

## Task 1 — RED：离线 codegen 锁
新建 `internalexample/src/test/java/io/mycat/drdsrunner/RxNullableScalarProjectionContainerRowTest.java`（复用 `ScalarStdFuncLocalCodegenTest` 的 init/`codegenSource` 骨架，删临时 `ProbeI59CodegenTest`）。
断言（逐条须目视 RED 且失败原因=形态而非编译错）：
1. `select DAYOFWEEK(DATA_TYPE) from information_schema.columns` → 码中 `RxBuiltInMethodImpl.select(...)` 的 lambda 返 `Object[]`、含 `new Object[] {`，且 `getElementType()` 为 `java.lang.Object[].class`。
2. `select IF(1=2,'a',NULL) from information_schema.columns` → 同上（与函数无关，锁不变式本体）。
3. `select distinct DAYOFWEEK(DATA_TYPE) from information_schema.columns` → Observable 段 lambda 返容器行。
4. `union all` 单列（i_s×i_s）→ 声明形态=实际形态（`getElementType()` 为 `Object[].class`，禁嵌套升箱）。
5. 控制组（今日即绿，防过度修复）：双列 distinct 仍走 `Functions.arrayComparer()`；`... limit 3` 仍为 Enumerable 腿（码中不出现 `RxBuiltInMethodImpl.select`）。
运行：`mvn -o -pl internalexample -am test -Dtest=RxNullableScalarProjectionContainerRowTest -Dsurefire.failIfNoSpecifiedTests=false`

## Task 2 — GREEN：两处 physType + helper
1. `StreamMycatEnumerableRelImplementor` 加 `streamRowPhysType(typeFactory, rowType, pref, childFormat)`（Spec §2.1，含 MYCAT CHANGE (I-DEBT-59) 注释，写明"RxJava 禁 null item / SCALAR 塌缩是根 / I-DEBT-19 同法"）。
2. `MycatCalc.implementStream:L297-300` 换用 helper（`implement()` 不动）。
3. `MycatUnion.implementStream` 两分支按**边界分立**处理（初版曾误写"两支均强制 ARRAY"，第三/四轮据实测订正，见 Spec §2.3）：
   - **`toEnumerate` 支**：目标形态 `hasObservableChild ? ARRAY : results.get(0).format`，各子 `result.physType.convertTo(childExp, targetFormat)`
     归一（M-1：混有 Observable 子则强制容器行并归一 Enumerable 标量子；纯 Enumerable 子沿用首子形态，锁 E）；
     非 `all` 的去重 comparer 统一取归一后的 `physType.comparer()`。
   - **`else` 支（两支皆 Observable）**：`streamRowPhysType(..., JavaRowFormat.ARRAY)` 强制容器行。
4. 复跑 Task 1 全绿；`getProblems` 无新增告警。

## Task 3 — 全量回归护栏
- `mvn -o -pl hbt test`
- `mvn -o -pl server -am test`（含 `BinaryBlobByteStringEncodeTest` 5、`ShowCreateDatabaseCharsetTest`）
- `mvn -o -pl common,config,proxy,router test`
- example：`UserCaseTest`(48)、`CrudResultMatrixTest`(12)、`HbtTest`
任何红（含非本次造成的）逐条点名上报，不得略过。

## Task 4 — 活库 e2e 值对拍
1. 重建 fat-jar（`mvn -o -pl server -am package -DskipTests` -P jar-with-dependencies 按仓内惯例），重启 Mycat（`-D`/`--add-opens` 参数逐个加引号）。
2. `target/ProbeI59.java` 7 查询全转 OK；记录行数与去重集。
3. 同 SQL 打真 MySQL 3307 对拍逐值（含 null 保留、distinct 去重基数）；不一致即不宣称完成。

## Task 5 — 门禁与收口
1. 独立 CodeReview subagent（review_scope=current_session），以 Spec §2/§3/§4 为准绳；Important 级以上逐条处置。
2. commit（`-F` UTF-8 消息文件 + `-c i18n.commitEncoding=UTF-8`）→ push → 取 CI run 逐 job/逐步骤认证（零 skipped + 时长画像）。
3. 看板 DEBT-QUEUE.md I-DEBT-59 行：记定级订正（纯投影也中招）、机理闭环（View 腿 toRows 桥接解释分片表不触发）、修法落点与验证四重、升 Evidenced-Done 待 CI；并新登 §2.5 的 `MycatSQLTableLookup` 声明≠实际错配为独立行。
4. 清理临时探针（`ProbeI59CodegenTest` 必删；`target/ProbeI59.java` 属 gitignore 可留）。
