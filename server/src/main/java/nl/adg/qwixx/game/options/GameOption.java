package nl.adg.qwixx.game.options;

import jakarta.annotation.Nullable;
import java.util.List;

public record GameOption(
        String key,
        String labelKey,
        String descriptionKey,
        OptionType type,
        String defaultValue,
        List<String> choices,
        @Nullable Integer minValue, // only set for INTEGER options
        @Nullable Integer maxValue,
        boolean adminOnly,
        OptionCategory category,
        List<String> incompatibleWith) {

    public GameOption {
        choices = List.copyOf(choices);
        incompatibleWith = incompatibleWith != null ? List.copyOf(incompatibleWith) : List.of();
    }

    public GameOption withIncompatibleWith(List<String> keys) {
        return new GameOption(key, labelKey, descriptionKey, type, defaultValue,
                choices, minValue, maxValue, adminOnly, category, keys);
    }

    /** Moves this option into a settings-UI group. Factories default to {@link OptionCategory#MODE}. */
    public GameOption withCategory(OptionCategory newCategory) {
        return new GameOption(key, labelKey, descriptionKey, type, defaultValue,
                choices, minValue, maxValue, adminOnly, newCategory, incompatibleWith);
    }

    public static GameOption boolOption(String key, String labelKey, String descriptionKey) {
        return boolOption(key, labelKey, descriptionKey, false);
    }

    public static GameOption boolOption(String key, String labelKey, String descriptionKey,
                                        boolean defaultValue) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN,
                Boolean.toString(defaultValue), List.of(), null, null, false, OptionCategory.MODE, List.of());
    }

    public static GameOption adminBoolOption(String key, String labelKey, String descriptionKey) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN, "false",
                List.of(), null, null, true, OptionCategory.MODE, List.of());
    }

    public static GameOption enumOption(String key, String labelKey, String descriptionKey,
                                        String defaultValue, List<String> choices) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.ENUM, defaultValue,
                choices, null, null, false, OptionCategory.MODE, List.of());
    }

    public static GameOption intOption(String key, String labelKey, String descriptionKey,
                                       String defaultValue, int minValue, int maxValue) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.INTEGER, defaultValue,
                List.of(), minValue, maxValue, false, OptionCategory.MODE, List.of());
    }
}
