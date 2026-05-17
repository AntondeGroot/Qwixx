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
        Integer maxValue) {

    public GameOption {
        choices = List.copyOf(choices);
    }

    public static GameOption boolOption(String key, String labelKey, String descriptionKey) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN, "false",
                List.of(), null, null);
    }

    public static GameOption enumOption(String key, String labelKey, String descriptionKey,
                                        String defaultValue, List<String> choices) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.ENUM, defaultValue,
                choices, null, null);
    }

    public static GameOption intOption(String key, String labelKey, String descriptionKey,
                                       String defaultValue, int minValue, int maxValue) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.INTEGER, defaultValue,
                List.of(), minValue, maxValue);
    }
}