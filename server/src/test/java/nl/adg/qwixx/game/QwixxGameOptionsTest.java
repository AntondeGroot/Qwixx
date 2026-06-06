package nl.adg.qwixx.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
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
    void cardModeOptionDefaultIsDeterministic() {
        assertEquals("DETERMINISTIC", optionByKey("cardMode").defaultValue());
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
    void applyCardModeProbabilistic() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of("cardMode", "PROBABILISTIC"));
        assertEquals(CardMode.PROBABILISTIC, builder.build().cardMode());
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
    void applyUnknownKeyIsIgnored() {
        GameSettings.Builder builder = GameSettings.builder();
        assertDoesNotThrow(() -> QwixxGameOptions.apply(builder, Map.of("bogus", "value")));
    }

    @Test
    void applyMultipleOptions() {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, Map.of(
                "gameMode", "OFFLINE",
                "cardMode", "PROBABILISTIC",
                "randomOrder", "true"
        ));
        GameSettings s = builder.build();
        assertEquals(GameMode.OFFLINE, s.gameMode());
        assertEquals(CardMode.PROBABILISTIC, s.cardMode());
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

    private GameOption optionByKey(String key) {
        return QwixxGameOptions.all().stream()
                .filter(o -> o.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("option not found: " + key));
    }
}
