package nl.adg.qwixx.testapp;

import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.game.BaseVariant;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.state.RowState;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

    private record Scenario(String path, String label, boolean isScoreScreen) {}

    private record PreviewResult(
            String description,
            String redirectUrl,
            Map<String, String> otherPlayerUrls) {
        PreviewResult {
            otherPlayerUrls = Map.copyOf(otherPlayerUrls);
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("/preview/1",  "2-player · RED row almost closable (5 crosses, dice ready)", false),
            new Scenario("/preview/2",  "2-player · game finished → score screen",                    true),
            new Scenario("/preview/3",  "5-player · mid-game · you are the active player",            false),
            new Scenario("/preview/4",  "5-player · game finished → score screen",                    true),
            new Scenario("/preview/5",  "Longo variant · mid-game · you are the active player",       false),
            new Scenario("/preview/6",  "Extra-row variant · mid-game",                               false),
            new Scenario("/preview/7",  "2-player · player1 crossed RED 12 and declared lock intent · you (active) must confirm", false),
            new Scenario("/preview/8",  "2-player · near game end (many crosses)",                    false),
            new Scenario("/preview/9",  "5-player · extra-row variant · game finished → score screen",true),
            new Scenario("/preview/10", "Longo · 3-player · close RED (white+white=16) AND YELLOW (white+yellow=16) this turn", false),
            new Scenario("/preview/11", "Longo · 3-player · cross value 15 twice: RED (white+white=15) AND YELLOW (white+yellow=15)", false)
    );

    // ── HTML endpoints ────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<String> list() {
        var rows = new StringBuilder();
        for (var s : SCENARIOS) {
            rows.append("<tr>")
                .append("<td class='url'>").append(esc(s.path())).append("</td>")
                .append("<td>").append(esc(s.label())).append("</td>")
                .append("<td><span class='badge").append(s.isScoreScreen() ? " score" : "").append("'>")
                    .append(s.isScoreScreen() ? "score" : "board").append("</span></td>")
                .append("<td><a href='").append(s.path().substring(1)).append("' class='btn primary'>Open</a></td>")
                .append("</tr>");
        }
        String body = "<h1>Scenario Previews</h1>"
                + "<p class='hint'>Each button creates a fresh game — open multiple tabs for multiplayer testing.</p>"
                + "<table><thead><tr><th>Path</th><th>Scenario</th><th>Type</th><th></th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>";
        return html(page("Scenario Previews", body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> open(@PathVariable int id) {
        PreviewResult r = switch (id) {
            case 1  -> scenario01();
            case 2  -> scenario02();
            case 3  -> scenario03();
            case 4  -> scenario04();
            case 5  -> scenario05();
            case 6  -> scenario06();
            case 7  -> scenario07();
            case 8  -> scenario08();
            case 9  -> scenario09();
            case 10 -> scenario10();
            case 11 -> scenario11();
            default -> null;
        };
        if (r == null) return ResponseEntity.notFound().build();

        var actions = new StringBuilder();
        actions.append("<a href='").append(r.redirectUrl()).append("' class='btn primary'>Open as active player</a>");
        for (var e : r.otherPlayerUrls().entrySet()) {
            actions.append("<a href='").append(e.getValue()).append("' class='btn ghost'>")
                   .append(esc(e.getKey())).append("</a>");
        }
        String body = "<h1>Scenario " + id + "</h1>"
                + "<p class='desc'>" + esc(r.description()) + "</p>"
                + "<div class='actions'>" + actions + "</div>"
                + "<a href='../preview' class='back'>&#8592; back to list</a>";
        return html(page("Scenario " + id, body));
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────────

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
                .body(body);
    }

    private static final String PAGE_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <title>{{title}}</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; }
                body  { margin: 0; padding: 2rem 1.5rem; background: #12122a; color: #e0e0e0;
                        font-family: system-ui, sans-serif; }
                h1    { margin: 0 0 .4rem; color: #fff; font-size: 1.5rem; }
                .hint { margin: 0 0 1.5rem; color: #888; font-size: .9rem; }
                table { border-collapse: collapse; width: 100%; max-width: 880px; }
                th    { padding: .45rem 1rem; text-align: left; color: #aaa; font-size: .75rem;
                        text-transform: uppercase; letter-spacing: .06em;
                        border-bottom: 2px solid rgba(255,255,255,.12); }
                td    { padding: .6rem 1rem; border-bottom: 1px solid rgba(255,255,255,.06);
                        vertical-align: middle; }
                tr:hover td { background: rgba(255,255,255,.04); }
                .url  { font-family: monospace; font-size: .85rem; color: gold;
                        text-decoration: underline; white-space: nowrap; }
                .badge { display: inline-block; font-size: .72rem; font-weight: 600;
                         text-transform: uppercase; letter-spacing: .05em;
                         padding: .15rem .5rem; border-radius: 3px;
                         background: rgba(255,255,255,.1); color: #aaa; }
                .badge.score { background: rgba(255,215,0,.15); color: gold; }
                .btn  { display: inline-block; padding: .4rem 1.1rem; border-radius: 5px;
                        font-size: .88rem; font-weight: 700; text-decoration: none;
                        border: none; white-space: nowrap; }
                .btn:hover { opacity: .82; }
                .primary { background: gold; color: #111; }
                .ghost   { background: rgba(255,255,255,.07); color: #ccc;
                           border: 1px solid rgba(255,255,255,.18); }
                .desc { color: #bbb; margin: 0 0 1.75rem; }
                .actions { display: flex; gap: .75rem; flex-wrap: wrap; margin-bottom: 2rem; }
                .back { color: #888; font-size: .85rem; text-decoration: none; }
                .back:hover { color: #ccc; }
              </style>
            </head>
            <body>{{body}}</body>
            </html>
            """;

    private static String page(String title, String body) {
        return PAGE_TEMPLATE
                .replace("{{title}}", esc(title))
                .replace("{{body}}", body);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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

    // Relative URLs so the links work regardless of nginx path prefix (e.g. /qwixx/).
    // The launcher page lives at /preview/{id}, so ../game/... resolves correctly.
    private String gameUrl(String sessionId, UUID playerId) {
        return "../game/" + sessionId + "/" + playerId;
    }

    private String scoreUrl(String sessionId, UUID playerId) {
        return "../score/" + sessionId + "?pid=" + playerId + "&fast=1";
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
    // player1 (passive) has 5 crosses in RED and crosses "12" using white+white=12,
    // then declares lock intent. Row is pending closure at EVALUATE.
    private PreviewResult scenario07() {
        GameSetup g = startGame(2, GameSettings.builder().build());
        Player p0 = g.players().get(0); // active player — this is "you"
        Player p1 = g.players().get(1); // passive declarant

        setCrosses(g, p0, 0, 5); // you have 5 crosses in RED (can also cross "12")
        setCrosses(g, p1, 0, 5); // player1 has 5 crosses in RED (qualifies for lock)

        // Roll with white=6+6=12 so "12" is the reachable closing cell
        rollAndSet(g, 6, 6);

        // player1 crosses RED "12" with white+white, then declares lock intent
        var state   = g.session().currentState();
        var redRow  = state.sheetLayouts().get(p1.id()).rows().get(0);
        String red12Id = redRow.cells().stream()
                .filter(c -> "12".equals(c.displayValue()))
                .findFirst().orElseThrow().id();
        g.session().applyAction(new CrossCellAction(p1.id(), 0, red12Id, DiceCombination.WHITE_WHITE));
        g.session().applyAction(new DeclareLockIntentAction(p1.id(), 0));

        return new PreviewResult(
                "2-player · player1 crossed RED 12 and declared lock intent · row closes at end of turn",
                gameUrl(g.sessionId(), p0.id()),
                otherGameUrls(g, p0.id()));
    }

    // 8 — 2-player · near game end; RED+YELLOW almost full for both players
    private PreviewResult scenario08() {
        GameSetup g = startGame(2, GameSettings.builder().base(BaseVariant.LONGO).build());
        setCrosses(g, g.players().get(0), 0, 9); // RED: 9/12
        setCrosses(g, g.players().get(0), 1, 8); // YELLOW: 8/12
        setCrosses(g, g.players().get(0), 2, 4);
        setCrosses(g, g.players().get(0), 3, 3);
        setCrosses(g, g.players().get(1), 0, 6);
        setCrosses(g, g.players().get(1), 1, 7);
        setCrosses(g, g.players().get(1), 2, 5);
        setCrosses(g, g.players().get(1), 3, 4);
        UUID you = rollAndSet(g, 7, 8);
        return new PreviewResult(
                "✅ LONGO 2-player · near game end (player0: 9 in RED, 8 in YELLOW) · dice=7+8",
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

        // player1: progress in RED/YELLOW/GREEN; BLUE row empty so BLUE "16" is
        // clickable (white+white=16) during the final PASSIVE_MOVE after the double-close
        setCrosses(g, p1, 0,  4);
        setCrosses(g, p1, 1,  3);
        setCrosses(g, p1, 2,  9);

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

    // 11 — Longo · 3-player · cross value 15 in RED (white+white) AND YELLOW (white+yellow die)
    //
    //  Longo ascending rows: position 13 = display value 15  (values run 2→16).
    //  player0 has 13 crosses in RED and YELLOW (values 2–14, positions 0–12).
    //  Value 15 (position 13) is the next available cell in both rows.
    //
    //  Dice: white1=7, white2=8  →  white+white = 15  →  RED position 13 (value 15)
    //        yellow die = 8       →  white1+yellow = 7+8 = 15  →  YELLOW position 13 (value 15)
    private PreviewResult scenario11() {
        GameSetup g = startGame(3, GameSettings.builder().base(BaseVariant.LONGO).build());
        Player p0 = g.players().get(0);
        Player p1 = g.players().get(1);
        Player p2 = g.players().get(2);

        // player0: 13 crosses in RED and YELLOW — value 15 is the next cell in both
        setCrosses(g, p0, 0, 13); // RED   positions 0-12 (values 2-14)
        setCrosses(g, p0, 1, 13); // YELLOW positions 0-12 (values 2-14)
        setCrosses(g, p0, 2,  4);
        setCrosses(g, p0, 3,  3);

        setCrosses(g, p1, 0,  5);
        setCrosses(g, p1, 1,  4);
        setCrosses(g, p1, 2,  8);
        setCrosses(g, p1, 3,  7);

        setCrosses(g, p2, 0,  7);
        setCrosses(g, p2, 1,  6);
        setCrosses(g, p2, 2,  5);
        setCrosses(g, p2, 3,  4);

        // white=7+8 (sum=15); yellow die=8 so white1(7)+yellow(8)=15
        UUID you = rollAndSetColored(g, 7, 8, Map.of(Color.YELLOW, 8));

        return new PreviewResult(
                "Longo · 3-player · cross value 15 in RED (white+white=15) AND YELLOW (white+yellow=15) this turn",
                gameUrl(g.sessionId(), you),
                otherGameUrls(g, you));
    }

}
