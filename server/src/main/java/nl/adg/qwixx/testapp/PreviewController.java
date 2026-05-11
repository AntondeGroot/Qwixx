package nl.adg.qwixx.testapp;

import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.game.BaseVariant;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.state.RowClosureRequest;
import nl.adg.qwixx.state.RowState;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dev-only endpoints that create pre-seeded game scenarios so you can
 * navigate directly to a specific state without playing through the game.
 *
 * Available only when the "e2e" Spring profile is NOT active (i.e. normal
 * dev mode).  Accessible at GET /preview/{1..10}.
 *
 * Usage:
 *   1. Start the server with the default profile.
 *   2. Navigate to http://localhost:4200/preview to see all scenarios.
 *   3. Navigate to http://localhost:4200/preview/3 to launch scenario 3.
 */
@RestController
@Profile("!e2e")
@RequestMapping("/preview")
public class PreviewController {

    // Returned by GET /preview — shows available scenarios without creating games
    public record ScenarioSummary(String id, String label, boolean isScoreScreen) {}

    // Returned by GET /preview/{id} — game is created, caller should navigate to redirectUrl
    public record PreviewResult(
            String description,
            String redirectUrl,
            Map<String, String> otherPlayerUrls) {
        public PreviewResult {
            otherPlayerUrls = Map.copyOf(otherPlayerUrls);
        }
    }

    static final List<ScenarioSummary> SCENARIOS = List.of(
            new ScenarioSummary("/preview/1",  "2-player · RED row almost closable (5 crosses, dice ready)", false),
            new ScenarioSummary("/preview/2",  "2-player · game finished → score screen",                    true),
            new ScenarioSummary("/preview/3",  "5-player · mid-game · you are the active player",            false),
            new ScenarioSummary("/preview/4",  "5-player · game finished → score screen",                    true),
            new ScenarioSummary("/preview/5",  "Longo variant · mid-game · you are the active player",       false),
            new ScenarioSummary("/preview/6",  "Extra-row variant · mid-game",                               false),
            new ScenarioSummary("/preview/7",  "2-player · RED row closure requested by other player",       false),
            new ScenarioSummary("/preview/8",  "2-player · near game end (many crosses)",                    false),
            new ScenarioSummary("/preview/9",  "5-player · extra-row variant · game finished → score screen",true),
            new ScenarioSummary("/preview/10", "Longo · 3-player · close RED (white+white=16) AND YELLOW (white+yellow=16) this turn", false)
    );

    @GetMapping
    public List<ScenarioSummary> listScenarios() {
        return SCENARIOS;
    }

    @GetMapping("/{id}")
    public ResponseEntity<PreviewResult> setup(@PathVariable int id) {
        return switch (id) {
            case 1  -> ResponseEntity.ok(scenario01());
            case 2  -> ResponseEntity.ok(scenario02());
            case 3  -> ResponseEntity.ok(scenario03());
            case 4  -> ResponseEntity.ok(scenario04());
            case 5  -> ResponseEntity.ok(scenario05());
            case 6  -> ResponseEntity.ok(scenario06());
            case 7  -> ResponseEntity.ok(scenario07());
            case 8  -> ResponseEntity.ok(scenario08());
            case 9  -> ResponseEntity.ok(scenario09());
            case 10 -> ResponseEntity.ok(scenario10());
            default -> ResponseEntity.notFound().build();
        };
    }

    // ── Shared helpers ────────────────────────────────────────────────────────────

    private record GameSetup(String sessionId, List<Player> players, GameSession session) {}

