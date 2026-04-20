package nl.adg.qwixx.web;

import nl.adg.qwixx.generated.api.GameOptionsApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GameOptionsApiController.class)
@Import(GameOptionsApiDelegateImpl.class)
class GameOptionsApiDelegateImplTest {

    @Autowired
    MockMvc mvc;

    @Test
    void getGameOptionsReturnsNonEmptyList() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void getGameOptionsIncludesGameModeAndCardMode() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='gameMode')]").exists())
                .andExpect(jsonPath("$[?(@.key=='cardMode')]").exists());
    }

    @Test
    void getGameOptionsGameModeHasChoices() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='gameMode')].choices[0]").value("ONLINE"));
    }
}