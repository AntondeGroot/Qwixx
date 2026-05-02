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
        return createGame(nrPlayers, "test-room-" + System.nanoTime());
    }

    public String createGame(int nrPlayers, String roomName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> created = post("/games",
                Map.of("roomName", roomName, "maxPlayers", nrPlayers), Map.class);
        String sessionId = (String) created.get("sessionId");

        for (int i = 0; i < nrPlayers; i++) {
            post("/games/" + sessionId + "/players", Map.of("name", "player" + i), Map.class);
        }

        post("/games/" + sessionId, null, Void.class);
        return sessionId;
    }

    public List<String> getPlayerIds(String sessionId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players =
                http.getForObject(BASE_URL + "/games/" + sessionId + "/players", List.class);
        return players.stream().map(p -> (String) p.get("id")).toList();
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

    // ---------- PRIVATE ----------

    private <T> T post(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForObject(BASE_URL + path, new HttpEntity<>(body, headers), responseType);
    }
}
