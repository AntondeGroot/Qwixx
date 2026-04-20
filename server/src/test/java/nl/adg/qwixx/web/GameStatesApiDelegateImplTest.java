package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.api.GamestatesApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
}