    private GameSetup startGame(int nPlayers, GameSettings settings) {
        String sid = GameRegistry.createGame("preview-" + System.nanoTime(), nPlayers, settings);
        GameSession session = GameRegistry.getGame(sid);
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < nPlayers; i++) {
            Player p = Player.of("player" + i);
            session.addPlayer(p);
            players.add(p);
        }
        session.start();
        return new GameSetup(sid, players, session);
    }

    private void setCrosses(GameSetup g, Player player, int rowIndex, int count) {
        var state    = g.session().currentState();
        var layout   = state.sheetLayouts().get(player.id());
        var progress = state.boardState().sheetProgress().get(player.id());
        var row      = layout.rows().get(rowIndex);
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < Math.min(count, row.cells().size()); i++) {
            cells.add(row.cells().get(i).id());
        }
        progress.updateRowState(rowIndex, new RowState(cells, false));
    }

    /** Rolls the dice for the active player and overrides the white dice values. */
    private UUID rollAndSet(GameSetup g, int white1, int white2) {
        return rollAndSetColored(g, white1, white2, Map.of());
    }

    /**
     * Rolls dice for the active player, overrides white dice, and overrides specific
     * colored dice.  {@code coloredOverrides} is merged into the rolled colored dice
     * map so only the specified colors are replaced.
     */
    private UUID rollAndSetColored(GameSetup g, int white1, int white2,
                                   Map<Color, Integer> coloredOverrides) {
        var state = g.session().currentState();
        UUID active = state.turnState().activePlayerId();
        g.session().applyAction(new RollAction(active));
        var roll = g.session().currentState().turnState().currentRoll();
        Map<Color, Integer> newColored = new HashMap<>(roll.coloredDice());
        newColored.putAll(coloredOverrides);
        g.session().currentState().turnState()
                .setCurrentRoll(new RollResult(white1, white2, newColored));
        return active;
    }

    private String gameUrl(String sessionId, UUID playerId) {
        return "/game/" + sessionId + "/" + playerId;
    }

    private String scoreUrl(String sessionId, UUID playerId) {
        return "/score/" + sessionId + "?pid=" + playerId + "&fast=1";
    }

    private Map<String, String> otherGameUrls(GameSetup g, UUID youId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Player p : g.players()) {
            if (!p.id().equals(youId)) {
                map.put(p.name(), gameUrl(g.sessionId(), p.id()));
            }
        }
        return map;
    }

    private Map<String, String> otherScoreUrls(GameSetup g, UUID youId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Player p : g.players()) {
            if (!p.id().equals(youId)) {
                map.put(p.name(), scoreUrl(g.sessionId(), p.id()));
            }
        }
        return map;
    }

    // ── Scenarios ─────────────────────────────────────────────────────────────────

    // 1 — 2-player · mid-game · basic setup, some progress, you are active (rolled 3+4)
    // 1 — 2-player · 5 crosses in RED for active player, dice = 4+4 (sum=8)
    //     Enough crosses to be able to declare lock intent on RED
    private PreviewResult scenario01() {
        GameSetup g = startGame(2, GameSettings.builder().build());
        setCrosses(g, g.players().get(0), 0, 5); // RED: first 5 cells
        setCrosses(g, g.players().get(1), 0, 3); // RED: first 3 cells
        UUID you = rollAndSet(g, 4, 4);
        return new PreviewResult(
                "2-player · RED almost closable: 5 crosses in RED, dice=4+4 (sum=8)",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 2 — 2-player · game finished; player0 scores better on RED+GREEN, player1 on YELLOW+BLUE
    private PreviewResult scenario02() {
        GameSetup g = startGame(2, GameSettings.builder().build());
        setCrosses(g, g.players().get(0), 0, 7); // RED
        setCrosses(g, g.players().get(0), 1, 4); // YELLOW
        setCrosses(g, g.players().get(0), 2, 6); // GREEN
        setCrosses(g, g.players().get(0), 3, 5); // BLUE
        setCrosses(g, g.players().get(1), 0, 3);
        setCrosses(g, g.players().get(1), 1, 8);
        setCrosses(g, g.players().get(1), 2, 2);
        setCrosses(g, g.players().get(1), 3, 9);
        g.session().forceFinish();
        UUID you = g.players().get(0).id();
        return new PreviewResult(
                "2-player · game finished (player0 wins on RED+GREEN, player1 on YELLOW+BLUE)",
                scoreUrl(g.sessionId(), you),
                otherScoreUrls(g, you));
    }

    // 3 — 5-player · mid-game · each player has a few crosses in different rows
    private PreviewResult scenario03() {
        GameSetup g = startGame(5, GameSettings.builder().build());
        setCrosses(g, g.players().get(0), 0, 3);
        setCrosses(g, g.players().get(1), 1, 2);
        setCrosses(g, g.players().get(2), 2, 4);
        setCrosses(g, g.players().get(3), 3, 2);
        setCrosses(g, g.players().get(4), 0, 1);
        UUID you = rollAndSet(g, 2, 5);
        return new PreviewResult(
                "5-player · mid-game · you are the active player (rolled 2+5)",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 4 — 5-player · finished; varied cross counts so ranking is unambiguous
    private PreviewResult scenario04() {
        GameSetup g = startGame(5, GameSettings.builder().build());
        // rows: RED=0, YELLOW=1, GREEN=2, BLUE=3
        int[][] crosses = {
                {9, 2, 5, 3},   // player0: 28 pts  (p1 wins)
                {4, 7, 3, 8},   // player1: 27 pts
                {6, 5, 8, 4},   // player2: 36 pts  (p2 likely wins with higher row values)
                {3, 9, 4, 6},   // player3: 34 pts
                {5, 3, 7, 2},   // player4: 19 pts
        };
        for (int p = 0; p < 5; p++) {
            for (int r = 0; r < 4; r++) {
                setCrosses(g, g.players().get(p), r, crosses[p][r]);
            }
        }
        g.session().forceFinish();
        UUID you = g.players().get(0).id();
        return new PreviewResult(
                "5-player · game finished (varied scores — see who wins!)",
                scoreUrl(g.sessionId(), you),
                otherScoreUrls(g, you));
    }

    // 5 — Longo variant · mid-game
    private PreviewResult scenario05() {
        GameSetup g = startGame(2, GameSettings.builder().base(BaseVariant.LONGO).build());
        setCrosses(g, g.players().get(0), 0, 3);
        setCrosses(g, g.players().get(0), 1, 2);
        setCrosses(g, g.players().get(1), 2, 4);
        setCrosses(g, g.players().get(1), 3, 1);
        UUID you = rollAndSet(g, 3, 4);
        return new PreviewResult(
                "Longo variant · mid-game · you are the active player (rolled 3+4)",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 6 — Extra-row variant · mid-game (row index 4 is the extra row)
    private PreviewResult scenario06() {
        GameSetup g = startGame(2, GameSettings.builder().extraRow(true).build());
        setCrosses(g, g.players().get(0), 0, 3);
        setCrosses(g, g.players().get(0), 4, 2); // extra row
        setCrosses(g, g.players().get(1), 1, 4);
        setCrosses(g, g.players().get(1), 4, 3); // extra row
        UUID you = rollAndSet(g, 2, 3);
        return new PreviewResult(
                "Extra-row variant · mid-game · you are the active player (rolled 2+3)",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 7 — 2-player · player1 has declared intent to close RED; you (player0) are about to act
    private PreviewResult scenario07() {
        GameSetup g = startGame(2, GameSettings.builder().build());
        setCrosses(g, g.players().get(0), 0, 5);
        setCrosses(g, g.players().get(1), 0, 6);
        g.session().currentState().rowClosureRequests()
                .add(new RowClosureRequest("player1", Color.RED));
        UUID you = rollAndSet(g, 4, 3);
        return new PreviewResult(
                "2-player · RED row closure declared by player1 · you have 5 crosses, dice=4+3",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 8 — 2-player · near game end; RED+YELLOW almost full for both players
    private PreviewResult scenario08() {
        GameSetup g = startGame(2, GameSettings.builder().build());
        setCrosses(g, g.players().get(0), 0, 9); // RED: 9/12
        setCrosses(g, g.players().get(0), 1, 8); // YELLOW: 8/12
        setCrosses(g, g.players().get(0), 2, 4);
        setCrosses(g, g.players().get(0), 3, 3);
        setCrosses(g, g.players().get(1), 0, 6);
        setCrosses(g, g.players().get(1), 1, 7);
        setCrosses(g, g.players().get(1), 2, 5);
        setCrosses(g, g.players().get(1), 3, 4);
        UUID you = rollAndSet(g, 5, 6);
        return new PreviewResult(
                "2-player · near game end (player0: 9 in RED, 8 in YELLOW) · dice=5+6",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 10 — Longo · 3-player · active player can close RED (white+white=16) AND YELLOW (white+yellow=16)
    //
    //  Longo uses 8-sided dice (range 1–8) and rows that run 2→16 (15 number cells + lock).
    //  Lock minimum: 7 crosses.
    //
    //  player0 has 14 crosses in RED (values 2–15) and 14 in YELLOW (values 2–15):
    //    only the last cell (value 16) remains uncrossed in each row.
    //  Dice: white1=8, white2=8  →  white+white = 16  →  crosses RED position 14 → auto-lock RED
    //        yellow die = 8       →  white+yellow = 16  →  crosses YELLOW position 14 → auto-lock YELLOW
    //
    //  The active player can therefore close BOTH rows in a single turn.
    private PreviewResult scenario10() {
        GameSetup g = startGame(3, GameSettings.builder().base(BaseVariant.LONGO).build());
        Player p0 = g.players().get(0);
        Player p1 = g.players().get(1);
        Player p2 = g.players().get(2);

        // player0: 14 crosses in RED and YELLOW — one cell away from closing both
        setCrosses(g, p0, 0, 14); // RED   rows 0-13 (values 2-15)
        setCrosses(g, p0, 1, 14); // YELLOW rows 0-13 (values 2-15)
        setCrosses(g, p0, 2,  5); // GREEN — some progress
        setCrosses(g, p0, 3,  4); // BLUE  — some progress

        // player1: well progressed in GREEN and BLUE
        setCrosses(g, p1, 0,  4);
        setCrosses(g, p1, 1,  3);
        setCrosses(g, p1, 2,  9);
        setCrosses(g, p1, 3,  8);

        // player2: spread across all rows
        setCrosses(g, p2, 0,  6);
        setCrosses(g, p2, 1,  7);
        setCrosses(g, p2, 2,  5);
        setCrosses(g, p2, 3,  6);

        // white=8+8 (sum=16 hits RED/YELLOW last cell); yellow die also 8 (white+yellow=16)
        UUID you = rollAndSetColored(g, 8, 8, Map.of(Color.YELLOW, 8));

        return new PreviewResult(
                "Longo · 3-player · you can close RED (white+white=16) AND YELLOW (white+yellow die=16) this turn",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

    // 9 — 5-player · extra-row · finished; five rows in the score table
    private PreviewResult scenario09() {
        GameSetup g = startGame(5, GameSettings.builder().extraRow(true).build());
        // rows: RED=0, YELLOW=1, GREEN=2, BLUE=3, EXTRA=4
        int[][] crosses = {
                {7, 3, 5, 4, 6},
                {4, 8, 2, 7, 3},
                {6, 4, 9, 3, 5},
                {3, 6, 4, 8, 7},
                {5, 2, 6, 4, 4},
        };
        for (int p = 0; p < 5; p++) {
            for (int r = 0; r < 5; r++) {
                setCrosses(g, g.players().get(p), r, crosses[p][r]);
            }
        }
        g.session().forceFinish();
        UUID you = g.players().get(0).id();
        return new PreviewResult(
                "5-player · extra-row variant · game finished (5 score columns)",
                scoreUrl(g.sessionId(), you),
                otherScoreUrls(g, you));
    }
}
