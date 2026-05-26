package nl.adg.qwixx.web;

import nl.adg.qwixx.generated.api.GameOptionsApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(jsonPath("$.length()").value(10));
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

    @Test
    void previewLayoutDefaultSettingsReturnsFourRows() throws Exception {
        mvc.perform(post("/game-options/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4));
    }

    @Test
    void previewLayoutLongoReturns15CellsPerRow() throws Exception {
        mvc.perform(post("/game-options/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base\":\"LONGO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells.length()").value(15));
    }

    @Test
    void previewLayoutStandardReturns11CellsPerRow() throws Exception {
        mvc.perform(post("/game-options/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells.length()").value(11));
    }
}