package io.mycat.util;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class JsonUtil {

    // MYCAT CHANGE (I-DEBT-21): hint 家族（MigrateHint/BinlogSyncHint/PauseServerHint/...）的
    // build() 走 JsonUtil.toJson(this)，因继承 HintBuilder 的 getCmd() 抽象 getter，Jackson 会把
    // "cmd" 当属性冗余序列化进 body；而服务端 JsonUtil.from(body, XxxHint.class) 反序列化时该类无
    // cmd 写属性（getCmd 为只读计算值），默认 ObjectMapper 的 FAIL_ON_UNKNOWN_PROPERTIES=true 遂抛
    // UnrecognizedPropertyException（MIGRATE hint 报 Unrecognized field "cmd"）。cmd 实际由 hint 头部
    // ("/*+ mycat:{CMD}{json}" 中 { 前缀) 提取、body 里的 cmd 仅冗余。改为容错读端（忽略未知属性），
    // 单点覆盖整个 hint 族，契合"跨版本宽容兼容"主旨；hint body 均为自选字段解析，无需靠"未知即报错"保正确。
    //
    // MYCAT CHANGE (I-DEBT-24): manager hint 常由人手写、非严格 JSON——如 setSqlTimeFilter 的 body
    // "{value:30000}" 字段名未加双引号，默认 ObjectMapper 抛 JsonParseException("was expecting double-quote
    // to start field name")。再开 ALLOW_UNQUOTED_FIELD_NAMES（纯放宽接受、对既有合法 JSON 逐字解析无影响），
    // 与上面容错读端同属"hint 宽容兼容"主旨，单点覆盖 HintHandler 全部 JsonUtil.from(body, Map.class) 解析口。
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    public static Map<String, String> urlSplit(String s) {
        Map<String, String> mapRequest = new HashMap<>(2);
        String strUrlParam = truncateUrlPage(s);
        if (strUrlParam == null) {
            return mapRequest;
        }
        String[] arrSplit = strUrlParam.split("[&]");
        for (String strSplit : arrSplit) {
            String[] arrSplitEqual = strSplit.split("[=]");
            //解析出键值
            if (arrSplitEqual.length > 1) {
                //正确解析
                mapRequest.put(arrSplitEqual[0], arrSplitEqual[1]);
            } else {
                if (!"".equals(arrSplitEqual[0])) {
                    //只有参数没有值，不加入
                    mapRequest.put(arrSplitEqual[0], "");
                }
            }
        }
        return mapRequest;
    }

    private static String truncateUrlPage(String strURL) {
        String strAllParam = null;
        strURL = strURL.trim();
        String[] arrSplit = strURL.split("[?]");
        if (strURL.length() > 1) {
            if (arrSplit.length > 1) {
                for (int i = 1; i < arrSplit.length; i++) {
                    strAllParam = arrSplit[i];
                }
            }
        }
        return strAllParam;
    }

    public static <T> T clone(Object o, Class<T> clazz) {
        return from(toJson(o), clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T from(String text, Class<T> clazz) {
        try {
            return MAPPER.readValue(text, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    public static Optional<String> fromMap(String text, String item) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            Map map = MAPPER.readValue(text, Map.class);
            return Optional.ofNullable(map).map(i -> (String) i.get(item));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
