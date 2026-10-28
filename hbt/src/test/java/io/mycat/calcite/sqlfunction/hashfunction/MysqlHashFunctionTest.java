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

    /**
     * {@code SHA2(str, hashLength)} 语义，全部期望值取自真库 MySQL 8.0.46（docker week-m80, utf8mb4）实测，
     * 且与 MariaDB 10.11（m1-mysql-a）逐值一致：
     * <ul>
     *   <li>224/256/384/512 → 对应摘要小写十六进制（长 56/64/96/128）；</li>
     *   <li>len=0 视同 256（真库实测 {@code SHA2('abc',0)=SHA2('abc',256)}）；</li>
     *   <li>非法长度（1/160/999/负）→ NULL；</li>
     *   <li>非 ASCII 按 UTF-8 字节摘要（{@code SHA2('中文',256)=SHA2(CAST('中文' AS BINARY),256)} 同值）。</li>
     * </ul>
     */
    @Test
    public void sha2MatchesRealMysql() {
        Assertions.assertEquals("23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7",
                SHA2Function.sha2("abc", 224));
        Assertions.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SHA2Function.sha2("abc", 256));
        Assertions.assertEquals("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
                SHA2Function.sha2("abc", 384));
        Assertions.assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                SHA2Function.sha2("abc", 512));
        Assertions.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SHA2Function.sha2("", 256));
        // 真库 len=0 视同 256
        Assertions.assertEquals(SHA2Function.sha2("abc", 256), SHA2Function.sha2("abc", 0));
        // 非 ASCII 按 UTF-8 字节摘要
        Assertions.assertEquals("72726d8818f693066ceb69afa364218b692e62ea92b385782363780f47529c21",
                SHA2Function.sha2("中文", 256));
    }

    /** 非法摘要长度真库返回 NULL（MySQL 8.0.46 实测：1/160/999/负 均为 NULL）。 */
    @Test
    public void sha2InvalidLengthReturnsNull() {
        Assertions.assertNull(SHA2Function.sha2("abc", 1));
        Assertions.assertNull(SHA2Function.sha2("abc", 160));
        Assertions.assertNull(SHA2Function.sha2("abc", 999));
        Assertions.assertNull(SHA2Function.sha2("abc", -1));
    }

    /**
     * NULL 入参：{@code SHA2(NULL,256)} 与 {@code SHA2('abc',NULL)} 真库(MySQL 8.0.46) 均返回 NULL。
     * 注：MariaDB 10.11 对 {@code SHA2('abc',NULL)} 会把 NULL 长度回退为 256 而非 NULL，属版本 persona 发散；
     * 本实现按北极星主口径对齐 MySQL（见 DEBT-QUEUE 族行残差登记）。
     */
    @Test
    public void sha2NullArgumentReturnsNull() {
        Assertions.assertNull(SHA2Function.sha2(null, 256));
        Assertions.assertNull(SHA2Function.sha2("abc", null));
    }

    /** SHA2 摘要为小写十六进制，长度与位数/4 一致（256→64、512→128）。 */
    @Test
    public void sha2DigestHexWidthMatchesLength() {
        Assertions.assertEquals(64, SHA2Function.sha2("abc", 256).length());
        Assertions.assertEquals(128, SHA2Function.sha2("abc", 512).length());
        Assertions.assertEquals(SHA2Function.sha2("abc", 256).toLowerCase(), SHA2Function.sha2("abc", 256));
    }
}
