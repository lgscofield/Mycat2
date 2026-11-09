# MATRIX96-ENV — I-DEBT-96 轮1:WSL2 七版本数据库环境与原始连通性矩阵

- 日期:2026-11-10(执行窗口 2026-11-09 晚 ~ 11-10)
- 执行环境:Windows 宿主(win32)+ WSL2 `Ubuntu-24.04`(24.04.1 LTS,kernel 5.15+,8C/24G,/ 251G 空闲 227G)
- 数据纪律:全部安装/数据/日志集中在 WSL `/opt/matrix-data/`;未卸载、未触碰 WSL 既有系统文件;Windows 侧仅写入本目录
- 未启动任何 mycat 实例;未改 mycat 配置;未动宿主 8066/8068

## 1. 结论速览

**7/7 版本就绪,WSL 内 7/7 PASS,Windows 宿主侧 7/7 PASS(含 MySQL 5.7,无 NO_DATA)。**
MySQL 5.7.44 通过官方 glibc2.12 tarball + 兼容库垫片成功跑通,见 §5。

| 端口 | 实例名 | 版本串(SELECT VERSION()) | 安装方式 | WSL 内 | 宿主侧 |
|---|---|---|---|---|---|
| 3401 | ms57 | 5.7.44 | 官方 tarball (glibc2.12) + lib-compat 垫片 | PASS | PASS |
| 3402 | ms80 | 8.0.45 | 官方 tarball (glibc2.28, full) | PASS | PASS |
| 3403 | ms84 | 8.4.8 | 官方 tarball (glibc2.28, minimal) | PASS | PASS |
| 3404 | ms96 | 9.6.0 | 官方 tarball (glibc2.28, minimal) | PASS | PASS |
| 3405 | mar106 | 10.6.28-MariaDB | 官方 bintar (archive.mariadb.org 阿里云镜像) | PASS | PASS |
| 3406 | mar1011 | 10.11.19-MariaDB | 官方 bintar (同上) | PASS | PASS |
| 3407 | mar11 | 11.8.9-MariaDB | 官方 bintar (同上) | PASS | PASS |

- WSL 内验证:各版本**自带客户端** `-h127.0.0.1 -P340x -uroot -p123456 -N -e "SELECT 1; SELECT VERSION()"` → 全部返回 `1` + 版本串
- 宿主侧验证:`"D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -h127.0.0.1 -P340x -uroot -p123456 -e "SELECT 1; SELECT VERSION()"` → 7/7 返回(WSL2 localhost 转发生效)

## 2. 原始矩阵证据(实际命令输出)

WSL 内(自带客户端,TCP):
```
PASS ms57 :3401 [1 5.7.44]
PASS ms80 :3402 [1 8.0.45]
PASS ms84 :3403 [1 8.4.8]
PASS ms96 :3404 [1 9.6.0]
PASS mar106 :3405 [1 10.6.28-MariaDB]
PASS mar1011 :3406 [1 10.11.19-MariaDB]
PASS mar11 :3407 [1 11.8.9-MariaDB]
```

Windows 宿主侧(mysql.exe 8.4,经 WSL2 localhost 转发):
```
HOST_PASS :3401 [1 5.7.44]
HOST_PASS :3402 [1 8.0.45]
HOST_PASS :3403 [1 8.4.8]
HOST_PASS :3404 [1 9.6.0]
HOST_PASS :3405 [1 10.6.28-MariaDB]
HOST_PASS :3406 [1 10.11.19-MariaDB]
HOST_PASS :3407 [1 11.8.9-MariaDB]
```

status-all(2026-11-10):ms57/ms80/ms84/ms96/mar106/mar1011/mar11 全部 UP。

## 3. 安装来源(官方渠道,全部落在 /opt/matrix-data)

| 实例 | 归档 | 来源 URL | 大小 |
|---|---|---|---|
| ms57 | mysql-5.7.44-linux-glibc2.12-x86_64.tar.gz | https://cdn.mysql.com/Downloads/MySQL-5.7/ | 694,785,800 B |
| ms80 | mysql-8.0.45-linux-glibc2.28-x86_64.tar.xz | https://cdn.mysql.com/archives/mysql-8.0/ | 891,437,968 B |
| ms84 | mysql-8.4.8-linux-glibc2.28-x86_64-minimal.tar.xz | https://cdn.mysql.com/archives/mysql-8.4/ | 66,039,584 B |
| ms96 | mysql-9.6.0-linux-glibc2.28-x86_64-minimal.tar.xz | https://cdn.mysql.com/Downloads/MySQL-9.6/ | 69,934,940 B |
| mar106 | mariadb-10.6.28-linux-systemd-x86_64.tar.gz | https://mirrors.aliyun.com/mariadb/mariadb-10.6.28/bintar-linux-systemd-x86_64/ | 326,718,696 B |
| mar1011 | mariadb-10.11.19-linux-systemd-x86_64.tar.gz | https://mirrors.aliyun.com/mariadb/mariadb-10.11.19/… | 357,390,930 B |
| mar11 | mariadb-11.8.9-linux-systemd-x86_64.tar.gz | https://mirrors.aliyun.com/mariadb/mariadb-11.8.9/… | 447,936,227 B |

