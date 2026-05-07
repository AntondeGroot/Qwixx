package nl.adg.qwixx.game;

import java.util.List;

public record GameOption(
        String key,
        String labelKey,
        String descriptionKey,
        OptionType type,
        String defaultValue,
        List<String> choices) {

    public GameOption {
        choices = List.copyOf(choices);
    }

    public static GameOption boolOption(String key, String labelKey, String descriptionKey) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.BOOLEAN, "false", List.of());
    }

    public static GameOption enumOption(String key, String labelKey, String descriptionKey,
                                        String defaultValue, List<String> choices) {
        return new GameOption(key, labelKey, descriptionKey, OptionType.ENUM, defaultValue, choices);
    }
}