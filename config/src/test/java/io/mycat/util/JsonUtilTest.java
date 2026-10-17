package io.mycat.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I-DEBT-7C 收口配套：JsonUtil 容错读端补 ALLOW_TRAILING_COMMA（I-DEBT-21/24 同族第三缝）。
 *
 * <p>历史回归：0735b13d3「fastjson→Jackson 迁移」把 hint/分片 function properties 的解析口
 * 从 fastjson（天然容忍尾逗号）换成 Jackson 严格模式，人写配置里的尾逗号
 * （如 {"tableNum":"2",}）从可解析变为 JsonParseException——对客户端/配置作者的兼容面收窄。
 * 本锁钉回该宽容度：尾逗号对象/数组接受、既有严格 JSON 逐字解析不变。
 */
public class JsonUtilTest {

    @Test
    public void trailingCommaInObjectIsAccepted() {
        // RED 复现点：HashFunctionTest#testHashTableId  fixture 即此形态（末属性后逗号+换行+}）
        Map map = JsonUtil.from("{\n\"tableNum\":\"2\",\n\"storeNum\":1,\n}", Map.class);
        assertEquals("2", map.get("tableNum"));
        assertEquals(1, map.get("storeNum"));
    }

    @Test
    public void trailingCommaInArrayIsAccepted() {
        int[] arr = JsonUtil.from("[1,2,3,]", int[].class);
        assertEquals(3, arr.length);
        assertEquals(3, arr[2]);
    }

    @Test
    public void strictJsonStillParsesVerbatim() {
        // 护栏：合法 JSON 不受放宽影响
        Map map = JsonUtil.from("{\"a\":\"1\"}", Map.class);
        assertEquals("1", map.get("a"));
    }

    @Test
    public void genuinelyBrokenJsonStillRejected() {
        // 护栏：容错只放形态、不吞真错误——缺值冒号形态必须仍抛
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> JsonUtil.from("{\"a\":}", Map.class));
    }
}
