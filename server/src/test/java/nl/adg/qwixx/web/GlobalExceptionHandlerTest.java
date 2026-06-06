package nl.adg.qwixx.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import nl.adg.qwixx.game.GameAlreadyStartedException;
import nl.adg.qwixx.game.GameNotFinishedException;
import nl.adg.qwixx.game.GameNotStartedException;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.rules.IllegalMoveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/throw/not-found")    void notFound()    { throw new SessionNotFoundException("s1"); }
        @GetMapping("/throw/conflict1")    void conflict1()   { throw new GameAlreadyStartedException("s1"); }
        @GetMapping("/throw/conflict2")    void conflict2()   { throw new GameNotStartedException("s1"); }
        @GetMapping("/throw/conflict3")    void conflict3()   { throw new GameNotFinishedException("s1"); }
        @GetMapping("/throw/bad-request")  void badRequest()  { throw new IllegalMoveException("illegal"); }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void sessionNotFound_returns404WithMessage() throws Exception {
        mvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void gameAlreadyStarted_returns409() throws Exception {
        mvc.perform(get("/throw/conflict1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void gameNotStarted_returns409() throws Exception {
        mvc.perform(get("/throw/conflict2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void gameNotFinished_returns409() throws Exception {
        mvc.perform(get("/throw/conflict3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void illegalMove_returns400WithMessage() throws Exception {
        mvc.perform(get("/throw/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("illegal"));
    }
}
