package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.api.GamesApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GamesApiController.class)
@Import({GamesApiDelegateImpl.class, GlobalExceptionHandler.class, SseEmitterRegistry.class, LobbyController.class})
class GamesApiDelegateImplTest {

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        GameRegistry.clear();
    }

    // --- POST /games ---

    @Test
    void createGameReturns201WithSessionId() throws Exception {
        mvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomName":"room1","maxPlayers":4}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    @Test
    void createGameWithOptionsAppliesThemCorrectly() throws Exception {
        mvc.perform(post("/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomName":"room","maxPlayers":2,"gameOptions":{"gameMode":"OFFLINE"}}
                                """))
                .andExpect(status().isCreated());
    }

    // --- GET /games ---

    @Test
    void getAllGamesReturnsEmptyListInitially() throws Exception {
        mvc.perform(get("/games"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getAllGamesReturnsCreatedGame() throws Exception {
        GameRegistry.createGame("lobby", 4, GameSettings.builder().build());

        mvc.perform(get("/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomName").value("lobby"))
                .andExpect(jsonPath("$[0].status").value("waiting"));
    }

    // --- GET /games/{sessionId} ---

    @Test
    void getGameReturns200ForKnownSession() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());

        mvc.perform(get("/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.roomName").value("room"))
                .andExpect(jsonPath("$.maxNrPlayers").value(4));
    }

    @Test
    void getGameReturns404ForUnknownSession() throws Exception {
        mvc.perform(get("/games/no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // --- POST /games/{sessionId} ---

    @Test
    void startGameReturns200WhenSessionHasPlayers() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameRegistry.getGame(id).addPlayer(Player.of("Alice"));

        mvc.perform(post("/games/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void startGameReturns404ForUnknownSession() throws Exception {
        mvc.perform(post("/games/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startGameReturns409WhenAlreadyStarted() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameRegistry.getGame(id).addPlayer(Player.of("Alice"));
        GameRegistry.getGame(id).start();

        mvc.perform(post("/games/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    // --- DELETE /games/{sessionId} ---

    @Test
    void stopGameReturns204AndRemovesSession() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());

        mvc.perform(delete("/games/{id}", id))
                .andExpect(status().isNoContent());

        mvc.perform(get("/games/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopGameReturns404ForUnknownSession() throws Exception {
        mvc.perform(delete("/games/ghost"))
                .andExpect(status().isNotFound());
    }

    // --- POST /games/{sessionId}/players ---

    @Test
    void addPlayerReturns201WithPlayerId() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());

        mvc.perform(post("/games/{id}/players", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"00000000-0000-0000-0000-000000000001","name":"Bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").isNotEmpty());
    }

    @Test
    void addPlayerReturns404ForUnknownSession() throws Exception {
        mvc.perform(post("/games/ghost/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"00000000-0000-0000-0000-000000000001","name":"Bob"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void addPlayerReturns409WhenGameAlreadyStarted() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameRegistry.getGame(id).addPlayer(Player.of("Alice"));
        GameRegistry.getGame(id).start();

        mvc.perform(post("/games/{id}/players", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"00000000-0000-0000-0000-000000000001","name":"Bob"}
                                """))
                .andExpect(status().isConflict());
    }

    // --- GET /games/{sessionId}/players ---

    @Test
    void getAllPlayersReturnsAddedPlayers() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        GameRegistry.getGame(id).addPlayer(Player.of("Alice"));
        GameRegistry.getGame(id).addPlayer(Player.of("Bob"));

        mvc.perform(get("/games/{id}/players", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // --- DELETE /games/{sessionId}/players/{playerId} ---

    @Test
    void leaveGameReturns204() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        Player alice = Player.of("Alice");
        GameRegistry.getGame(id).addPlayer(alice);

        mvc.perform(delete("/games/{id}/players/{pid}", id, alice.id().toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    void leaveGameReturns404WhenSessionNotFound() throws Exception {
        mvc.perform(delete("/games/ghost/players/some-player"))
                .andExpect(status().isNotFound());
    }

    @Test
    void leaveGameReturns404WhenPlayerNotInSession() throws Exception {
        String id = GameRegistry.createGame("room", 4, GameSettings.builder().build());

        mvc.perform(delete("/games/{id}/players/{pid}", id,
                        "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    // --- GET /games/{sessionId}/scores ---

    @Test
    void getScoresReturns404ForUnknownSession() throws Exception {
        mvc.perform(get("/games/ghost/scores"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getScoresReturns409WhenGameNotFinished() throws Exception {
        String id = GameRegistry.createGame("room", 2, GameSettings.builder().build());
        Player alice = Player.of("Alice");
        GameRegistry.getGame(id).addPlayer(alice);
        GameRegistry.getGame(id).start();

        mvc.perform(get("/games/{id}/scores", id))
                .andExpect(status().isConflict());
    }

    @Test
    void getScoresReturns200WithScoreDataAfterGameOver() throws Exception {
        nl.adg.qwixx.game.GameSettings settings = nl.adg.qwixx.game.GameSettings.builder()
                .gameMode(nl.adg.qwixx.game.GameMode.OFFLINE).build();
        String id = GameRegistry.createGame("room", 2, settings);
        Player alice = Player.of("Alice");
        GameRegistry.getGame(id).addPlayer(alice);
        GameRegistry.getGame(id).start();

        // 4 punishments trigger game over in offline mode
        nl.adg.qwixx.game.GameSession session = GameRegistry.getGame(id);
        for (int i = 0; i < 4; i++) {
            session.applyAction(new nl.adg.qwixx.action.TakePunishmentAction(alice.id()));
        }

        mvc.perform(get("/games/{id}/scores", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + alice.id() + "'].total").isNumber())
                .andExpect(jsonPath("$['" + alice.id() + "'].punishmentPoints").value(-20));
    }
}