package nl.adg.qwixx.game;

import java.util.List;

public record GameOption(
        String key,
        String label,
        String description,
        OptionType type,
        String defaultValue,
        List<String> choices) {

    public static GameOption boolOption(String key, String label, String description) {
        return new GameOption(key, label, description, OptionType.BOOLEAN, "false", List.of());
    }

    public static GameOption enumOption(String key, String label, String description,
                                        String defaultValue, List<String> choices) {
        return new GameOption(key, label, description, OptionType.ENUM, defaultValue, choices);
    }
}