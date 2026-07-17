package nl.adg.qwixx.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = GameNameController.class)
class GameNameControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void gameNameReturnsQwixxAsJson() throws Exception {
        mvc.perform(get("/game-name"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("Qwixx"));
    }

    @Test
    void gameNameAcceptsLocaleParam() throws Exception {
        mvc.perform(get("/game-name").param("locale", "nl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Qwixx"));
    }
}
