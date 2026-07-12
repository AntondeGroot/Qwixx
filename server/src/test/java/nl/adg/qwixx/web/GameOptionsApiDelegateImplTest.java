package nl.adg.qwixx.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import nl.adg.qwixx.generated.api.GameOptionsApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = GameOptionsApiController.class)
@Import(GameOptionsApiDelegateImpl.class)
class GameOptionsApiDelegateImplTest {

    @Autowired
    MockMvc mvc;

    @Test
    void getGameOptionsReturnsNonEmptyList() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13));
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

    // ── type field must be serialised correctly for every option ─────────────

    @Test
    void botCountHasTypeInteger() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='botCount')].type").value("INTEGER"));
    }

    @Test
    void botCountHasMinValueAndMaxValue() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='botCount')].minValue").value(0))
                .andExpect(jsonPath("$[?(@.key=='botCount')].maxValue").value(3));
    }

    @Test
    void botStrategyHasTypeEnum() throws Exception {
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='botStrategy')].type").value("ENUM"));
    }

    @Test
    void booleanOptionsHaveTypeBooleanNotEnum() throws Exception {
        // bigPoints, randomOrder, extraRow, connectedCells, xChange, luckyNumber, luckyCross
        for (String key : new String[]{"bigPoints", "randomOrder", "extraRow", "connectedCells",
                "xChange", "luckyNumber", "luckyCross"}) {
            mvc.perform(get("/game-options"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.key=='" + key + "')].type").value("BOOLEAN"));
        }
    }

    @Test
    void noIntegerOptionIsSerializedAsEnum() throws Exception {
        // Regression guard: OptionType.INTEGER must never appear as "ENUM" in the response.
        // The toDto() method previously defaulted everything non-BOOLEAN to ENUM.
        mvc.perform(get("/game-options"))
                .andExpect(status().isOk())
                // botCount is the only INTEGER option; its type must not be ENUM
                .andExpect(jsonPath(
                        "$[?(@.key=='botCount' && @.type=='ENUM')]").isEmpty());
    }
}
