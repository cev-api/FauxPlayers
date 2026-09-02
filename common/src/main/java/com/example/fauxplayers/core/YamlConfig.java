package com.example.fauxplayers.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.DumperOptions;

/** Small YAML document store used by the Fabric adapter and shared commands. */
public final class YamlConfig implements PluginConfig.Source {
    private final Map<String, Object> root;

    private YamlConfig(Map<String, Object> root) {
        this.root = root;
    }

    public static YamlConfig load(Path path) throws IOException {
        if (!Files.exists(path)) return new YamlConfig(new LinkedHashMap<>());
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return new YamlConfig(toMap(parsed));
    }

    public static YamlConfig empty() {
        return new YamlConfig(new LinkedHashMap<>());
    }

    @Override
    public Object get(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> mapList(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) return new java.util.ArrayList<>();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
                result.add(copy);
            }
        }
        return result;
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setWidth(120);
        Files.writeString(path, new Yaml(options).dump(root), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> result.put(String.valueOf(key), normalize(entry)));
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?>) return toMap(value);
        if (value instanceof List<?> list) return list.stream().map(YamlConfig::normalize).toList();
        return value;
    }
}
