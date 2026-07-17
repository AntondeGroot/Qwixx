package nl.adg.qwixx.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the game's display name for the GameRoom lobby, which GETs {baseUrl}/game-name?locale=xx
 * and reads the "name" field. Without this endpoint the request falls through to the SPA and
 * GameRoom logs a 404. "Qwixx" is a brand name, identical in every language, so {@code locale} is
 * accepted for contract compatibility but does not change the result.
 */
@RestController
public class GameNameController {

    @GetMapping("/game-name")
    public Map<String, String> gameName(@RequestParam(required = false) String locale) {
        return Map.of("name", "Qwixx");
    }
}