版本口径(执行日 2026-11 各官方源最新可用):8.0.45 为 archives 中最新 8.0 tarball;8.4.8/9.6.0 为 cdn.mysql.com 当前可拉取的最新 8.4 LTS/9.x innovation;MariaDB 10.6.28(已 EOL,archive 末版)/10.11.19/11.8.9(LTS)。

## 4. 与任务书的两处方法偏差(及原因)

1. **MySQL 8.0/8.4 未走 apt、MariaDB 未走 apt 仓库安装,统一改官方 tarball/bintar。** 原因:
   - 同一 Ubuntu 上 apt 安装多个大版本不可共存(MySQL 8.0/8.4 的包均占据 `/usr/sbin/mysqld`,MariaDB 10.6/10.11/11.x 均占据 `/usr/sbin/mariadbd`,互为冲突/替换),无法形成七版本矩阵;
   - 官方 MariaDB apt 仓库对 noble(Ubuntu 24.04)**没有 10.6 版本**(实测 `mirrors.aliyun.com/mariadb/repo/10.6/ubuntu/dists/noble/` → 404;noble 自带源仅 10.11);
   - 任务书本身对 5.7/9.x 即指定 tarball 路线,故 7 个全部 tarball 化保持方法一致、可并存、可整体清理。
2. **启动用手工脚本而非 systemd 单元**:该 WSL 的 PID1 为 init(systemd 未启用),按任务书允许的备选路线使用手工启动脚本(见 §7)。

## 5. 兼容垫片与 apt 包清单(清理时需 purge)

- **libaio**:noble 的 libaio 包为 t64 变更,仅提供 `libaio.so.1t64`,MySQL 全系二进制需 `libaio.so.1` → 垫片符号链接 `/opt/matrix-data/lib-compat/libaio.so.1 -> /usr/lib/x86_64-linux-gnu/libaio.so.1t64`(所有 ms* 启动包装器注入 LD_LIBRARY_PATH)
- **5.7 客户端**:需 libtinfo.so.5/libncurses.so.5(noble 已移除)→ 从 archive.ubuntu.com(jammy pool)下载 `libtinfo5_6.3-2_amd64.deb`、`libncurses5_6.3-2_amd64.deb`,`dpkg -x` 解入 `/opt/matrix-data/lib-compat/`(未安装进系统);deb 原件留存在 `/opt/matrix-data/tmp-deb/` 备查
- **5.7 服务端**(linux-generic 构建)无 libssl 外链问题,仅需 libaio 垫片即正常运行
- **apt 实际安装的包**(记录于 `/opt/matrix-data/apt-packages-installed.txt`,下轮清理照单 purge):
  ```
  ca-certificates 20260816ubuntu0.24.04.2   ca-certificates-java 20240118
  cpio 2.15+dfsg-1ubuntu2.1                 curl 8.5.0-2ubuntu10.16
  libaio1t64:amd64 0.3.113-6build1.1        libncurses6:amd64 6.4+20240113-1ubuntu2.2
  libnuma1:amd64 2.0.18-1ubuntu0.24.04.1    procps 2:4.0.4-4ubuntu3.3
  rpm 4.18.2+dfsg-2.1build2                 rpm2cpio 4.18.2+dfsg-2.1build2
  xz-utils 5.6.1+really5.4.5-1ubuntu0.4
  ```

## 6. 每实例布局与参数

统一约定(全部位于 `/opt/matrix-data/`):
- 二进制根:`<basedir>`;数据目录:`data/<name>`;配置:`etc/<name>.cnf`;套接字:`run/<name>.sock`;PID:`run/<name>.pid`;错误日志:`log/<name>.err`
- my.cnf 关键项:`port=340x`、`bind-address=0.0.0.0`、`skip-name-resolve`、`user=root`、`mysqlx=OFF`(仅 ms80/ms84/ms96;5.7 linux-generic 无内建 X 插件,10.x/11.x MariaDB 无 X)
- 账号:`root@localhost` 与 `root@'%'` 密码均为 **123456**;MySQL 系为 caching_sha2(8.0+/9.x)/native(5.7),MariaDB 为 mysql_native_password
- 初始化命令:
  - MySQL:`/opt/matrix-data/bin-<name> --initialize-insecure`(`bin-<name>` 为带 LD 垫片的 mysqld 包装器)
  - MariaDB:`<basedir>/scripts/mariadb-install-db --defaults-file=etc/<name>.cnf --user=root --auth-root-authentication-method=normal`
