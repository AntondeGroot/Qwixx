package nl.adg.qwixx.web;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.GameMode;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.api.MovesApiController;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MovesApiController.class)
@Import({MovesApiDelegateImpl.class, GlobalExceptionHandler.class})
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
        roll(sid, alice.id());

        // Passive player passes (EndTurn) while active player hasn't finished yet
        move(sid, bob.id(), """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void simultaneousTurn_passivePlayerCanCrossAndConfirmDuringActiveMovePhase() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        roll(sid, alice.id());

        CellTarget target = findWhiteWhiteCell(sid, bob.id());

        move(sid, bob.id(), """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        // Confirm the cross with PASS — equivalent of "End Turn" for passive player
        move(sid, bob.id(), """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void simultaneousTurn_activePlayerEndTurnAfterCrossGoesToNextTurnWhenPassiveDone() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        roll(sid, alice.id());

        // Bob finishes first (passive)
        move(sid, bob.id(), """
                {"moveType":"PASS"}
                """).andExpect(status().isOk());

        // Alice crosses with color die and ends turn — queue already empty → evaluate
        CellTarget target = findColorDieCell(sid, alice.id());
        move(sid, alice.id(), """
                {"moveType":"CROSS_COLOR_DIE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        move(sid, alice.id(), """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void activePlayerCannotEndTurnBeforeMakingAnyMove() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        roll(sid, alice.id());

        move(sid, alice.id(), """
                {"moveType":"PASS"}
                """)
                .andExpect(status().isBadRequest());
    }

    // ── Lock flow ─────────────────────────────────────────────────────────────

    @Test
    void lockFlow_canDeclareLockIntentAfterCrossingClosingEligibleCell() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        setupEnoughCrossesForLock(sid, alice.id(), 0);
        roll(sid, alice.id());

        String rowId = GameRegistry.getGame(sid).currentState()
                .sheetLayouts().get(alice.id()).rows().get(0).id();

        move(sid, alice.id(), """
                {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                """.formatted(rowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    @Test
    void lockFlow_passivePlayerCanUndoCrossWhileLockPending() throws Exception {
        Player bob = Player.of("Bob");
        String sid = twoPlayerSession(alice, bob);
        roll(sid, alice.id());

        // Bob crosses a cell during ACTIVE_MOVE (simultaneous)
        CellTarget target = findWhiteWhiteCell(sid, bob.id());
        move(sid, bob.id(), """
                {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                """.formatted(target.rowId(), target.cellId()))
                .andExpect(status().isOk());

        // Alice accumulates enough crosses to declare lock intent
        setupEnoughCrossesForLock(sid, alice.id(), 0);

        String rowId = GameRegistry.getGame(sid).currentState()
                .sheetLayouts().get(alice.id()).rows().get(0).id();

        move(sid, alice.id(), """
                {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                """.formatted(rowId))
                .andExpect(status().isOk());

        // Bob can undo his cross from the simultaneous phase
        move(sid, bob.id(), """
                {"moveType":"UNDO_LAST_CROSS"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record CellTarget(String rowId, String cellId) {}

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

        Set<String> crossed = new HashSet<>(lock.requiredCells());
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
    void rowClosureRequestsInitiallyEmpty() throws Exception {
        var gameState = GameRegistry.getGame(sessionId).currentState();
        assertNotNull(gameState.rowClosureRequests());
        assertEquals(0, gameState.rowClosureRequests().size());
    }

    @Test
    void rowClosureRequestsClearedOnResetTurn() throws Exception {
        // Add row closure requests manually (simulating DECLARE_LOCK_INTENT)
        var state = GameRegistry.getGame(sessionId).currentState();
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("Bob", nl.adg.qwixx.data.Color.RED)
        );

        // Verify requests populated
        assertEquals(1, state.rowClosureRequests().size());

        // Reset turn should clear requests
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"RESET_TURN"}
                        """))
                .andExpect(status().isOk());

        // Verify cleared
        var afterReset = GameRegistry.getGame(sessionId).currentState();
        assertEquals(0, afterReset.rowClosureRequests().size());
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

        // Roll the dice: white1=1, white2=1 (total=2)
        mvc.perform(post("/moves/{sid}/{pid}", testSessionId, player0.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"ROLL"}
                        """))
                .andExpect(status().isOk());

        // Verify the state
        var afterRoll = GameRegistry.getGame(testSessionId).currentState();
        assertNotNull(afterRoll.turnState().currentRoll(), "Dice should be rolled");

        // Verify player0 is active
        assertEquals(player0.id().toString(), afterRoll.turnState().activePlayerId().toString());

        // Verify both players have 5 crosses in BLUE
        for (Player p : new Player[]{player0, player1}) {
            var blueState = afterRoll.boardState().sheetProgress().get(p.id()).rowStates().get(3);
            assertEquals(5, blueState.crossedCells().size(), p.name() + " should have 5 crosses in BLUE");
        }

        // Print info for manual use
        var roll = afterRoll.turnState().currentRoll();
        System.out.println("\n✓✓✓ TEST GAME SETUP COMPLETE ✓✓✓");
        System.out.println("sessionId: " + testSessionId);
        System.out.println("player0: " + player0.id());
        System.out.println("player1: " + player1.id());
        System.out.println("White dice rolled: " + roll.white1() + " + " + roll.white2() + " = " + (roll.white1() + roll.white2()));
        System.out.println("Both players: 5 crosses in BLUE row");
        System.out.println("Active player: player0");
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

        // Manually set up state: roll the dice and set up 5 crosses in GREEN row
        var game = GameRegistry.getGame(sessionId);
        var state = game.currentState();
        var layout = state.sheetLayouts().get(alice.id());
        var progress = state.boardState().sheetProgress().get(alice.id());
        var greenRow = layout.rows().get(2);  // GREEN is rowIndex 2

        // Add 5 crosses in GREEN row (cells 12, 11, 10, 9, 8)
        Set<String> greenCrosses = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            greenCrosses.add(greenRow.cells().get(i).id());
        }
        progress.updateRowState(2, new RowState(greenCrosses, false));

        // Now roll the dice so the player can make a move
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"moveType":"ROLL"}
                        """))
                .andExpect(status().isOk());

        // Get the lock-eligible cell for GREEN (displayValue "2", which is at the end of the row)
        Cell lockEligibleCell = greenRow.cells().get(greenRow.cells().size() - 1);
        assertEquals("2", lockEligibleCell.displayValue(), "Lock-eligible cell should have displayValue 2");

        // Cross the lock-eligible cell (this makes lock intent available)
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"moveType":"CROSS_WHITE_WHITE","rowId":"%s","cellId":"%s"}
                        """, greenRow.id(), lockEligibleCell.id())))
                .andExpect(status().isOk());

        // Now declare lock intent on GREEN row
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"moveType":"DECLARE_LOCK_INTENT","rowId":"%s"}
                        """, greenRow.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        // Verify lock intent was accepted, game moved to LOCK_PENDING phase
        var afterIntent = GameRegistry.getGame(sessionId).currentState();
        assertEquals("LOCK_PENDING", afterIntent.turnState().phase().toString(),
                "Game should move to LOCK_PENDING phase after declaring lock intent");

        // Now confirm the lock by crossing the lock cell (only possible in LOCK_PENDING phase)
        mvc.perform(post("/moves/{sid}/{pid}", sessionId, alice.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"moveType":"CROSS_LOCK","rowId":"%s"}
                        """, greenRow.id())))
                .andExpect(status().isOk());

        // Verify the lock cell is now marked as locked
        var afterLock = GameRegistry.getGame(sessionId).currentState();
        var greenStateLocked = afterLock.boardState().sheetProgress().get(alice.id()).rowStates().get(2);
        assertTrue(greenStateLocked.lockCrossed(), "Lock should be marked as crossed after lock confirmed");
    }
}