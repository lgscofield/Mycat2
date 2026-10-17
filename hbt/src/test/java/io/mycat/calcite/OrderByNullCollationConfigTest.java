package io.mycat.calcite;

import org.apache.calcite.config.NullCollation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-DEBT-55 回归锁：Mycat 全链唯一 validator config 出生点的默认 NULL 序
 * 必须钉死为 LOW（MySQL/MariaDB 语义：NULL 最小值），防止未来无声回退到
 * Calcite 连接属性默认 HIGH 导致裸 ORDER BY 的 NULL 排布偏离真库。
 */
public class OrderByNullCollationConfigTest {

    @Test
    public void validatorConfigDefaultNullCollationIsLow() {
        assertEquals(NullCollation.LOW,
                MycatCalciteSupport.INSTANCE.getValidatorConfig().defaultNullCollation(),
                "裸 ORDER BY 默认 NULL 序须为 MySQL 语义 LOW");
    }
}
