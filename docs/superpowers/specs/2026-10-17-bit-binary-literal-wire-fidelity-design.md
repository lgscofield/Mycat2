# I-DEBT-54·D3 专轮设计：binary 字面量回灌线级保真（bit/BLOB `_binary 'bytes'` 1406）

> 状态：设计已定稿待实施。承 2026-10-17 D1/D2 专轮遗留挂账（`d1d2-e2e-evidence.txt` §结论），本专轮关闭三破面中最后一面 D3。

## 1. 现象与根因（本轮全部实跑取证，非旧记录）

**金标准判据**：真 mysqldump 二进制默认口径导出含 `bit(8)` 的表 → 回灌 **Mycat** →
`ERROR 1406 (HY000): Data truncation: Data too long for column 'flag' at row 2`。
同文件回灌**原生 MySQL 8.0** RC=0 且逐值保真（导出字节 `\0`/0xAA/0xFF/0x01 全部正确）——破面 100% 在 Mycat 接收侧。

**根因链（三段，均已钉死）**：
1. **前端解码丢失（唯一不可逆点）**：`MycatVertxMySQLHandler.java:92`
   `new String(readEOFStringBytes(), StandardCharsets.UTF_8)` —— 非法 UTF-8 字节（0xAA/0xFF）被
   replace 成 U+FFFD，不同字节信息坍缩，不可逆。
2. **参数化引入模板/参数分叉**：druid 把 `_binary 'x'` 解析为 `MySqlCharExpr`，**`getCharset()="_binary"`
   introducer 在 AST 保留、toString 保真输出**（探针实测）；但 `MycatPreparedStatementUtil.outputToParameterized`
   的 `MySqlExportParameterVisitor` 把 VALUES 里的该字面量折叠为 `?` + **String 值参数**，
   restore 阶段以普通字符串回填 → introducer 丢，且值已含 U+FFFD。
3. **后端 JDBC 再编码**：`NewMycatConnectionImpl.insert(String sql)` 把含 U+FFFD 的文本交 Connector/J，
   按连接字符集 UTF-8 编码出 `EF BF BD` 3 字节 → bit(8) 只容 1 字节 → 后端拒 1406。
   （general_log 实拿下发 SQL：`(2,'<EFBFBD>','aa')`，无 `_binary` 前缀。）

**行为对照（真 MySQL 为何能对）**：MySQL 服务器把客户端字符集非法字节保真映射为同值 latin1 码位
（"guaranteed reversibility"），且 `_binary` introducer 的字面量按字节存储不转码；Connector/J 的
`com.mysql.cj.protocol.a.NativeConstants$AbstractStringUtils$SingleByteCharsetConverter` 本就内置该
latin1 兜底（实测其 `latin1ToBytes` 表：U+0080..U+00FF → 单字节原值；其余 → '?'）。

## 2. 修复设计（三层各自闭环）

### D3-a 前端解码：MySQL 式可逆解码（common 新增工具类）
`io.mycat.util.MySQLSqlTextCodec`（common，前端/执行层均可依赖）：
- `public static String decode(byte[] bytes)`：按 UTF-8 解码；**对每个非法 UTF-8 起始/延续字节，
  恒等映射为同码位值字符 U+00xx**（等价 MySQL 服务器兜底 + Connector latin1ToBytes 逆域）。
  合法 UTF-8 序列（含全部中文）解码行为与现状**完全一致** → 零回归面。
- 解码规则细节：采用与 `CharsetDecoder(UTF_8)` 相同的序列识别（合法多字节按正常解码；
  遇到非法字节 emit 其 U+0000+byte 码位并前进一字节）。
- 接入点：`MycatVertxMySQLHandler` COM_QUERY（L92）与 COM_STMT_PREPARE 的 SQL 文本解码
  （统一经 ReadView 提供字节 → 调 codec；其余 NUL/EOF 字符串如 schema 名维持现状，仅 SQL 文本换）。

### D3-b 写路径：binary-introducer 字面量不参数化
`MycatPreparedStatementUtil.outputToParameterized` 的匿名 visitor 增补：
`visit(MySqlCharExpr x)`：当 `x.getCharset()` 非空且形如 `_binary`（charset introducer 字面量）时，
`this.parameterized = false` 后 `super.visit(x)`（finally 恢复 true）——与既有
`SQLIntervalExpr` 的处理完全同构。效果：该字面量**原文留在模板**，introducer 天然保留；
经 D3-a 后模板文本中的 U+00xx 码位由后端连接器逐字节还原（见 D3-c），不再进 String 参数链。
> 与 I-DEBT-44 关系：同族（b'…' 靠 UnMergeValuesList 保真；本面是 `_binary '…'` 保真），互不冲突。

