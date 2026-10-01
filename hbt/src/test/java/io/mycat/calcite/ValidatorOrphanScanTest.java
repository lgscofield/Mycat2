/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-3 Task 4：validator 孤儿钩子扫描器。
 *
 * <p>删掉 hbt 里的 {@code org.apache.calcite.sql.validate.SqlValidatorImpl} 副本后，Mycat 的 validator
 * 子类（命名类 {@link MycatSqlValidator} 与 {@link DrdsRunnerHelper} 里 {@code getSqlValidator} 的匿名子类）
 * 改为直接继承 calcite-core fork。危险面：某个 {@code protected}/{@code public} 方法当初是为了覆写"副本独有"
 * 的方法而写，副本一删，fork 里查无同签名覆写目标 → 该方法变成永不被调用的"孤儿钩子"，静默失效。
 *
 * <p>判据（对每个子类 C 的每个声明方法 m）：m 为 public/protected、非 static/bridge/synthetic，且在 C 的
 * 父类链（不含 C、不含 Object）里找不到同名同参数表的方法 → 记为孤儿候选。测试断言候选清单为空。
 *
 * <p>与计划 Step 1 的 javap 方案语义等价，但改为测试 JVM 内反射：无需拼 calcite-core jar 路径、无需起外部
 * 进程，确定可跑（见账本 Ruling）。
 */
class ValidatorOrphanScanTest {

    private static final String VALIDATOR_FQCN = "org.apache.calcite.sql.validate.SqlValidatorImpl";

    @Test
    void noOrphanHooksAfterCopyRemoval() throws Exception {
        List<Class<?>> subclasses = collectValidatorSubclasses();
        Assertions.assertFalse(subclasses.isEmpty(), "至少要发现一个 validator 子类，否则扫描器没在扫东西");

        List<String> orphans = new ArrayList<>();
        for (Class<?> c : subclasses) {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) {
                    continue; // private 方法不是覆写钩子，天然排除
                }
                if (hasSuperTarget(c, m)) {
                    continue; // 父类链有同签名 → 真覆写，非孤儿
                }
                orphans.add(c.getName() + "#" + m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }

        for (String o : orphans) {
            System.out.println("ORPHAN HOOK CANDIDATE: " + o);
        }
        Assertions.assertTrue(orphans.isEmpty(),
                "发现 " + orphans.size() + " 个孤儿钩子（fork 无同签名覆写目标，删副本后将静默失效）：" + orphans);
    }

    /** 父类链（不含自身、不含 Object）里是否存在同名同参数表的方法。 */
    private boolean hasSuperTarget(Class<?> c, Method m) {
        for (Class<?> s = c.getSuperclass(); s != null && s != Object.class; s = s.getSuperclass()) {
            try {
                s.getDeclaredMethod(m.getName(), m.getParameterTypes());
                return true;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        return false;
    }

    /** 命名子类 + DrdsRunnerHelper 的匿名子类（从 CodeSource 目录里 glob 类文件发现）。 */
    private List<Class<?>> collectValidatorSubclasses() throws Exception {
        List<Class<?>> result = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // 1) 命名子类
        Class<?> named = MycatSqlValidator.class;
        if (isValidatorSubclass(named)) {
            result.add(named);
        }

        // 2) DrdsRunnerHelper 的匿名子类：在其 .class 所在目录里找 DrdsRunnerHelper$*.class
        Class<?> host = DrdsRunnerHelper.class;
        URL src = host.getProtectionDomain().getCodeSource().getLocation();
        File dir = new File(src.toURI());
        String pkg = host.getName().substring(0, host.getName().lastIndexOf('.') + 1).replace('.', File.separatorChar);
        File pkgDir = new File(dir, pkg);
        String simpleHost = host.getName().substring(host.getName().lastIndexOf('.') + 1);
        File[] parts = pkgDir.listFiles((d, n) -> n.startsWith(simpleHost + "$") && n.endsWith(".class"));
        if (parts != null) {
            for (File f : parts) {
                String cls = host.getName().substring(0, host.getName().lastIndexOf('.') + 1)
                        + f.getName().substring(0, f.getName().length() - ".class".length());
                try {
                    Class<?> k = Class.forName(cls, false, cl);
                    if (isValidatorSubclass(k)) {
                        result.add(k);
                    }
                } catch (Throwable ignored) {
                    // 非本类或无法加载：跳过
                }
            }
        }
        return result;
    }

    private boolean isValidatorSubclass(Class<?> c) {
        for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
            if (VALIDATOR_FQCN.equals(s.getName())) {
                return true;
            }
        }
        return false;
    }
}
