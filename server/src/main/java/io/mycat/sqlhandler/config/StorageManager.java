package io.mycat.sqlhandler.config;

import io.mycat.ConfigReporter;
import io.mycat.config.KVObject;
import lombok.SneakyThrows;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface StorageManager extends ConfigReporter {
    void register(Class aClass);

    // MYCAT CHANGE (I-DEBT-98): 文件层自愈（孤儿配置隔离）需定位基目录；非文件后端
    // （如 DB 存储）默认空 Optional，调用方跳过文件级隔离，仅依赖运行态 WARN+跳过兜底。
    default Optional<Path> getBaseDirectory() {
        return Optional.empty();
    }

    @SneakyThrows
    default public <T extends KVObject> KV<T> get(Class<T> aClass) {
        T t = aClass.newInstance();
        String path = t.path();
        String fileNameTemplate = t.fileName();
        return get(path, fileNameTemplate, aClass);
    }

    public <T extends KVObject> KV<T> get(String path, String fileNameTemplate, Class<T> aClass);

    Collection<Class> registerClasses();

    void syncFromNet();

    void syncToNet();

    boolean checkConfigConsistency();

    public  Map<String, Map<String,String>> toMap();

    public void write(Map<String, Map<String, String>> map);
}
