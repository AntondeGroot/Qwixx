package nl.adg.qwixx.game;

import nl.adg.qwixx.state.CardMode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GameRegistry {

    private static final Map<String, GameSettings> PRESETS = Map.of(
        "standard",  GameSettings.builder().base(BaseVariant.STANDARD).build(),
        "longo",     GameSettings.builder().base(BaseVariant.LONGO).build(),
        "extra-row", GameSettings.builder().base(BaseVariant.STANDARD).extraRow(true).build(),
        "random",    GameSettings.builder().base(BaseVariant.STANDARD).randomOrder(true)
                         .cardMode(CardMode.PROBABILISTIC).build()
    );

    private static final Map<String, GameSession> SESSIONS = new ConcurrentHashMap<>();

    private GameRegistry() {}

    public static String createGame(String roomName, int maxPlayers, GameSettings settings) {
        String id = UUID.randomUUID().toString();
        SESSIONS.put(id, new GameSession(id, roomName, maxPlayers, settings));
        return id;
    }

    public static String createGameFromPreset(String presetName, String roomName, int maxPlayers) {
        GameSettings settings = PRESETS.get(presetName);
        if (settings == null) throw new IllegalArgumentException("unknown preset: " + presetName);
        return createGame(roomName, maxPlayers, settings);
    }

    public static GameSession getGame(String sessionId) {
        return SESSIONS.get(sessionId);
    }

    public static List<GameSession> getAllGames() {
        return List.copyOf(SESSIONS.values());
    }

    public static void removeGame(String sessionId) {
        SESSIONS.remove(sessionId);
    }

    public static Map<String, GameSettings> presets() {
        return PRESETS;
    }

    // visible for testing
    public static void clear() {
        SESSIONS.clear();
    }
}