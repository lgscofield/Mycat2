/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.beans.mysql;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for MySQLDialect version detection.
 */
public class MySQLDialectTest {

    @Test
    public void testDetectMariaDB() {
        // MariaDB 10.x versions
        Assert.assertEquals(MySQLDialect.MARIADB_10_X, MySQLDialect.detect("10.6.15-MariaDB"));
        Assert.assertEquals(MySQLDialect.MARIADB_10_X, MySQLDialect.detect("10.11.6-MariaDB"));
        Assert.assertEquals(MySQLDialect.MARIADB_10_X, MySQLDialect.detect("10.3.39-MariaDB"));
        Assert.assertEquals(MySQLDialect.MARIADB_10_X, MySQLDialect.detect("10.0.38-MariaDB"));
        Assert.assertEquals(MySQLDialect.MARIADB_10_X, MySQLDialect.detect("5.5.68-MariaDB"));
    }

    @Test
    public void testDetectMySQL() {
        // MySQL 8.0.x
        Assert.assertEquals(MySQLDialect.MYSQL_8_0, MySQLDialect.detect("8.0.28"));
        Assert.assertEquals(MySQLDialect.MYSQL_8_0, MySQLDialect.detect("8.0.33-log"));
        Assert.assertEquals(MySQLDialect.MYSQL_8_0, MySQLDialect.detect("8.0.35"));

        // MySQL 5.7.x
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("5.7.33"));
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("5.7.40-log"));
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("5.7.36"));

        // MySQL 5.6.x defaults to 5.7
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("5.6.51"));
    }

    @Test
    public void testDetectEdgeCases() {
        // Null and empty
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect(null));
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect(""));

        // Unknown version defaults to 5.7
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("unknown"));
        Assert.assertEquals(MySQLDialect.MYSQL_5_7, MySQLDialect.detect("9.0.0"));
    }

    @Test
    public void testIsMariaDB() {
        Assert.assertTrue(MySQLDialect.MARIADB_10_X.isMariaDB());
        Assert.assertFalse(MySQLDialect.MYSQL_5_7.isMariaDB());
        Assert.assertFalse(MySQLDialect.MYSQL_8_0.isMariaDB());
    }

    @Test
    public void testIsMySQL() {
        Assert.assertFalse(MySQLDialect.MARIADB_10_X.isMySQL());
        Assert.assertTrue(MySQLDialect.MYSQL_5_7.isMySQL());
        Assert.assertTrue(MySQLDialect.MYSQL_8_0.isMySQL());
    }

    @Test
    public void testSupportsUca1400Collation() {
        Assert.assertTrue(MySQLDialect.MARIADB_10_X.supportsUca1400Collation());
        Assert.assertFalse(MySQLDialect.MYSQL_5_7.supportsUca1400Collation());
        Assert.assertFalse(MySQLDialect.MYSQL_8_0.supportsUca1400Collation());
    }

    @Test
    public void testSupportsSystemVersionedTables() {
        Assert.assertTrue(MySQLDialect.MARIADB_10_X.supportsSystemVersionedTables());
        Assert.assertFalse(MySQLDialect.MYSQL_5_7.supportsSystemVersionedTables());
        Assert.assertFalse(MySQLDialect.MYSQL_8_0.supportsSystemVersionedTables());
    }

    @Test
    public void testSupportsXaTransactions() {
        // All dialects support XA
        Assert.assertTrue(MySQLDialect.MARIADB_10_X.supportsXaTransactions());
        Assert.assertTrue(MySQLDialect.MYSQL_5_7.supportsXaTransactions());
        Assert.assertTrue(MySQLDialect.MYSQL_8_0.supportsXaTransactions());
    }

    @Test
    public void testValidationQuery() {
        Assert.assertEquals("SELECT 1", MySQLDialect.MARIADB_10_X.getValidationQuery());
        Assert.assertEquals("SELECT 1", MySQLDialect.MYSQL_5_7.getValidationQuery());
        Assert.assertEquals("SELECT 1", MySQLDialect.MYSQL_8_0.getValidationQuery());
    }

    @Test
    public void testToString() {
        Assert.assertEquals("MariaDB 10.x", MySQLDialect.MARIADB_10_X.toString());
        Assert.assertEquals("MySQL 5.7", MySQLDialect.MYSQL_5_7.toString());
        Assert.assertEquals("MySQL 8.0", MySQLDialect.MYSQL_8_0.toString());
    }
}
