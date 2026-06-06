package nl.adg.qwixx.testapp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.state.ClosureNotification;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.web.SseEmitterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("e2e")
@RequestMapping("/test")
public class TestController {

    @Autowired
    private SseEmitterRegistry sseRegistry;

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        GameRegistry.clear();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/set-crosses/{sessionId}/{playerId}/{rowIndex}/{count}")
    public ResponseEntity<Void> setCrosses(
            @PathVariable String sessionId,
            @PathVariable String playerId,
            @PathVariable int rowIndex,
            @PathVariable int count) {

        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) return ResponseEntity.notFound().build();

        UUID pid = UUID.fromString(playerId);
        var state = session.currentState();
        var layout = state.sheetLayouts().get(pid);
        var progress = state.boardState().sheetProgress().get(pid);
        var row = layout.rows().get(rowIndex);

        Set<String> crossed = new HashSet<>();
        for (int i = 0; i < Math.min(count, row.cells().size()); i++) {
            crossed.add(row.cells().get(i).id());
        }
        progress.updateRowState(rowIndex, new RowState(crossed, false));

        return ResponseEntity.ok().build();
    }

    @PostMapping("/force-finish/{sessionId}")
    public ResponseEntity<Void> forceFinish(@PathVariable String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) return ResponseEntity.notFound().build();
        session.forceFinish();
        sseRegistry.emit(sessionId, session.currentState(), session);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/add-closure-request/{sessionId}/{playerId}/{rowColor}")
    public ResponseEntity<Void> addClosureRequest(
            @PathVariable String sessionId,
            @PathVariable String playerId,
            @PathVariable String rowColor) {

        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) return ResponseEntity.notFound().build();

        Color color = Color.valueOf(rowColor.toUpperCase());
        session.currentState().closureNotifications().add(new ClosureNotification(UUID.fromString(playerId), color));
        session.currentState().incrementVersion();
        sseRegistry.emit(sessionId, session.currentState(), session);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/set-dice/{sessionId}/{white1}/{white2}")
    public ResponseEntity<Void> setDice(
            @PathVariable String sessionId,
            @PathVariable int white1,
            @PathVariable int white2) {

        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) return ResponseEntity.notFound().build();

        var turnState = session.currentState().turnState();
        if (turnState == null || turnState.currentRoll() == null)
            return ResponseEntity.badRequest().build();

        var current = turnState.currentRoll();
        turnState.setCurrentRoll(new RollResult(white1, white2, current.coloredDice()));
        session.currentState().incrementVersion();
        sseRegistry.emit(sessionId, session.currentState(), session);

        return ResponseEntity.ok().build();
    }

    /** Override the value of one specific colored die, leaving the rest of the roll unchanged. */
    @PostMapping("/set-colored-die/{sessionId}/{color}/{value}")
    public ResponseEntity<Void> setColoredDie(
            @PathVariable String sessionId,
            @PathVariable String color,
            @PathVariable int value) {

        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) return ResponseEntity.notFound().build();

        var turnState = session.currentState().turnState();
        if (turnState == null || turnState.currentRoll() == null)
            return ResponseEntity.badRequest().build();

        var current    = turnState.currentRoll();
        Map<Color, Integer> newColored = new HashMap<>(current.coloredDice());
        newColored.put(Color.valueOf(color.toUpperCase()), value);
        turnState.setCurrentRoll(new RollResult(current.white1(), current.white2(), newColored));
        session.currentState().incrementVersion();
        sseRegistry.emit(sessionId, session.currentState(), session);

        return ResponseEntity.ok().build();
    }
}
