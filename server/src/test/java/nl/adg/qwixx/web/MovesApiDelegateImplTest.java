package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.api.MovesApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MovesApiController.class)
@Import({MovesApiDelegateImpl.class, GlobalExceptionHandler.class})
class MovesApiDelegateImplTest {

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
    void rollReturnsAccepted() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void rollReturns404ForUnknownSession() throws Exception {
        mvc.perform(post("/moves/ghost/{pid}", alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPlayerIdReturns400() throws Exception {
        mvc.perform(post("/moves/{sid}/not-a-uuid", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossWithMissingRowIdReturns400() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"CROSS_WHITE_WHITE","cellId":"some-cell"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rollAfterRollIsIllegalMove() throws Exception {
        // first roll succeeds, second roll in ACTIVE_MOVE phase is illegal
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void passInActiveMoveWithoutWhiteWhiteIsIllegal() throws Exception {
        // roll first
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isOk());

        // PASS in ACTIVE_MOVE requires white+white to have been used first
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"PASS"}
                                """))
                .andExpect(status().isBadRequest());
    }
}