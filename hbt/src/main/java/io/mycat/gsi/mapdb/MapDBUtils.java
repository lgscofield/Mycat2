package io.mycat.gsi.mapdb;

import com.fasterxml.jackson.databind.ObjectMapper;

public class MapDBUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("rawtypes")
    public static Object cast(Object value, Class javaClass) {
        return MAPPER.convertValue(value, javaClass);
    }
}