### D3-c 后端下发：latin1 兜底使 U+00xx 逐字节还原
JDBC 数据源 URL 追加 `characterEncoding=UTF-8` **并** 使模板文本经 Connector 的
SingleByteCharsetConverter 域：Connector/J 9.x 在 `connectionCollation=utf8mb4_*` 下对
String→wire **不做** latin1 兜底（'ª'→c2aa）。为使 U+00xx 单字节还原，Mycat 在下发前把
SQL 文本**自编码**：新增 `MySQLSqlTextCodec.encode(String)`（decode 的精确逆：
U+0000..U+00FF 码位 → 单字节；其余 → 标准 UTF-8），产物 byte[] 再以**ISO-8859-1 String** 视
需要传递——**实际落点**：`NewMycatConnectionImpl`/原生协议 backend 的 sql 文本发送处按后端类型二选一：
- 原生协议后端（`RowSetQuery.java:123` `curSql.getBytes()` 平台默认编码——本就须改为显式
  `MySQLSqlTextCodec.encode`）直接发 byte[]，天然逐字节保真。
- JDBC 后端：Connector 无 byte 级注入口 → 采用**编码对称链**：URL 配 `characterEncoding=ISO-8859-1`
  使 Connector 用 latin1 表把 U+00xx 编回原字节；服务端 `character_set_client` 由握手协商为 latin1，
  **中文等合法多字节字符**因 D3-a 解码仍产出真实 Unicode 码位、latin1 无法编码 → 会在中文场景破坏，
  **故 JDBC 后端不采用 URL latin1**；改为：仅当 SQL 文本含 U+0080..U+00FF 码位且源自 binary introducer
  片段时，执行层把该片段**改写为 `_binary x'HH…'` 十六进制字面量**（纯 ASCII 输出，一切字符集安全）。
  改写点：D3-b 同一 visitor 的 post 阶段（对未参数化的 MySqlCharExpr+charset 片段做 hex 化输出），
  即 **D3-b 的最终产物就是 `_binary x'AA'` 模板文本**，中文场景完全不受影响，JDBC/原生两后端全兼容。

> **收敛定稿（取代上述 D3-c 备选）**：D3-b 与 D3-c 合并为一步——binary introducer 字面量
> **不参数化 + 输出 hex 形态 `_binary x'..'`**（visitor 对该节点定制输出）。前端 D3-a 保证
> 模板文本中的原字节可逆（U+00xx 码位），hex 化把 U+00xx 码位转成纯 ASCII 十六进制，彻底绕过
> 后端编码差异。原生协议后端无需 hex 也安全，但统一走 hex 使两后端行为一致、且与真 mysqldump
> `--hex-blob` 产物同形（MySQL 原生接受 `_binary x'AA'`，探针实测原生死锁的 LITERAL_HEX 拒Parse
> 是**前端 druid 方言**问题，不是后端 MySQL 问题——后端只收执行层产出的文本）。
> **注意**：前端用户直发 `x'AA'`/`0xAA` 十六进制字面量的支持属另一面（druid parser 不支持
> `_binary 0xAA` 组合），不在本专轮范围，挂账。

## 3. 影响面与回归判据
- 改动文件（预估）：`common/…/MySQLSqlTextCodec.java`（新）、`server/…/MycatVertxMySQLHandler.java`
  （2 处解码接入）、`hbt/…/MycatPreparedStatementUtil.java`（visitor 增补 + hex 输出）、
  `server/…/RowSetQuery.java`（getBytes 显式编码，顺手修正平台默认编码隐患）。
- 零回归判据：合法 UTF-8 SQL（含中文）decode 行为与现状一致；无 charset introducer 的字面量照常参数化；
  `b'1010'`（I-DEBT-44 面）不受触碰。
- 测试网：
  1. 单测 codec decode/encode 双向保真（含中文、非法字节、混合序列）；
  2. 单测 preParse：`insert … values(2,_binary '<0xAA>','aa')` → parameterizedSQL 含 `_binary x'AA'`、
     params 不含该值、中文/普通字面量仍参数化；
  3. e2e 金标准：dbdump3（bit(8) 全判别值）真 mysqldump 导出 → 回灌 Mycat RC=0 → Mycat 读回逐值保真 +
     回灌原生逐值保真（正反双靶）；
  4. compat 全矩阵 + hbt 全量回归绿。

## 4. 明确不做（挂账）
- 前端 druid 对 `_binary 0xAA` / `x'AA'` 十六进制字面量的**用户直发**解析支持（独立小面，另票）。
- D2b（sql_mode 分组放宽遵从）——维持挂账，跨切验证器改动，与本面正交。
- 结果集方向（Mycat→客户端）的二进制表征：本轮导出已实测逐字节保真，无需改动。
