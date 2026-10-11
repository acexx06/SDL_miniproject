package com.hms.bench;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** JSON helpers. Parsed documents are plain maps and lists, navigated with {@link #at}. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final JsonMapper COMPACT = JsonMapper.builder().build();

    private Json() {
    }

    public static Object parse(String text) {
        return text == null || text.isBlank() ? null : COMPACT.readValue(text, Object.class);
    }

    public static String write(Object value) {
        return COMPACT.writeValueAsString(value);
    }

    public static void save(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, MAPPER.writeValueAsString(value), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(Path file) throws IOException {
        return (Map<String, Object>) parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Walks into nested maps and lists: {@code at(doc, "user", "id")} or {@code at(doc, "nodes", 0, "port")}. */
    @SuppressWarnings("unchecked")
    public static <T> T at(Object document, Object... path) {
        Object current = document;
        for (Object step : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(step);
            } else if (current instanceof List<?> list && step instanceof Integer index && index < list.size()) {
                current = list.get(index);
            } else {
                return null;
            }
        }
        return (T) current;
    }

    public static double num(Object document, Object... path) {
        Number value = at(document, path);
        return value == null ? 0 : value.doubleValue();
    }
}
