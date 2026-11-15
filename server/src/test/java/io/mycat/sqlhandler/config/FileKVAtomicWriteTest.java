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
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.sqlhandler.config;

import io.mycat.config.SequenceConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * I-DEBT-132：{@link FileKV} 落盘非原子写 + 资源泄漏 + 损坏 JSON 毒化离线锁。
 *
 * <p>三债：
 * <ol>
 *   <li>{@code put→writeFile} 用 {@code FileUtils.write} 截断重写——进程崩溃/断电/磁盘满时
 *       配置文件停在半截 JSON，下次启动 {@code JsonUtil.from} 抛毒化。修：temp+move 原子写
 *       （{@code <file>.tmp-<ts>} → {@code Files.move(ATOMIC_MOVE)}，失败清理 tmp）。</li>
 *   <li>{@code values()} 的 {@code Files.list} Stream 未关闭——Windows 上目录句柄常驻，
 *       home 目录无法清理/重命名；Linux fd 泄漏。</li>
 *   <li>{@code values()}/{@code get()} 遇损坏 JSON 直接抛——单文件损坏毒化整次装载。
 *       修：该文件 WARN+跳过+移入 {@code <dir>/orphan/} 子目录隔离（复用 I-DEBT-98 orphan 惯例），
 *       其余文件照常装载。</li>
 * </ol>
 *
 * <p>离线锁全部走临时目录 + 纯文件操作，不连库、不起实例。
 */
public class FileKVAtomicWriteTest {

    @TempDir
    Path home;

    private static final String TEMPLATE = "sequence";
    private static final String SUFFIX = "json";

    private FileKV<SequenceConfig> newFileKV(Path dir) {
        return new FileKV<>(TEMPLATE, SequenceConfig.class, dir, SUFFIX);
    }

    private static SequenceConfig seq(String name, String start) {
        SequenceConfig config = new SequenceConfig();
        config.setName(name);
        config.setStartWith(start);
        return config;
    }

    private static Path fileOf(Path dir, String key) {
        return dir.resolve(key + "." + TEMPLATE + "." + SUFFIX);
    }

    @Test
    void putGetRoundtripUnchanged() throws Exception {
        FileKV<SequenceConfig> kv = newFileKV(home);
        kv.put("s1", seq("s1", "100"));

        Path file = fileOf(home, "s1");
        Assertions.assertTrue(Files.exists(file), "put 后文件必须存在");
        Assertions.assertFalse(Files.readString(file).contains("tmp-"), "文件内容即 JSON");

        Optional<SequenceConfig> got = kv.get("s1");
        Assertions.assertTrue(got.isPresent());
        Assertions.assertEquals("s1", got.get().getName());
        Assertions.assertEquals("100", got.get().getStartWith());

        // 原子写残留检查：落盘完成后目录内不得有 .tmp-* 半成品
        Assertions.assertEquals(0, listTmpResidue(home).size(),
                "原子写序列（先 tmp 后 move）完成后不得残留 tmp 文件");
    }

    @Test
    void overwriteReplacesContentAtomically() throws Exception {
        FileKV<SequenceConfig> kv = newFileKV(home);
        kv.put("s1", seq("s1", "1"));
        kv.put("s1", seq("s1", "2"));

        Assertions.assertEquals("2", kv.get("s1").get().getStartWith(), "覆盖写后内容=新值");
        Assertions.assertEquals(1, kv.values().size(), "覆盖写不得产生重复文件");
        Assertions.assertEquals(0, listTmpResidue(home).size(), "覆盖写不得残留 tmp 文件");
    }

    @Test
    void valuesStreamClosed_directoryRenamableAfterValues() throws Exception {
        Path dir = home.resolve("kvdir");
        Files.createDirectories(dir);
        FileKV<SequenceConfig> kv = newFileKV(dir);
        kv.put("s1", seq("s1", "1"));
        kv.values();

        // Files.list Stream 未关闭时 Windows 持目录句柄：重命名必失败。
        // 该格 RED（现 values() 泄漏 Stream）→ 修后（try-with-resources）重命名成功。
        Path renamed = home.resolve("kvdir-moved");
        Assertions.assertDoesNotThrow(() -> Files.move(dir, renamed),
                "values() 后目录必须可重命名（Files.list Stream 必须关闭，不得泄漏目录句柄）");
    }

    @Test
    void corruptJsonSkippedAndQuarantined() throws Exception {
        FileKV<SequenceConfig> kv = newFileKV(home);
        kv.put("good1", seq("good1", "1"));
        kv.put("good2", seq("good2", "2"));
        Files.write(fileOf(home, "bad"), "{ not-json!!!".getBytes(StandardCharsets.UTF_8));

        // RED（现行为）：values() 直接抛 JsonSyntaxException/异常——单文件损坏毒化整次装载
        List<SequenceConfig> values = Assertions.assertDoesNotThrow(kv::values,
                "损坏 JSON 文件必须 WARN+跳过，不得毒化 values() 整次装载");
        Assertions.assertEquals(2, values.size(), "有效文件照常装载，损坏文件跳过");
        Assertions.assertTrue(values.stream().allMatch(c -> c.getName().startsWith("good")));

        // 损坏文件被移入 orphan 子目录隔离（复用 I-DEBT-98 orphan 惯例），不再毒化下次装载
        Path orphan = home.resolve("orphan");
        Assertions.assertTrue(Files.exists(orphan), "orphan 隔离目录必须存在");
        try (DirectoryStream<Path> orphanFiles = Files.newDirectoryStream(orphan,
                p -> p.getFileName().toString().startsWith("bad."))) {
            Assertions.assertTrue(orphanFiles.iterator().hasNext(), "损坏文件必须移入 orphan/ 隔离");
        }
        Assertions.assertFalse(Files.exists(fileOf(home, "bad")), "原损坏文件位置不再保留");
        Assertions.assertTrue(kv.get("good1").isPresent(), "get() 正常路径不受影响");
    }

    @Test
    void failedAtomicWriteLeavesNoTmpResidue() throws Exception {
        FileKV<SequenceConfig> kv = newFileKV(home);
        // 目标路径被一个非空目录占据 → move 必失败（异常须上抛，不静默吞）
        Path occupied = fileOf(home, "blocked");
        Files.createDirectories(occupied);
        Files.write(occupied.resolve("keep.txt"), "x".getBytes(StandardCharsets.UTF_8));

        Assertions.assertThrows(Exception.class, () -> kv.put("blocked", seq("blocked", "1")),
                "落盘失败必须上抛（不得静默丢配置）");
        Assertions.assertTrue(Files.exists(occupied.resolve("keep.txt")), "被占目标不得被破坏");
        Assertions.assertEquals(0, listTmpResidue(home).size(),
                "失败路径必须清理 .tmp-* 半成品（temp+move 原子写语义）");
    }

    private static List<Path> listTmpResidue(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*tmp-*")) {
            return java.util.stream.StreamSupport.stream(stream.spliterator(), false)
                    .collect(Collectors.toList());
        }
    }
}
