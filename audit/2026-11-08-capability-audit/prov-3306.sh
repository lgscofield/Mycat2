#!/bin/bash
export LD_LIBRARY_PATH=/opt/matrix-data/lib-compat
M=/opt/matrix-data/mysql-8.4/bin/mysql
$M -hlocalhost -uroot -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '123456'; CREATE USER 'root'@'127.0.0.1' IDENTIFIED BY '123456'; CREATE USER 'root'@'%' IDENTIFIED BY '123456'; GRANT ALL ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION; GRANT ALL ON *.* TO 'root'@'%' WITH GRANT OPTION; CREATE DATABASE IF NOT EXISTS db1; CREATE DATABASE IF NOT EXISTS db2; FLUSH PRIVILEGES;"
$M -h127.0.0.1 -P3306 -uroot -p123456 -N -B -e "SELECT '3306-gate provisioned', VERSION()"
echo PROV_DONE
