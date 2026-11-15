package io.mycat.sqlhandler.config;

import io.mycat.config.KVObject;
import io.mycat.util.JsonUtil;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileKV<T extends KVObject> implements KV<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileKV.class);

    /**
     * MYCAT CHANGE (I-DEBT-132)：原子写临时文件名后缀（{@code <file>.tmp-<时间戳>}），落盘完成后即清。
     */
    private static final String TMP_SUFFIX = ".tmp-";

    /**
     * MYCAT CHANGE (I-DEBT-132)：损坏 JSON 隔离子目录（复用 I-DEBT-98 orphan 目录惯例）。
     */
    private static final String ORPHAN_DIR = "orphan";

    String fileNameTemplate;
    Class<T> aClass;
    Path dir;
    String suffix;

    public FileKV(String fileNameTemplate, Class<T> aClass, Path dir, String suffix) {
        this.fileNameTemplate = fileNameTemplate;
        this.aClass = aClass;
        this.dir = dir;
        this.suffix = suffix;
    }

    @Override
    public Optional< T> get(String key) {
        Path path1 = getPath(key);
        Optional<String> stringOptional = readString(path1);
        // MYCAT CHANGE (I-DEBT-132)：损坏 JSON 不再毒化 get()——WARN+跳过+隔离（与 values() 同口径）
        return stringOptional.map(s -> parseOrNull(path1, s)).filter(java.util.Objects::nonNull);
    }

    @Override
    @SneakyThrows
    public void removeKey(String key) {
        Files.deleteIfExists(getPath(key));
    }

    @NotNull
    private Path getPath(String key) {
        return dir.resolve(key + "." + fileNameTemplate + "." + suffix);
    }

    @Override
    public void put(String key, T value) {
        // MYCAT CHANGE (I-DEBT-132)：落盘改走 temp+move 原子写（原 FileUtils.write 截断重写，
        // 崩溃/磁盘满时留下半截 JSON 毒化下次启动）
        writeFile(JsonUtil.toJson(value), getPath(key));
    }

    @Override
    @SneakyThrows
    public List<T> values() {
        String s = "." + fileNameTemplate + "." + suffix;
        // MYCAT CHANGE (I-DEBT-132)：Files.list Stream 必须 try-with-resources 关闭（原实现泄漏目录
        // 句柄：Windows 上 home 目录无法清理/重命名，Linux fd 泄漏）；损坏 JSON 单文件 WARN+跳过
        // +移入 orphan/ 隔离，不毒化整次装载（复用 I-DEBT-98 orphan 惯例）。
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(i -> i.toString().endsWith(s)).map(i -> {
                Optional<String> s1 = readString(i);
                if (!s1.isPresent()) {
                    return null;
                }
                return parseOrNull(i, s1.get());
            }).filter(java.util.Objects::nonNull).collect(Collectors.toList());
        }
    }

    /**
     * MYCAT CHANGE (I-DEBT-132)：损坏 JSON 处理——解析失败该文件 WARN+按空处理，并移入
     * {@code <dir>/orphan/} 子目录隔离（文件名追加 {@code .orphan-<时间戳>} 防碰撞），
     * 不再毒化本次装载与下次启动；隔离动作失败仅 WARN（尽力而为，值语义=跳过不变）。
     */
    private T parseOrNull(Path path, String json) {
        try {
            return JsonUtil.from(json, aClass);
        } catch (Throwable e) {
            LOGGER.warn("I-DEBT-132 corrupt KV config file [{}]: parse failed ({}), skip and quarantine to orphan/",
                    path, e.getMessage());
            quarantineCorruptFile(path);
            return null;
        }
    }

    @SneakyThrows
    private void quarantineCorruptFile(Path path) {
        try {
            Path orphanDirectory = dir.resolve(ORPHAN_DIR);
            Files.createDirectories(orphanDirectory);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")
                    .format(LocalDateTime.now());
            Path target = orphanDirectory.resolve(path.getFileName() + ".orphan-" + stamp);
            int sequence = 1;
            while (Files.exists(target)) {
                target = orphanDirectory.resolve(path.getFileName() + ".orphan-" + stamp + "-" + (sequence++));
            }
            Files.move(path, target);
        } catch (Throwable quarantineError) {
            LOGGER.warn("I-DEBT-132 quarantine corrupt KV config file [{}] failed: {}",
                    path, quarantineError.toString());
        }
    }

    /**
     * MYCAT CHANGE (I-DEBT-132)：temp+move 原子写——先写 {@code <file>.tmp-<时间戳>} 全量内容，
     * 再 {@code Files.move(ATOMIC_MOVE)} 到目标（同目录 move 原子生效）；ATOMIC_MOVE 不被支持时
     * 降级 REPLACE_EXISTING move；失败路径清理 tmp 半成品后上抛。目标文件要么旧内容要么新内容，
     * 绝不出现截断的中间态。
     */
    @SneakyThrows
    public static void writeFile(String t, Path filePath) {
        Path tmp = filePath.resolveSibling(
                filePath.getFileName() + TMP_SUFFIX + System.nanoTime());
        boolean moved = false;
        try {
            Files.write(tmp, t.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @SneakyThrows
    Optional<String> readString(Path path) {
        if (!Files.exists(path)){
            return Optional.empty();
        }
        return Optional.ofNullable(new String(Files.readAllBytes(path)));
    }
}
