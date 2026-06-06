package nl.adg.qwixx.game;

import java.util.List;

public record GameOption(
        String key,
        String labelKey,
        String descriptionKey,
        OptionType type,
        String defaultValue,
        List<String> choices,
        Integer minValue,
        Integer maxValue,
        boolean adminOnly,
        List<String> incompatibleWith) {

    public GameOption {
        choices = List.copyOf(choices);
        incompatibleWith = incompatibleWith != null ? List.copyOf(incompatibleWith) : List.of();
    }

    public GameOption withIncompatibleWith(List<String> keys) {
        return new GameOption(key, labelKey, descriptionKey, type, defaultValue,
                choices, minValue, maxValue, adminOnly, keys);
    }

    public static GameOption boolOption(String key, String labelKey, String descriptionKey) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN, "false",
                List.of(), null, null, false, List.of());
    }

    public static GameOption adminBoolOption(String key, String labelKey, String descriptionKey) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN, "false",
                List.of(), null, null, true, List.of());
    }

    public static GameOption enumOption(String key, String labelKey, String descriptionKey,
                                        String defaultValue, List<String> choices) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.ENUM, defaultValue,
                choices, null, null, false, List.of());
    }

    public static GameOption intOption(String key, String labelKey, String descriptionKey,
                                       String defaultValue, int minValue, int maxValue) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.INTEGER, defaultValue,
                List.of(), minValue, maxValue, false, List.of());
    }
}
