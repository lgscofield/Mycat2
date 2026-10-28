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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要算法到 MySQL 返回值形态的小写十六进制串。
 *
 * <p>MYCAT ADDITION（I-DEBT-32-FAMILY 加密/哈希族）：{@code MessageDigest} 非线程安全，
 * 故每次调用新建实例（与 MySQL 每表达式一次摘要同构）；字节序列固定按 UTF-8 取，
 * 以对齐真库在 utf8mb4 连接下的行为。
 */
final class DigestHex {
    private DigestHex() {
    }

    static String digestHex(String algorithm, String text) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求 MD5/SHA-1 必然存在; 走到这里说明运行环境残缺, 不应静默降级。
            throw new IllegalStateException("digest algorithm unavailable: " + algorithm, e);
        }
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
