package nl.adg.qwixx.web;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.GameMode;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.api.MovesApiController;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MovesApiController.class)
@Import({MovesApiDelegateImpl.class, GlobalExceptionHandler.class, SseEmitterRegistry.class})
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
    void crossWithIntegerRowIdReturns400() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"CROSS_WHITE_WHITE","rowId":"0","cellId":"some-cell"}
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

    @Test
    void declareLockIntentWithoutRowIdReturns400() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"DECLARE_LOCK_INTENT"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void giveUpInRollPhaseIsIllegal() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"GIVE_UP"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetTurnInRollPhaseIsNoOp() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"RESET_TURN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void undoLastCrossInRollPhaseIsIllegal() throws Exception {
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"UNDO_LAST_CROSS"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── Offline mode ──────────────────────────────────────────────────────────

    @Test
    void offlineTakePunishmentIsAccepted() throws Exception {
        String sid = offlineSession();
        Player bob = offlinePlayer(sid);

        mvc.perform(post("/moves/{sid}/{pid}", sid, bob.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"TAKE_PUNISHMENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void offlineCrossWhiteWhiteIsAccepted() throws Exception {
        String sid = offlineSession();
        Player bob = offlinePlayer(sid);
        nl.adg.qwixx.state.SheetLayout layout =
                GameRegistry.getGame(sid).currentState().sheetLayouts().get(bob.id());
        nl.adg.qwixx.data.Row row   = layout.rows().get(0);
        nl.adg.qwixx.data.Cell cell = row.cells().get(0);

        mvc.perform(post("/moves/{sid}/{pid}", sid, bob.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                                """.formatted(row.id(), cell.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void offlineRollIsRejected() throws Exception {
        String sid = offlineSession();
        Player bob = offlinePlayer(sid);

        mvc.perform(post("/moves/{sid}/{pid}", sid, bob.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"ROLL"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void offlineCrossLockWithoutRowIdReturns400() throws Exception {
        String sid = offlineSession();
        Player bob = offlinePlayer(sid);

        mvc.perform(post("/moves/{sid}/{pid}", sid, bob.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moveType":"CROSS_LOCK"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── Simultaneous play ─────────────────────────────────────────────────────

    @Test
    void simultaneousTurn_passivePlayerCanPassDuringActiveMovePhase() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        // Passive player passes (EndTurn) while active player hasn't finished yet
        move(sid, passive, """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void simultaneousTurn_passivePlayerCanCrossAndConfirmDuringActiveMovePhase() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        CellTarget target = findWhiteWhiteCell(sid, passive);

        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        // Confirm the cross with PASS — equivalent of "End Turn" for passive player
        move(sid, passive, """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void simultaneousTurn_activePlayerEndTurnAfterCrossGoesToNextTurnWhenPassiveDone() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        // Passive player finishes first
        move(sid, passive, """
                {"moveType":"PASS"}
                """).andExpect(status().isOk());

        // Active player crosses with color die and ends turn — queue already empty → evaluate
        CellTarget target = findColorDieCell(sid, active);
        move(sid, active, """
                {"moveType":"CROSS_COLOR_DIE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        move(sid, active, """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void activePlayerCannotEndTurnBeforeMakingAnyMove() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active = activePlayerId(sid);
        roll(sid, active);

        move(sid, active, """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isBadRequest());
    }

    // ── Passive player one-cross-per-turn enforcement ─────────────────────────

    /**
     * A passive player may make exactly one white+white cross per turn.
     * Crossing a second cell (in a different row but with the same value) must
     * be rejected with 400 Bad Request.
     */
    @Test
    void simultaneousTurn_passivePlayerCannotCrossMoreThanOnce() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        // Force dice so white+white = 6; both RED (position 4) and YELLOW (position 4)
        // have a reachable "6" cell — giving us two distinct targets to attempt.
        GameState s = GameRegistry.getGame(sid).currentState();
        var current = s.turnState().currentRoll();
        s.turnState().setCurrentRoll(new nl.adg.qwixx.data.RollResult(2, 4, current.coloredDice()));

        CellTarget red6    = findWhiteWhiteCell(sid, passive);
        CellTarget yellow6 = findSecondWhiteWhiteCell(sid, passive, red6.rowId());

        // First cross: must be accepted
        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(red6.rowId(), red6.cellId()))
                .andExpect(status().isOk());

        // Second cross in a different row: must be rejected
        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(yellow6.rowId(), yellow6.cellId()))
                .andExpect(status().isBadRequest());
    }

    // ── Lock flow ─────────────────────────────────────────────────────────────

    @Test
    void lockFlow_canDeclareLockIntentAfterCrossingClosingEligibleCell() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active = activePlayerId(sid);
        setupEnoughCrossesForLock(sid, active, 0);
        roll(sid, active);

        String rowId = GameRegistry.getGame(sid).currentState()
                .sheetLayouts().get(active).rows().get(0).id();

        move(sid, active, """
                {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                """.formatted(rowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void lockFlow_passivePlayerCanUndoCrossWhileLockPending() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        // Passive player crosses a cell during ACTIVE_MOVE (simultaneous)
        CellTarget target = findWhiteWhiteCell(sid, passive);
        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        // Active player accumulates enough crosses to declare lock intent
        setupEnoughCrossesForLock(sid, active, 0);

        String rowId = GameRegistry.getGame(sid).currentState()
                .sheetLayouts().get(active).rows().get(0).id();

        move(sid, active, """
                {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                """.formatted(rowId))
                .andExpect(status().isOk());

        // Passive player can undo their cross from the simultaneous phase
        move(sid, passive, """
                {"moveType":"UNDO_LAST_CROSS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    // ── Per-player layout mode (extraRow / PROBABILISTIC) ────────────────────

    /**
     * In extraRow (or PROBABILISTIC) mode each player gets their own Row objects
     * with distinct UUIDs. This verifies that assumption holds — the remaining
     * tests in this section are only meaningful because the IDs differ.
     */
    @Test
    void perPlayerMode_extraRow_eachPlayerHasDistinctRowIds() {
        Player bob = Player.of("Bob");
        String sid = perPlayerSession(alice, bob, GameSettings.builder().extraRow(true).build());
        GameState state = GameRegistry.getGame(sid).currentState();

        List<String> aliceIds = state.sheetLayouts().get(alice.id()).rows().stream()
                .map(Row::id).toList();
        List<String> bobIds = state.sheetLayouts().get(bob.id()).rows().stream()
                .map(Row::id).toList();

        assertNotEquals(aliceIds, bobIds,
                "extraRow mode must give each player their own row IDs");
    }

    /**
     * Regression: parseRowIndex used to grab an arbitrary player's layout via
     * .values().iterator().next(). In per-player mode the passive player's rowId
     * does not exist in the active player's layout, so the move was rejected.
     */
    @Test
    void perPlayerMode_extraRow_passivePlayerCanCrossWithOwnRowId() throws Exception {
        Player bob = Player.of("Bob");
        String sid = perPlayerSession(alice, bob, GameSettings.builder().extraRow(true).build());
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        CellTarget target = findWhiteWhiteCell(sid, passive);

        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void perPlayerMode_probabilistic_passivePlayerCanCrossWithOwnRowId() throws Exception {
        Player bob = Player.of("Bob");
        String sid = perPlayerSession(alice, bob,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        UUID active  = activePlayerId(sid);
        UUID passive = passivePlayerId(sid, active);
        roll(sid, active);

        CellTarget target = findWhiteWhiteCell(sid, passive);

        move(sid, passive, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void perPlayerMode_extraRow_activePlayerCanCrossWithOwnRowId() throws Exception {
        Player bob = Player.of("Bob");
        String sid = perPlayerSession(alice, bob, GameSettings.builder().extraRow(true).build());
        UUID active = activePlayerId(sid);
        roll(sid, active);

        CellTarget target = findWhiteWhiteCell(sid, active);

        move(sid, active, """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record CellTarget(String rowId, String cellId) {}

    private UUID activePlayerId(String sid) {
        return GameRegistry.getGame(sid).currentState().turnState().activePlayerId();
    }

    private UUID passivePlayerId(String sid, UUID activeId) {
        return GameRegistry.getGame(sid).currentState().players().stream()
                .filter(id -> !id.equals(activeId))
                .findFirst()
                .orElseThrow();
    }

    private void roll(String sid, UUID pid) throws Exception {
        move(sid, pid, """
                {"moveType":"ROLL"}
                """).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions move(
            String sid, UUID pid, String body) throws Exception {
        return mvc.perform(post("/moves/{sid}/{pid}", sid, pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private CellTarget findWhiteWhiteCell(String sid, UUID pid) {
        GameState state = GameRegistry.getGame(sid).currentState();
        int target = state.turnState().currentRoll().white1()
                   + state.turnState().currentRoll().white2();
        return findCell(state, pid, target);
    }

    /** Returns a white+white cell in a DIFFERENT row than {@code excludeRowId}. */
    private CellTarget findSecondWhiteWhiteCell(String sid, UUID pid, String excludeRowId) {
        GameState state  = GameRegistry.getGame(sid).currentState();
        int target = state.turnState().currentRoll().white1()
                   + state.turnState().currentRoll().white2();
        SheetLayout layout = state.sheetLayouts().get(pid);
        SheetProgress prog = state.boardState().sheetProgress().get(pid);
        for (Row row : layout.rows()) {
            if (row.id().equals(excludeRowId)) continue;
            RowState rs = prog.rowStates().getOrDefault(row.id(), new RowState(Set.of(), false));
            int rightmost = row.cells().stream()
                    .filter(c -> rs.crossedCells().contains(c.id()))
                    .mapToInt(Cell::position).max().orElse(-1);
            for (Cell cell : row.cells()) {
                if (cell.position() <= rightmost) continue;
                if (rs.crossedCells().contains(cell.id())) continue;
                if (cell.displayValue().equals(String.valueOf(target))) {
                    return new CellTarget(row.id(), cell.id());
                }
            }
        }
        throw new AssertionError("no second white+white cell found in a different row");
    }

    private CellTarget findColorDieCell(String sid, UUID pid) {
        GameState state = GameRegistry.getGame(sid).currentState();
        var roll = state.turnState().currentRoll();
        SheetLayout layout = state.sheetLayouts().get(pid);
        SheetProgress progress = state.boardState().sheetProgress().get(pid);
        for (Row row : layout.rows()) {
            int colorVal = roll.coloredDice().getOrDefault(row.cells().get(0).color(), 0);
            if (colorVal == 0) continue;
            for (int sum : new int[]{roll.white1() + colorVal, roll.white2() + colorVal}) {
                CellTarget t = findCell(state, pid, sum);
                if (t != null) return t;
            }
        }
        throw new AssertionError("no color die cell found");
    }

    private CellTarget findCell(GameState state, UUID pid, int targetValue) {
        SheetLayout layout   = state.sheetLayouts().get(pid);
        SheetProgress prog   = state.boardState().sheetProgress().get(pid);
        for (Row row : layout.rows()) {
            RowState rs = prog.rowStates().getOrDefault(row.id(), new RowState(Set.of(), false));
            int rightmost = row.cells().stream()
                    .filter(c -> rs.crossedCells().contains(c.id()))
                    .mapToInt(Cell::position).max().orElse(-1);
            for (Cell cell : row.cells()) {
                if (cell.position() <= rightmost) continue;
                if (rs.crossedCells().contains(cell.id())) continue;
                if (cell.displayValue().equals(String.valueOf(targetValue))) {
                    return new CellTarget(row.id(), cell.id());
                }
            }
        }
        return null;
    }

    private void setupEnoughCrossesForLock(String sid, UUID pid, int rowIndex) {
        GameState state = GameRegistry.getGame(sid).currentState();
        SheetLayout layout = state.sheetLayouts().get(pid);
        Row row = layout.rows().get(rowIndex);
        LockCell lock = row.lock();
        SheetProgress progress = state.boardState().sheetProgress().get(pid);

        Set<String> crossed = new HashSet<>(lock.closingCells());
        for (Cell c : row.cells()) {
            if (crossed.size() >= lock.minCrosses()) break;
            crossed.add(c.id());
        }
        progress.updateRowState(rowIndex, new RowState(crossed, false));
    }

    private String twoPlayerSession(Player... players) {
        String sid = GameRegistry.createGame("room2", 4, GameSettings.builder().build());
        for (Player p : players) GameRegistry.getGame(sid).addPlayer(p);
        GameRegistry.getGame(sid).start();
        return sid;
    }

    private String perPlayerSession(Player p1, Player p2, GameSettings settings) {
        String sid = GameRegistry.createGame("room-pp", 4, settings);
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();
        return sid;
    }

    private String multiPlayerSession() {
        String sid = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        GameRegistry.getGame(sid).addPlayer(alice);
        GameRegistry.getGame(sid).addPlayer(bob);
        GameRegistry.getGame(sid).start();
        return sid;
    }

    private String offlineSession() {
        return GameRegistry.createGame("offline", 4,
                GameSettings.builder().gameMode(GameMode.OFFLINE).build());
    }

    private Player offlinePlayer(String sid) {
        Player p = Player.of("Bob");
        GameRegistry.getGame(sid).addPlayer(p);
        GameRegistry.getGame(sid).start();
        return p;
    }

    // ── Row Closure Request Tests ─────────────────────────────────────────────

    @Test
    void closureNotificationsInitiallyEmpty() throws Exception {
        var gameState = GameRegistry.getGame(sessionId).currentState();
        assertNotNull(gameState.closureNotifications());
        assertEquals(0, gameState.closureNotifications().size());
    }

    @Test
    void closureNotificationsClearedOnResetTurn() throws Exception {
        // Add row closure requests manually (simulating DECLARE_LOCK_INTENT)
        var state = GameRegistry.getGame(sessionId).currentState();
        state.closureNotifications().add(
            new nl.adg.qwixx.state.ClosureNotification(alice.id(), nl.adg.qwixx.data.Color.RED)
        );

        // Verify requests populated
        assertEquals(1, state.closureNotifications().size());

        // Reset turn should clear requests
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"RESET_TURN"}
                        """))
                .andExpect(status().isOk());

        // Verify cleared
        var afterReset = GameRegistry.getGame(sessionId).currentState();
        assertEquals(0, afterReset.closureNotifications().size());
    }

    @Test
    void twoPlayerGameBothWith5CrossesInBlueRow() throws Exception {
        // Create a game with 2 players
        GameRegistry.clear();
        String testSessionId = GameRegistry.createGame("room", 2, GameSettings.builder().build());

        Player player0 = Player.of("player0");
        Player player1 = Player.of("player1");
        GameRegistry.getGame(testSessionId).addPlayer(player0);
        GameRegistry.getGame(testSessionId).addPlayer(player1);
        GameRegistry.getGame(testSessionId).start();

        // Set up: both players have 5 crosses in BLUE row (rowIndex 3)
        var game = GameRegistry.getGame(testSessionId);
        var state = game.currentState();

        for (Player p : new Player[]{player0, player1}) {
            var layout = state.sheetLayouts().get(p.id());
            var progress = state.boardState().sheetProgress().get(p.id());
            var blueRow = layout.rows().get(3);  // BLUE is rowIndex 3

            // Add 5 crosses in BLUE row (cells 2, 3, 4, 5, 6)
            Set<String> blueCrosses = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                blueCrosses.add(blueRow.cells().get(i).id());
            }
            progress.updateRowState(3, new RowState(blueCrosses, false));
        }

        UUID firstActive = activePlayerId(testSessionId);

        // Roll the dice
        mvc.perform(post("/moves/{sid}/{pid}", testSessionId, firstActive)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"ROLL"}
                        """))
                .andExpect(status().isOk());

        // Verify the state
        var afterRoll = GameRegistry.getGame(testSessionId).currentState();
        assertNotNull(afterRoll.turnState().currentRoll(), "Dice should be rolled");

        // Verify both players have 5 crosses in BLUE
        for (Player p : new Player[]{player0, player1}) {
            var blueState = afterRoll.boardState().sheetProgress().get(p.id()).rowStates().get(3);
            assertEquals(5, blueState.crossedCells().size(), p.name() + " should have 5 crosses in BLUE");
        }

        var roll = afterRoll.turnState().currentRoll();
        System.out.println("\n✓✓✓ TEST GAME SETUP COMPLETE ✓✓✓");
        System.out.println("sessionId: " + testSessionId);
        System.out.println("player0: " + player0.id());
        System.out.println("player1: " + player1.id());
        System.out.println("White dice rolled: " + roll.white1() + " + " + roll.white2() + " = " + (roll.white1() + roll.white2()));
        System.out.println("Both players: 5 crosses in BLUE row");
        System.out.println("Active player: " + firstActive);
        System.out.println("✓✓✓\n");
    }

    @Test
    void onlineGameCanLockAfterCrossingEligibleCell() throws Exception {
        // Create a game with just 1 player (no other players needed for this test)
        GameRegistry.clear();
        sessionId = GameRegistry.createGame("room", 1, GameSettings.builder().build());
        alice = Player.of("Alice");
        GameRegistry.getGame(sessionId).addPlayer(alice);
        GameRegistry.getGame(sessionId).start();

        // Manually set up state: set up 5 normal crosses + the last closing cell in GREEN row.
        // Having the closing cell permanently crossed allows an explicit DECLARE_LOCK_INTENT.
        var game = GameRegistry.getGame(sessionId);
        var state = game.currentState();
        var layout = state.sheetLayouts().get(alice.id());
        var progress = state.boardState().sheetProgress().get(alice.id());
        var greenRow = layout.rows().get(2);  // GREEN is rowIndex 2

        // Get the lock-eligible (closing) cell for GREEN (last cell, displayValue "2")
        Cell lockEligibleCell = greenRow.cells().get(greenRow.cells().size() - 1);
        assertEquals("2", lockEligibleCell.displayValue(), "Lock-eligible cell should have displayValue 2");

        // Add 5 normal crosses + the closing cell as PERMANENT crosses (enables explicit declare)
        Set<String> greenCrosses = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            greenCrosses.add(greenRow.cells().get(i).id());
        }
        greenCrosses.add(lockEligibleCell.id()); // permanent closing cell → DECLARE_LOCK_INTENT offered
        progress.updateRowState(2, new RowState(greenCrosses, false));

        // Roll the dice so the player can make a move
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"ROLL"}
                        """))
                .andExpect(status().isOk());

        // Declare lock intent on GREEN row — explicit action (permanent closing cell available)
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                        """, greenRow.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        // In the new architecture, DECLARE_LOCK_INTENT stays in ACTIVE_MOVE.
        var afterIntentBeforeEndTurn = GameRegistry.getGame(sessionId).currentState();
        assertEquals("ACTIVE_MOVE", afterIntentBeforeEndTurn.turnState().phase().toString(),
                "DECLARE_LOCK_INTENT must not change phase — stays in ACTIVE_MOVE");

        // The player still needs to cross something to be allowed to EndTurn.
        // Force dice to white+white=7 so we can cross a known cell in the RED row.
        var stateForCross = GameRegistry.getGame(sessionId).currentState();
        var existingColored = stateForCross.turnState().currentRoll().coloredDice();
        stateForCross.turnState().setCurrentRoll(new nl.adg.qwixx.data.RollResult(3, 4, existingColored));

        var redRow = layout.rows().get(0); // RED ascending row
        // Cell at pos 5 has displayValue "7" (matches white+white=3+4=7).
        Cell redCell7 = redRow.cells().get(5);
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                        """, redRow.id(), redCell7.id())))
                .andExpect(status().isOk());

        // EndTurn triggers EVALUATE which closes the row (single-player → no passives).
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"PASS"}
                        """))
                .andExpect(status().isOk());

        var afterIntent = GameRegistry.getGame(sessionId).currentState();
        assertEquals("ROLL", afterIntent.turnState().phase().toString(),
                "Single-player lock intent must close row at EndTurn (EVALUATE) — phase should be ROLL");

        // Verify the lock is marked crossed and the row is globally closed
        var greenStateLocked = afterIntent.boardState().sheetProgress().get(alice.id()).rowStates().get(2);
        assertTrue(greenStateLocked.lockCrossed(),
                "Lock should be marked crossed after EVALUATE closes the row");
        assertTrue(afterIntent.boardState().closedRows().containsKey(2),
                "GREEN row (index 2) should be in closedRows after EVALUATE");
    }
}