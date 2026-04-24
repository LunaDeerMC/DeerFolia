package cn.lunadeer.mc.deerfolia.afknetwork;

import java.util.Locale;
import java.util.Optional;

public enum AfkSuppressibleCategory {
    CHUNK_STREAM("chunk-stream"),
    BLOCK_UPDATES("block-updates"),
    ENTITY_STREAM("entity-stream"),
    WORLD_EFFECTS("world-effects"),
    UI_STREAM("ui-stream");

    private final String configKey;

    AfkSuppressibleCategory(final String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return this.configKey;
    }

    public static Optional<AfkSuppressibleCategory> fromConfigKey(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        final String normalized = value.toLowerCase(Locale.ROOT).trim();
        for (final AfkSuppressibleCategory category : values()) {
            if (category.configKey.equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
