package com.example.fauxplayers.fabric;

import com.example.fauxplayers.core.YamlConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

/** Stores and reuses the local server's join and leave message components. */
final class FabricMessageFormat {
    private static final String PLACEHOLDER = "{name}";

    private final Path path;
    private final Consumer<String> warning;
    private Template join;
    private Template leave;

    FabricMessageFormat(Path path, Consumer<String> warning) {
        this.path = path;
        this.warning = warning;
    }

    void load() {
        try {
            YamlConfig cache = YamlConfig.load(path);
            join = read(cache, "join");
            leave = read(cache, "leave");
        } catch (IOException error) {
            warning.accept("Unable to load message-format.yml: " + error.getMessage());
        }
    }

    void observeJoin(ServerPlayer player, Component message) {
        Template observed = observe(player, message);
        if (observed != null) {
            join = observed;
            save();
        }
    }

    void observeLeave(ServerPlayer player, Component message) {
        Template observed = observe(player, message);
        if (observed != null) {
            leave = observed;
            save();
        }
    }

    Component renderJoin(String name) {
        return render(join, name);
    }

    Component renderLeave(String name) {
        return render(leave, name);
    }

    private Template observe(ServerPlayer player, Component message) {
        List<String> names = new ArrayList<>();
        names.add(player.getGameProfile().name());
        String displayName = player.getDisplayName().getString();
        if (!displayName.isBlank()) names.add(displayName);
        Component template = replace(message, names, PLACEHOLDER);
        String json = serialize(template);
        return json == null ? null : new Template(template, json);
    }

    private void save() {
        try {
            YamlConfig cache = YamlConfig.empty();
            write(cache, "join", join);
            write(cache, "leave", leave);
            cache.save(path);
        } catch (IOException error) {
            warning.accept("Unable to save message-format.yml: " + error.getMessage());
        }
    }

    private static void write(YamlConfig cache, String path, Template template) {
        cache.set(path + ".observed", template != null);
        if (template != null) cache.set(path + ".template", template.json());
    }

    private Template read(YamlConfig cache, String path) {
        if (!Boolean.parseBoolean(String.valueOf(cache.get(path + ".observed")))) return null;
        Object value = cache.get(path + ".template");
        if (value == null) return null;
        String json = String.valueOf(value);
        Component component = deserialize(json);
        return component == null ? null : new Template(component, json);
    }

    private static Component render(Template template, String name) {
        return template == null ? null : replace(template.component(), List.of(PLACEHOLDER), name);
    }

    private static String serialize(Component component) {
        try {
            JsonElement json = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component)
                    .getOrThrow(error -> new IllegalArgumentException(error));
            return json.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Component deserialize(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element)
                    .result().orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Component replace(Component source, List<String> originals, String replacement) {
        ComponentContents contents = source.getContents();
        MutableComponent result;
        if (contents instanceof PlainTextContents plain) {
            result = Component.literal(replaceText(plain.text(), originals, replacement));
        } else if (contents instanceof TranslatableContents translatable) {
            Object[] arguments = new Object[translatable.getArgs().length];
            for (int i = 0; i < arguments.length; i++) {
                Object argument = translatable.getArgs()[i];
                if (argument instanceof Component component) {
                    arguments[i] = replace(component, originals, replacement);
                } else if (argument instanceof String text) {
                    arguments[i] = replaceText(text, originals, replacement);
                } else {
                    arguments[i] = argument;
                }
            }
            result = translatable.getFallback() == null
                    ? Component.translatable(translatable.getKey(), arguments)
                    : Component.translatableWithFallback(translatable.getKey(),
                            translatable.getFallback(), arguments);
        } else {
            result = MutableComponent.create(contents);
        }
        result.setStyle(source.getStyle());
        for (Component sibling : source.getSiblings()) {
            result.append(replace(sibling, originals, replacement));
        }
        return result;
    }

    private static String replaceText(String text, List<String> originals, String replacement) {
        String result = text;
        for (String original : originals) {
            if (original != null && !original.isBlank()) result = result.replace(original, replacement);
        }
        return result;
    }

    private record Template(Component component, String json) {
    }
}