- **踩坑记录(下轮注意)**:mariadb-install-db 会额外创建 `root@127.0.0.1`、`root@::1`、`root@<hostname>` 的空密码条目,优先级压过 `root@'%'` 导致 TCP 拒连;已对三个 MariaDB 实例执行 `DELETE FROM mysql.user WHERE User='root' AND Host NOT IN ('localhost','%'); DELETE FROM mysql.user WHERE User=''; FLUSH PRIVILEGES;` 清除

| name | basedir | 版本 | 端口 |
|---|---|---|---|
| ms57 | /opt/matrix-data/mysql-5.7 | MySQL 5.7.44 | 3401 |
| ms80 | /opt/matrix-data/mysql-8.0 | MySQL 8.0.45 | 3402 |
| ms84 | /opt/matrix-data/mysql-8.4 | MySQL 8.4.8 | 3403 |
| ms96 | /opt/matrix-data/mysql-9.x | MySQL 9.6.0 | 3404 |
| mar106 | /opt/matrix-data/mariadb-10.6 | MariaDB 10.6.28 | 3405 |
| mar1011 | /opt/matrix-data/mariadb-10.11 | MariaDB 10.11.19 | 3406 |
| mar11 | /opt/matrix-data/mariadb-11.x | MariaDB 11.8.9 | 3407 |

## 7. 运维命令(WSL 内,root)

```bash
# 启动(幂等,Windows 侧执行)
wsl -d Ubuntu-24.04 -u root -- /opt/matrix-data/scripts/start-all.sh
# 状态
wsl -d Ubuntu-24.04 -u root -- /opt/matrix-data/scripts/status-all.sh
# 停止
wsl -d Ubuntu-24.04 -u root -- /opt/matrix-data/scripts/stop-all.sh
# 单实例直启(示例 5.7)
nohup /opt/matrix-data/bin-ms57 >> /opt/matrix-data/log/ms57.nohup 2>&1 &
# 宿主连通抽查
"D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -h127.0.0.1 -P3401 -uroot -p123456 -e "SELECT VERSION()"
```

注意:Windows 重启会杀掉 WSL 内进程,重启后执行 start-all.sh 即可(数据均在,不需重新初始化)。

## 8. 磁盘占用(前后对比,命令按铁律输出)

命令:`du -sh /opt/matrix-data /var/lib/mysql* /var/lib/mysql-* 2>/dev/null`

- **前(2026-11-09 基线)**:`/opt/matrix-data` 不存在,`/var/lib/mysql*` 无任何输出(系统从未装过 mysql/mariadb,dpkg 计 0 条)
- **后(2026-11-10)**:
```
13G	/opt/matrix-data
(/var/lib/mysql* 依旧无输出 —— 未触碰)
```
- 明细:downloads 2.7G(tarball 原件保留备复装)、mysql-5.7 2.6G、mysql-8.0 1.7G、mariadb-11.x 1.4G、data 1.2G(七个数据目录)、mariadb-10.11 1.1G、mariadb-10.6 1016M、mysql-9.x 464M、mysql-8.4 446M、其余 <1M

## 9. 清理指令备忘(下轮清理时照做,本轮不执行)

```bash
# 0) 停库
wsl -d Ubuntu-24.04 -u root -- /opt/matrix-data/scripts/stop-all.sh
# 1) 整体删除数据/二进制/tarball(所有安装与数据均在此目录内)
wsl -d Ubuntu-24.04 -u root -- bash -c 'rm -rf /opt/matrix-data'
# 2) purge 本轮 apt 安装的包(见 §5 清单;先试 purge,失败可 autoremove)
wsl -d Ubuntu-24.04 -u root -- bash -c 'xargs -a /opt/matrix-data/apt-packages-installed.txt -n1 echo apt-get purge -y 2>/dev/null; DEBIAN_FRONTEND=noninteractive apt-get purge -y ca-certificates-java cpio curl libaio1t64 libncurses6 libnuma1 procps rpm rpm2cpio xz-utils; apt-get autoremove -y'
#    (ca-certificates/curl 视宿主依赖链允许性决定是否保留,不影响矩阵)
# 3) 可选:压缩 WSL vhdx 回收宿主磁盘(需管理员 PowerShell)
#    wsl --shutdown; Optimize-VHD -Path <Ubuntu-24.04.vhdx> -Mode Full
#    (或 diskpart → select vdisk file=… → compact vdisk)
```

## 10. 遗留与建议

- tarball 原件(2.7G)保留于 downloads/,若磁盘紧张可在下一轮直接删除,不影响已装实例
- `log/<name>.init.out`、`log/<name>.err` 保留初始化与启动原始日志
- MariaDB 的 systemd bintar 含 `support-files/` 脚本,如后续需要 systemd 单元可参照生成,但当前 WSL 未启用 systemd
- 本轮未创建任何业务库/表(仅账号与系统库),矩阵环境对轮2(Canonical SQL/case 注入)完全就绪
