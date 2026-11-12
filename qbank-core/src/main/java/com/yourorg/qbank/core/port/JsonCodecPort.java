package com.yourorg.qbank.core.port;

import java.util.List;
import java.util.Map;

/**
 * JSON serialization port isolating qbank-core from Jackson/Gson dependencies.
 */
public interface JsonCodecPort {
    String toJson(Object value);
    <T> T fromJson(String json, Class<T> type);
    <T> List<T> fromJsonList(String json, Class<T> elementType);
    Map<Integer, Integer> fromJsonIntMap(String json);
    List<Integer> fromJsonIntList(String json);
    List<String> fromJsonStringList(String json);
}
