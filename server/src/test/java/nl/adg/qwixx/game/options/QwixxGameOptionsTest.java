package nl.adg.qwixx.game.options;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.adg.qwixx.bot.BotStrategy;
import nl.adg.qwixx.state.CardMode;
import org.junit.jupiter.api.Test;

class QwixxGameOptionsTest {

    @Test
    void allReturnsNonEmptyList() {
        assertFalse(QwixxGameOptions.all().isEmpty());
    }

    @Test
    void allIncludesGameModeCardModeRandomOrderExtraRow() {
        List<String> keys = QwixxGameOptions.all().stream().map(GameOption::key).toList();
        assertTrue(keys.contains("gameMode"));
        assertTrue(keys.contains("cardMode"));
        assertTrue(keys.contains("randomOrder"));
        assertTrue(keys.contains("extraRow"));
    }

    @Test
    void gameModeOptionIsEnum() {
        GameOption opt = optionByKey("gameMode");
        assertEquals(OptionType.ENUM, opt.type());
        assertTrue(opt.choices().contains("ONLINE"));
        assertTrue(opt.choices().contains("OFFLINE"));
    }

    @Test
    void cardModeOptionDefaultIsSameCards() {
        assertEquals("SAME_CARDS", optionByKey("cardMode").defaultValue());
    }

    @Test
    void randomOrderOptionIsBoolean() {
        assertEquals(OptionType.BOOLEAN, optionByKey("randomOrder").type());
    }

    @Test
    void applyNullOptionsIsNoop() {
        GameSettings.Builder builder = GameSettings.builder();
        assertDoesNotThrow(() -> QwixxGameOptions.apply(builder, null));
    }

    @Test
    void applyGameModeOffline() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("gameMode", "OFFLINE"));
        assertEquals(GameMode.OFFLINE, builder.build().gameMode());
    }

    @Test
    void applyCardModeDifferentCards() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("cardMode", "DIFFERENT_CARDS"));
        assertEquals(CardMode.DIFFERENT_CARDS, builder.build().cardMode());
    }

    @Test
    void applyRandomOrderTrue() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("randomOrder", true));
        assertTrue(builder.build().randomOrder());
    }

    @Test
    void applyExtraRowTrue() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("extraRow", "true"));
        assertTrue(builder.build().extraRow());
    }

    @Test
    void seeOtherCardsOptionDefaultsToTrue() {
        GameOption opt = optionByKey("seeOtherCards");
        assertEquals(OptionType.BOOLEAN, opt.type());
        assertEquals("true", opt.defaultValue());
    }

    @Test
    void gameSettingsDefaultsSeeOtherCardsToTrue() {
        assertTrue(GameSettings.builder().build().seeOtherCards());
    }

    @Test
    void applySeeOtherCardsFalse() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("seeOtherCards", false));
        assertFalse(builder.build().seeOtherCards());
    }

    @Test
    void applyUnknownKeyIsIgnored() {
        GameSettings.Builder builder = GameSettings.builder();
        assertDoesNotThrow(() -> QwixxGameOptions.apply(builder, Map.of("bogus", "value")));
    }

    @Test
    void applyMultipleOptions() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of(
                "gameMode", "OFFLINE",
                "cardMode", "DIFFERENT_CARDS",
                "randomOrder", "true"
        ));
        GameSettings s = builder.build();
        assertEquals(GameMode.OFFLINE, s.gameMode());
        assertEquals(CardMode.DIFFERENT_CARDS, s.cardMode());
        assertTrue(s.randomOrder());
    }

    // ── randomOrder + bigPoints mutual exclusion ──────────────────────────────

    @Test
    void buildingSettingsWithRandomOrderAndBigPointsThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            GameSettings.builder().randomOrder(true).bigPoints(true).build());
    }

    @Test
    void randomOrderAloneIsValid() {
        assertDoesNotThrow(() -> GameSettings.builder().randomOrder(true).build());
    }

    @Test
    void bigPointsAloneIsValid() {
        assertDoesNotThrow(() -> GameSettings.builder().bigPoints(true).build());
    }

    @Test
    void applyingBothOptionsViaMapThrows() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("randomOrder", true, "bigPoints", true));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    // ── bool() parses both value types and both truth values ──────────────────

    @Test
    void applyBoolFromStringFalseParsesToFalse() {
        // seeOtherCards defaults to true; the String "false" must flip it off. This exercises the
        // Boolean.parseBoolean branch returning the parsed false (not a hard-coded true).
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("seeOtherCards", "false"));
        assertFalse(builder.build().seeOtherCards(), "String \"false\" must parse to false");
    }

    @Test
    void applyBoolFromBooleanTrueStaysTrue() {
        // The instanceof-Boolean branch must return the actual value, so a real Boolean.TRUE stays on.
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("randomOrder", Boolean.TRUE));
        assertTrue(builder.build().randomOrder());
    }

    // ── integer() parses both value types and returns the exact number ────────

    @Test
    void applyBotCountFromNumberReturnsExactValue() {
        // A real Number must be returned via intValue() — exactly 2, not 0.
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("botCount", 2));
        assertEquals(2, builder.build().botCount());
    }

    @Test
    void applyBotCountFromStringReturnsExactValue() {
        // A String must be parsed via Integer.parseInt — exactly 3, not 0.
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("botCount", "3"));
        assertEquals(3, builder.build().botCount());
    }

    @Test
    void applyBotCountNullDefaultsToZero() {
        // A null value takes the "0" default branch of integer() without throwing.
        GameSettings.Builder builder = GameSettings.builder();
        Map<String, Object> options = new HashMap<>();
        options.put("botCount", null);
        QwixxGameOptions.apply(builder, options);
        assertEquals(0, builder.build().botCount());
    }

    // ── toMap() round-trips every setting ─────────────────────────────────────

    @Test
    void toMapReturnsPopulatedMapWithExpectedEntries() {
        GameSettings settings = GameSettings.builder()
                .gameMode(GameMode.OFFLINE)
                .botCount(2)
                .botStrategy(BotStrategy.MOST_WINS)
                .build();

        Map<String, Object> map = QwixxGameOptions.toMap(settings);

        assertFalse(map.isEmpty(), "toMap must serialize the settings, not an empty map");
        assertEquals("STANDARD", map.get("base"));
        assertEquals("OFFLINE", map.get("gameMode"));
        assertEquals(2, map.get("botCount"));
        assertEquals(true, map.get("seeOtherCards"));
        // Non-null strategy branch: its own name is serialized, not the BALANCED fallback.
        assertEquals("MOST_WINS", map.get("botStrategy"));
    }

    @Test
    void toMapUsesBalancedWhenStrategyIsNull() {
        GameSettings settings = GameSettings.builder().botStrategy(null).build();

        Map<String, Object> map = QwixxGameOptions.toMap(settings);

        // Null-strategy branch of the ternary: fall back to BALANCED's name.
        assertEquals(BotStrategy.BALANCED.name(), map.get("botStrategy"));
    }

    private GameOption optionByKey(String key) {
        return QwixxGameOptions.all().stream()
                .filter(o -> o.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("option not found: " + key));
    }
}
