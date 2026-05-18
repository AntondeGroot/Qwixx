package nl.adg.qwixx.e2e.helpers;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

public class ApiHelper {

    private static final String BASE_URL = "http://127.0.0.1:4200";
    private final RestTemplate http = new RestTemplate();

    // ---------- GAME LIFECYCLE ----------

    public String createGame(int nrPlayers) {
        return createGame(nrPlayers, "test-room-" + System.nanoTime(), null);
    }

    public String createGame(int nrPlayers, String roomName) {
        return createGame(nrPlayers, roomName, null);
    }

    /** Creates a game with the given game options (e.g. {@code Map.of("base", "LONGO")}). */
    public String createGame(int nrPlayers, Map<String, Object> gameOptions) {
        return createGame(nrPlayers, "test-room-" + System.nanoTime(), gameOptions);
    }

    public String createGame(int nrPlayers, String roomName, Map<String, Object> gameOptions) {
        // Pass gameOptions in the creation body so createNewGame applies them immediately.
        // (updateLobby only sets proposed UI options; startGame ignores them.)
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("roomName", roomName);
        body.put("maxPlayers", nrPlayers);
        if (gameOptions != null && !gameOptions.isEmpty()) {
            body.put("gameOptions", gameOptions);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> created = post("/games", body, Map.class);
        String sessionId = (String) created.get("sessionId");

        for (int i = 0; i < nrPlayers; i++) {
            post("/games/" + sessionId + "/players",
                    Map.of("id", java.util.UUID.randomUUID().toString(), "name", "player" + i), Map.class);
        }

        post("/games/" + sessionId, null, Void.class);
        return sessionId;
    }

    /** Returns player IDs in join order (lobby order). */
    public List<String> getPlayerIds(String sessionId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players =
                http.getForObject(BASE_URL + "/games/" + sessionId + "/players", List.class);
        return players.stream().map(p -> (String) p.get("id")).toList();
    }

    /**
     * Returns player IDs in game-state order (i.e. the shuffled turn order).
     * Index 0 is the first active player, index 1 the next, and so on.
     * Use this instead of {@link #getPlayerIds} whenever tests depend on turn order.
     */
    @SuppressWarnings("unchecked")
    public List<String> getOrderedPlayerIds(String sessionId) {
        Map<String, Object> state = getGameState(sessionId);
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        return players.stream().map(p -> (String) p.get("id")).toList();
    }

    /** Returns the display name of the player with the given ID. */
    @SuppressWarnings("unchecked")
    public String getPlayerName(String sessionId, String playerId) {
        Map<String, Object> state = getGameState(sessionId);
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        return players.stream()
                .filter(p -> playerId.equals(p.get("id")))
                .map(p -> (String) p.get("name"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
    }

    /** Returns the player ID (UUID) for the player with the given display name. */
    @SuppressWarnings("unchecked")
    public String getPlayerIdByName(String sessionId, String name) {
        Map<String, Object> state = getGameState(sessionId);
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        return players.stream()
                .filter(p -> name.equals(p.get("name")))
                .map(p -> (String) p.get("id"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No player named: " + name));
    }

    // ---------- MOVES ----------

    public void roll(String sessionId, String playerId) {
        makeMove(sessionId, playerId, Map.of("moveType", "ROLL"));
    }

    public void crossCell(String sessionId, String playerId, String rowId, String cellId, boolean useColorDie) {
        makeMove(sessionId, playerId, Map.of(
                "moveType", useColorDie ? "CROSS_COLOR_DIE" : "CROSS_WHITE_WHITE",
                "rowId", rowId,
                "cellId", cellId));
    }

    public void declareLockIntent(String sessionId, String playerId, String rowId) {
        makeMove(sessionId, playerId, Map.of("moveType", "DECLARE_LOCK_INTENT", "rowId", rowId));
    }

    public void crossLock(String sessionId, String playerId, String rowId) {
        makeMove(sessionId, playerId, Map.of("moveType", "CROSS_LOCK", "rowId", rowId));
    }

    public void resetTurn(String sessionId, String playerId) {
        makeMove(sessionId, playerId, Map.of("moveType", "RESET_TURN"));
    }

    public void pass(String sessionId, String playerId) {
        makeMove(sessionId, playerId, Map.of("moveType", "PASS"));
    }

    public void giveUp(String sessionId, String playerId) {
        makeMove(sessionId, playerId, Map.of("moveType", "GIVE_UP"));
    }

    public void takePunishment(String sessionId, String playerId) {
        makeMove(sessionId, playerId, Map.of("moveType", "TAKE_PUNISHMENT"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> makeMove(String sessionId, String playerId, Map<String, Object> request) {
        return post("/moves/" + sessionId + "/" + playerId, request, Map.class);
    }

    // ---------- GAME STATE ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> getGameState(String sessionId) {
        return http.getForObject(BASE_URL + "/gamestates/" + sessionId, Map.class);
    }

    /** Returns the server-assigned UUID for the row at {@code rowIndex} in the given player's sheet layout. */
    @SuppressWarnings("unchecked")
    public String getRowId(String sessionId, String playerId, int rowIndex) {
        Map<String, Object> state = getGameState(sessionId);
        Map<String, Object> layouts = (Map<String, Object>) state.get("sheetLayouts");
        Map<String, Object> layout  = (Map<String, Object>) layouts.get(playerId);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) layout.get("rows");
        return (String) rows.get(rowIndex).get("id");
    }

    // ---------- TEST-ONLY ENDPOINTS (active under the "e2e" Spring profile) ----------

    public void reset() {
        post("/test/reset", null, Void.class);
    }

    public void setCrosses(String sessionId, String playerId, int rowIndex, int count) {
        post("/test/set-crosses/" + sessionId + "/" + playerId + "/" + rowIndex + "/" + count,
                null, Void.class);
    }

    public void setDice(String sessionId, int white1, int white2) {
        post("/test/set-dice/" + sessionId + "/" + white1 + "/" + white2, null, Void.class);
    }

    /** Override one specific colored die, leaving all other dice unchanged. */
    public void setColoredDie(String sessionId, String color, int value) {
        post("/test/set-colored-die/" + sessionId + "/" + color + "/" + value, null, Void.class);
    }

    public void forceFinish(String sessionId) {
        post("/test/force-finish/" + sessionId, null, Void.class);
    }

    public void addClosureRequest(String sessionId, String playerId, String rowColor) {
        post("/test/add-closure-request/" + sessionId + "/" + playerId + "/" + rowColor,
                null, Void.class);
    }

    // ---------- LOBBY ----------

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLobby(String sessionId) {
        return http.getForObject(BASE_URL + "/games/" + sessionId + "/lobby", Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLobbyPlayers(String sessionId) {
        Map<String, Object> lobby = getLobby(sessionId);
        return (List<Map<String, Object>>) lobby.get("players");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getProposedOptions(String sessionId) {
        Map<String, Object> lobby = getLobby(sessionId);
        return (Map<String, Object>) lobby.get("proposedOptions");
    }

    public void updateLobby(String sessionId, Map<String, Object> gameOptions) {
        put("/games/" + sessionId + "/lobby", Map.of("gameOptions", gameOptions));
    }

    public void leaveGame(String sessionId, String playerId) {
        http.delete(BASE_URL + "/games/" + sessionId + "/players/" + playerId);
    }

    public void restartGame(String sessionId) {
        post("/games/" + sessionId + "/restart", null, Void.class);
    }

    public void restartGame(String sessionId, Map<String, Object> gameOptions) {
        post("/games/" + sessionId + "/restart", Map.of("gameOptions", gameOptions), Void.class);
    }

    // ---------- PRIVATE ----------

    private <T> T post(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForObject(BASE_URL + path, new HttpEntity<>(body, headers), responseType);
    }

    private void put(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        http.put(BASE_URL + path, new HttpEntity<>(body, headers));
    }
}
