package nl.adg.qwixx.game.options;

import jakarta.annotation.Nullable;
import java.util.Arrays;
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

    // ── Key-derived factories ─────────────────────────────────────────────────
    // Convention: an option's i18n keys follow its key — label "gameOption.<key>" and description
    // "gameOption.<key>Description". These overloads derive both, so the catalog never repeats them.

    public static String labelKeyFor(String key)       { return "gameOption." + key; }
    public static String descriptionKeyFor(String key) { return labelKeyFor(key) + "Description"; }

    public static GameOption boolOption(String key) {
        return boolOption(key, labelKeyFor(key), descriptionKeyFor(key), false);
    }

    public static GameOption boolOption(String key, boolean defaultValue) {
        return boolOption(key, labelKeyFor(key), descriptionKeyFor(key), defaultValue);
    }

    public static GameOption adminBoolOption(String key) {
        return adminBoolOption(key, labelKeyFor(key), descriptionKeyFor(key));
    }

    public static GameOption enumOption(String key, String defaultValue, List<String> choices) {
        return enumOption(key, labelKeyFor(key), descriptionKeyFor(key), defaultValue, choices);
    }

    /** Enum option whose choices are the enum's constants (in declaration order) and whose default is
     *  the given constant — so the caller just passes the enum default, e.g. {@code BaseVariant.STANDARD}. */
    public static <E extends Enum<E>> GameOption enumOption(String key, E defaultValue) {
        List<String> choices = Arrays.stream(defaultValue.getDeclaringClass().getEnumConstants())
                .map(Enum::name).toList();
        return enumOption(key, defaultValue.name(), choices);
    }

    public static GameOption intOption(String key, String defaultValue, int minValue, int maxValue) {
        return intOption(key, labelKeyFor(key), descriptionKeyFor(key), defaultValue, minValue, maxValue);
    }
}
