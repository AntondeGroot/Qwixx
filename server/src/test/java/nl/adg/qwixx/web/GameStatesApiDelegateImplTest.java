package nl.adg.qwixx.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.generated.api.GamestatesApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = GamestatesApiController.class)
@Import({GameStatesApiDelegateImpl.class, GlobalExceptionHandler.class})
class GameStatesApiDelegateImplTest {

    @Autowired
    MockMvc mvc;

    String sessionId;
    Player alice;

    @BeforeEach
    void setUp() {
        GameRegistry.clear();
        sessionId = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        alice = Player.of("Alice");
        GameRegistry.getGame(sessionId).addPlayer(alice);
        GameRegistry.getGame(sessionId).start();
    }

    @Test
    void getGameStateReturns200WithFullState() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.gameOver").value(false))
                .andExpect(jsonPath("$.turnState.phase").value("ROLL"))
                .andExpect(jsonPath("$.players[0].name").value("Alice"));
    }

    @Test
    void getGameStateResponseIsNeverCached() throws Exception {
        // Cache-Control: no-store must be present on every response so that nginx
        // and browsers cannot serve stale state to passive players (e.g. hiding the
        // lock-intent modal because closureNotifications was cached away).
        mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getGameState304IsAlsoNeverCached() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId)
                        .param("stateVersion", "0"))
                .andExpect(status().isNotModified())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getGameStateReturns304WhenVersionMatches() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId)
                        .param("stateVersion", "0"))
                .andExpect(status().isNotModified());
    }

    @Test
    void getGameStateReturns200WhenVersionDiffers() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId)
                        .param("stateVersion", "99"))
                .andExpect(status().isOk());
    }

    @Test
    void getGameStateReturns404ForUnknownSession() throws Exception {
        mvc.perform(get("/gamestates/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getGameStateReturns409ForNotStartedGame() throws Exception {
        String notStarted = GameRegistry.createGame("waiting", 4, GameSettings.builder().build());
        mvc.perform(get("/gamestates/{sid}", notStarted))
                .andExpect(status().isConflict());
    }

    @Test
    void getGameStateIncludesSheetProgressForPlayers() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetProgress").isMap())
                .andExpect(jsonPath("$.sheetProgress['" + alice.id() + "'].punishments").value(0));
    }

    @Test
    void getGameStateIncludesActiveDiceColors() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeDiceColors").isArray());
    }

    @Test
    void getGameStateIncludesSheetLayoutsWithCells() throws Exception {
        mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sheetLayouts").isMap())
                .andExpect(jsonPath("$.sheetLayouts['" + alice.id() + "'].rows").isArray())
                .andExpect(jsonPath("$.sheetLayouts['" + alice.id() + "'].rows[0].cells").isArray())
                .andExpect(jsonPath("$.sheetLayouts['" + alice.id() + "'].rows[0].lock").exists())
                .andExpect(jsonPath("$.sheetLayouts['" + alice.id() + "'].rows[0].cells[0].displayValue").exists());
    }

    @Test
    void sheetProgressRowStateKeysMatchLayoutRowIds() throws Exception {
        var result = mvc.perform(get("/gamestates/{sid}", sessionId))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);

        com.fasterxml.jackson.databind.JsonNode layoutRows =
                root.path("sheetLayouts").path(alice.id().toString()).path("rows");
        com.fasterxml.jackson.databind.JsonNode rowStates =
                root.path("sheetProgress").path(alice.id().toString()).path("rowStates");

        // Every layout row ID must appear as a key in rowStates
        for (com.fasterxml.jackson.databind.JsonNode row : layoutRows) {
            String rowId = row.path("id").asText();
            org.junit.jupiter.api.Assertions.assertTrue(
                    rowStates.has(rowId),
                    "rowStates must be keyed by row UUID, missing key: " + rowId);
        }
    }

    @Test
    void offlineGameStateHasNoTurnState() throws Exception {
        String offlineId = GameRegistry.createGame("offline", 4,
                nl.adg.qwixx.game.options.GameSettings.builder()
                        .gameMode(nl.adg.qwixx.game.options.GameMode.OFFLINE).build());
        Player bob = Player.of("Bob");
        GameRegistry.getGame(offlineId).addPlayer(bob);
        GameRegistry.getGame(offlineId).start();

        mvc.perform(get("/gamestates/{sid}", offlineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnState").doesNotExist());
    }
}
