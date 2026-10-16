package io.mycat;

import io.mycat.prototypeserver.mysql.VisualTableHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1 验证：静态恒空标记能正确区分恒空仿表（COLUMN_STATISTICS/FILES）
 * 与含实数据仿表（ENGINES）。
 */
public class EmptyVisualTableMarkerTest {

    @Test
    public void columnStatisticsIsStaticallyEmpty() {
        VisualTableHandler handler = MysqlMetadataManager.COLUMN_STATISTICS_TABLE_HANDLER;
        assertTrue(handler.isStaticallyEmpty(),
                "COLUMN_STATISTICS 用 () -> Observable.empty() 构造，应标记为恒空");
    }

    @Test
    public void fileTableIsStaticallyEmpty() {
        VisualTableHandler handler = MysqlMetadataManager.FILE_TABLE_HANDLER;
        assertTrue(handler.isStaticallyEmpty(),
                "FILES 用 () -> Observable.empty() 构造，应标记为恒空");
    }

    @Test
    public void enginesIsNotStaticallyEmpty() {
        VisualTableHandler handler = MysqlMetadataManager.ENGINES_TABLE_HANDLER;
        assertFalse(handler.isStaticallyEmpty(),
                "ENGINES 有实数据行，不应标记为恒空");
    }
}
