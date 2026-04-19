package nl.adg.qwixx.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameRegistryTest {

    @BeforeEach
    void setUp() {
        GameRegistry.clear();
    }

    @Test
    void createGameReturnsUniqueIds() {
        String id1 = GameRegistry.createGame("room1", 4, GameSettings.builder().build());
        String id2 = GameRegistry.createGame("room2", 4, GameSettings.builder().build());
        assertNotEquals(id1, id2);
    }

    @Test
    void getGameReturnsCreatedSession() {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameSession session = GameRegistry.getGame(id);
        assertNotNull(session);
        assertEquals("room", session.roomName());
    }

    @Test
    void getGameReturnsNullForUnknownId() {
        assertNull(GameRegistry.getGame("no-such-id"));
    }

    @Test
    void removeGameDeletesSession() {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameRegistry.removeGame(id);
        assertNull(GameRegistry.getGame(id));
    }

    @Test
    void getAllGamesReturnsAllSessions() {
        GameRegistry.createGame("a", 4, GameSettings.builder().build());
        GameRegistry.createGame("b", 4, GameSettings.builder().build());
        assertEquals(2, GameRegistry.getAllGames().size());
    }

    // --- presets ---

    @Test
    void standardPresetExists() {
        assertNotNull(GameRegistry.presets().get("standard"));
    }

    @Test
    void longoPresetHasLongoBase() {
        assertEquals(BaseVariant.LONGO, GameRegistry.presets().get("longo").base());
    }

    @Test
    void extraRowPresetHasExtraRowEnabled() {
        assertTrue(GameRegistry.presets().get("extra-row").extraRow());
    }

    @Test
    void randomPresetHasProbabilisticCardMode() {
        assertEquals(nl.adg.qwixx.state.CardMode.PROBABILISTIC,
                GameRegistry.presets().get("random").cardMode());
    }

    @Test
    void createGameFromPresetCreatesSession() {
        String id = GameRegistry.createGameFromPreset("standard", "room", 4);
        assertNotNull(GameRegistry.getGame(id));
    }

    @Test
    void createGameFromUnknownPresetThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> GameRegistry.createGameFromPreset("unknown", "room", 4));
    }
}