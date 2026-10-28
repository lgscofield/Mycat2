/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.calcite.sqlfunction.hashfunction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-32-FAMILY 加密/哈希族静态单测：MySQL {@code MD5}/{@code SHA1} 语义对齐。
 *
 * <p>全部期望值取自真库 MySQL 8.0.46（docker week-m80, {@code --default-character-set=utf8mb4}）实测：
 * <pre>
 *   select md5('abc'), sha1('abc'), md5(''), sha1(''), md5('中文'), sha1('中文');
 *   -&gt; 900150983cd24fb0d6963f7d28e17f72 | a9993e364706816aba3e25717850c26c9cd0d89d
 *      | d41d8cd98f00b204e9800998ecf8427e | da39a3ee5e6b4b0d3255bfef95601890afd80709
 *      | a7bac2239fcdcb3a067903d8077c4a07 | 7be2d2d20c106eee0836c9bc2b939890a78e8fb3
 * </pre>
 * 其中非 ASCII 用例把"按 UTF-8 字节取摘要"钉死：真库对 utf8mb4 连接是按 UTF-8 字节哈希
 * （{@code md5(cast('中文' as binary))} 与 {@code md5('中文')} 同值），
 * 若实现走平台默认字符集（Windows JVM 常为 GBK）会得到不同摘要。
 */
public class MysqlHashFunctionTest {

    @Test
    public void md5MatchesRealMysql() {
        Assertions.assertEquals("900150983cd24fb0d6963f7d28e17f72", MD5Function.md5("abc"));
        Assertions.assertEquals("0cc175b9c0f1b6a831c399e269772661", MD5Function.md5("a"));
        Assertions.assertEquals("d41d8cd98f00b204e9800998ecf8427e", MD5Function.md5(""));
        // 真库 utf8mb4 连接下按 UTF-8 字节哈希
        Assertions.assertEquals("a7bac2239fcdcb3a067903d8077c4a07", MD5Function.md5("中文"));
    }

    @Test
    public void sha1MatchesRealMysql() {
        Assertions.assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SHA1Function.sha1("abc"));
        Assertions.assertEquals("86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", SHA1Function.sha1("a"));
        Assertions.assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", SHA1Function.sha1(""));
        Assertions.assertEquals("7be2d2d20c106eee0836c9bc2b939890a78e8fb3", SHA1Function.sha1("中文"));
    }

    /** 真库 {@code MD5(NULL)}/{@code SHA1(NULL)} 均返回 NULL（不抛）。 */
    @Test
    public void nullArgumentReturnsNull() {
        Assertions.assertNull(MD5Function.md5(null));
        Assertions.assertNull(SHA1Function.sha1(null));
    }

    /** 摘要为小写十六进制，长度 32/40（与真库 {@code character_length} 实测一致）。 */
    @Test
    public void digestIsLowercaseHexOfFixedWidth() {
        Assertions.assertEquals(32, MD5Function.md5("abc").length());
        Assertions.assertEquals(40, SHA1Function.sha1("abc").length());
        Assertions.assertEquals(MD5Function.md5("abc").toLowerCase(), MD5Function.md5("abc"));
        Assertions.assertEquals(SHA1Function.sha1("abc").toLowerCase(), SHA1Function.sha1("abc"));
    }
}
