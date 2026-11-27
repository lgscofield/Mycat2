import os
import sys
import time

import pymysql


def connect(port):
    return pymysql.connect(
        host="127.0.0.1",
        port=port,
        user="root",
        password=os.environ["MYSQL_PWD"],
        database="mysql",
        connect_timeout=10,
        read_timeout=10,
        write_timeout=10,
        autocommit=True,
    )


def scalar(connection, sql):
    with connection.cursor() as cursor:
        cursor.execute(sql)
        return cursor.fetchone()[0]


def probe_unknown_command(port):
    connection = connect(port)
    try:
        try:
            connection._execute_command(0x7F, b"")
            connection._read_packet()
            raise AssertionError("unknown command unexpectedly succeeded")
        except pymysql.err.NotSupportedError as error:
            if error.args[0] != 1235:
                raise AssertionError(f"unknown command error code: {error.args[0]}") from error
    finally:
        connection.close()
    print("unknown_command\tPASS\terror_code=1235")


def probe_hints(port):
    connection = connect(port)
    try:
        for hint in ("MERGE_UNION_SIZE(2)", "EXECUTE_TIMEOUT(1000)"):
            if scalar(connection, f"SELECT /*+ mycat:{hint} */ 1") != 1:
                raise AssertionError(f"hint query returned wrong result: {hint}")
    finally:
        connection.close()
    print("hints\tPASS\tmerge_union_size_and_execute_timeout")


def probe_idle(port):
    connection = connect(port)
    try:
        time.sleep(5)
        try:
            scalar(connection, "SELECT 1")
            raise AssertionError("idle connection was not closed")
        except pymysql.err.OperationalError:
            pass
    finally:
        connection.close()
    print("idle_timeout\tPASS\tinactive_connection_closed")

    connection = connect(port)
    try:
        for _ in range(6):
            if scalar(connection, "SELECT 1") != 1:
                raise AssertionError("active connection query failed")
            time.sleep(1)
    finally:
        connection.close()
    print("idle_timeout\tPASS\tactive_connection_survived")


def main():
    port = int(sys.argv[1])
    probe_unknown_command(port)
    probe_hints(port)
    probe_idle(port)
    print("verdict\tPASS")


if __name__ == "__main__":
    main()
