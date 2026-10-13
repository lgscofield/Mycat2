# Mycat2

Distributed database based on MySQL or JDBC.
## 官网
http://mycatone.top/

## Software introduction
Mycat2 is a distributed relational database (middleware) developed by the Mycat community. It supports distributed SQL queries, is compatible with the MySQL communication protocol, supports a variety of back-end databases with Java ecology, and improves data query processing capabilities through data fragmentation.
## 软件介绍
Mycat2是Mycat社区开发的一款分布式关系型数据库(中间件)。它支持分布式SQL查询，兼容MySQL通信协议，以Java生态支持多种后端数据库，通过数据分片提高数据查询处理能力。

gitee:https://gitee.com/MycatOne/Mycat2

github:https://github.com/MyCATApache/Mycat2

![](https://github.com/MyCATApache/Mycat2/workflows/Java%20CI%20-%20Mycat2%20Main/badge.svg)
![](https://github.com/MyCATApache/Mycat2/workflows/Java%20CI%20-%20Mycat2%20Dev/badge.svg)


[v1.22-release](https://github.com/MyCATApache/Mycat2/releases/tag/v1.22-2022-6-25)



[doc-en](https://www.yuque.com/ccazhw/ml3nkf/bef923fb8acc57e0f805d45ef7782670?translate=en)
[doc-cn](https://www.yuque.com/books/share/6606b3b6-3365-4187-94c4-e51116894695)


[![Stargazers over time](https://starchart.cc/MyCATApache/Mycat2.svg)](https://starchart.cc/MyCATApache/Mycat2)
      

## Features
1. Open source code
Learning middleware technology, database technology, and code is a must.
2. Distributed query engine compatible with MySQL syntax
• Compatible with MySQL syntax.
• Compatible with MySQL value types.
• Use an optimizer based on rule optimization and cost.
• Independent physical execution engine.
3. Custom function algorithm development
• Sharding algorithm, serial number algorithm, load balancing algorithm, etc. can all be customized to load.
• The query engine can run away from the network framework.
4. Customize the process
Self-developed DSL manipulates the physical query plan.
Support SQL forwarding and cache result set.


## Non-Goals (Scope Exclusions)

The following are explicitly out of scope. Mycat2 is a MySQL-protocol-compatible **distributed query middleware**, not a reimplementation of the MySQL server kernel. Anything below is delegated to physical backends (MySQL / MariaDB / etc.) or to dedicated CDC tooling. This section mirrors §1.2 of `docs/superpowers/specs/2026-09-30-mycat2-mysql-mariadb-lts-compatibility-design.md` and is intended to prevent scope creep.

- **N1 — Proxy as a replication master**: Mycat2 does **not** implement `COM_BINLOG_DUMP` / `COM_BINLOG_DUMP_GTID` / `CLIENT_MASTER`. The `COM_BINLOG_DUMP` opcode constant exists in `MySQLCommandType` (protocol completeness only), but no handler is registered and `CommandDispatcher` has no route — a slave client sending this command will get a protocol error, not a binlog stream. This is consistent with MyCat1, ProxySQL, and ShardingSphere. Downstream CDC consumers (Canal, Flink CDC, Debezium) must attach to each **physical** shard directly, not to the proxy. Reference: MySQL 8.0 §13.7.4.13 `COM_BINLOG_DUMP` / §13.7.4.14 `COM_BINLOG_DUMP_GTID`.
- **N2 — InnoDB kernel responsibilities**: storage engine internals, real transaction isolation levels, backup locks (`LOCK INSTANCE FOR BACKUP`), GTID generation and `gtid_executed` bookkeeping — all provided by the physical backend, not simulated by Mycat2.
- **N3 — MySQL Shell AdminAPI / InnoDB Cluster**: Mycat2 does not emulate `\cluster`, `replication_metadata`, `mysql.native_password` rotation flows driven by MySQL Shell, or `Group_Replication` administrative interfaces.
- **N4 — Productized external CDC subscription**: the `MIGRATE_*` / `BINLOG_*` hint family (see `HintHandler`) exists only for internal data-migration tooling (L3 online migration). It is not offered as an external, supported CDC subscription product.



## Framework

![](https://cdn.nlark.com/yuque/0/2021/png/658548/1615792485342-b0f62690-e0cf-4f4a-89b6-18e5e1487227.png)

Currently, only mycat2 is supported on java8, and will be supported in other versions later.



## License

Apache LICENSE
from 2023.8