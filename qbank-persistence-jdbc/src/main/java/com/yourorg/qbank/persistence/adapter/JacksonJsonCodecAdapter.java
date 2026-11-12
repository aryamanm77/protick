package com.yourorg.qbank.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yourorg.qbank.core.port.JsonCodecPort;

import java.util.List;
import java.util.Map;

public class JacksonJsonCodecAdapter implements JsonCodecPort {

    private final ObjectMapper mapper;

    public JacksonJsonCodecAdapter() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON into " + type.getSimpleName(), e);
        }
    }

    @Override
    public <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            var javaType = mapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return mapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON list of " + elementType.getSimpleName(), e);
        }
    }

    @Override
    public Map<Integer, Integer> fromJsonIntMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize integer map", e);
        }
    }

    @Override
    public List<Integer> fromJsonIntList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize integer list", e);
        }
    }

    @Override
    public List<String> fromJsonStringList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize string list", e);
        }
    }
}
