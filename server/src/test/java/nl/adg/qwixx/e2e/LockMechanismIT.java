package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.helpers.BoardInteractionHelper;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the row-lock mechanism.
 *
 * BLUE row (index 3) is DESCENDING: 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2
 * The closing-eligible cell is "2" (last position = 10).
 * Lock conditions: at least 5 crosses AND the "2" cell crossed.
 */
public class LockMechanismIT extends BaseIntegrationTest {

    private static final int BLUE_ROW_INDEX = 3;
    private static final int BLUE_ROW_ALL_CELLS = 11; // 12..2 inclusive
    private static final int RED_ROW_INDEX = 0;

    private WebDriver driver0;
    private WebDriver driver1;
    private String sessionId;
    private List<String> playerIds;

    @BeforeEach
    void createGame() {
        sessionId = api.createGame(2);
        playerIds = api.getPlayerIds(sessionId);
    }

    @AfterEach
    void tearDown() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
    }

    // ── Lock eligibility ───────────────────────────────────────────────────────
    // (The separate lock button no longer exists; lock eligibility is enforced by
    //  the server — the closing cell is simply not offered when conditions aren't met.
    //  Those rules are covered by backend unit tests.)

    @Test
    void lockBecomesAutoCrossedAfterCrossingClosingCell() {
        // Setup: 5 crosses (no "2"), dice 1+1 → white+white sum = 2 → "2" cell is reachable
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock should not be crossed before crossing '2'");

        // Crossing "2" auto-declares lock intent — no separate lock-button click needed.
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // The lock cross appears immediately on player0's board while the cell is pending
        // (frontend shows ✕ as soon as the last required cell is in pendingCellIds).
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear on player0's board as soon as '2' is pending");

        // Player1 sees the lock-intent modal and confirms → row closes.
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed after player1 acknowledges the auto-declared lock");
    }

    // ── Lock-intent modal: declaring player ───────────────────────────────────

    @Test
    void activePlayerSeesNoModalAfterCrossingClosingCell() {
        // Player 0 has 5 crosses (no "2"); clicking "2" auto-declares lock intent.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Wait for the auto-declare to reach LOCK_PENDING (lock cross appears on board).
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        // The declaring player must NOT see the row-closure modal.
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "The player who crossed the closing cell should NOT see the confirmation modal");
    }

    @Test
    void activePlayerSeesNoModalAfterDeclaringLockIntentViaApi() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        // Give the board time to poll and receive the new state
        TestUtils.wait(3000);

        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "The declaring player should NOT see the lock-intent modal");
    }

    // ── Lock-intent modal: other player ───────────────────────────────────────

    @Test
    void otherPlayerSeesModalWhenLockIntentDeclaredViaApi() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player 0 declares lock intent via API — player 1's browser is already open
        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        // Player 1's board polls every 2 s — wait for modal to appear
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);

        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Player 1 should see the lock-intent modal when player 0 declares intent");
    }

    @Test
    void modalContainsDeclaringPlayerName() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);

        String modalText = BoardInteractionHelper.getModalText(driver1);
        assertTrue(modalText.contains("player0"),
                "Modal should contain the declaring player's name 'player0'. Actual text: " + modalText);
    }

    @Test
    void modalContainsCorrectRowColorIndicator() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);

        assertTrue(BoardInteractionHelper.modalHasColorCell(driver1, "BLUE"),
                "Modal should display a BLUE color indicator (CSS class 'cell-blue')");
    }

    @Test
    void bothPlayersSeeLockIntentModalCorrectly() {
        // Comprehensive: player 0 no modal, player 1 has modal with player 0's name
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        TestUtils.wait(1000); // give player 0's board time to poll and NOT show modal

        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "Player 0 (declarer) should NOT see the modal");
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Player 1 (passive) SHOULD see the modal");
        assertTrue(BoardInteractionHelper.getModalText(driver1).contains("player0"),
                "Modal should name player 0 as the one who declared intent");
        assertTrue(BoardInteractionHelper.modalHasColorCell(driver1, "BLUE"),
                "Modal should show the BLUE color indicator");
    }

    // ── Three-player accumulation ─────────────────────────────────────────────

    /**
     * Scenario:
     *  - Player0 (active) declares lock intent for BLUE → player2 sees 1 request.
     *  - Player1 (passive, "in doubt") passes without locking.
     *  - Player1 changes mind: lock intent for RED is injected directly.
     *  - Player2's next poll shows BOTH requests in a single modal overlay — no
     *    second popup, the existing modal simply reflects the accumulated list.
     */
    @Test
    void threePlayersAccumulateLockIntentRequestsInSingleModal() {
        String sid3 = api.createGame(3);
        List<String> pids = api.getPlayerIds(sid3);

        // Player0 is lock-eligible for BLUE (all 11 cells crossed)
        api.setCrosses(sid3, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid3, pids.get(0));
        api.setDice(sid3, 1, 1);

        // Open player2's browser — they are the passive observer for this test
        driver1 = TestUtils.getDriver(sid3, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares lock intent for BLUE via the game API
        String blueRowId = api.getRowId(sid3, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid3, pids.get(0), blueRowId);

        // Player2's modal must appear and show exactly 1 request (player0 / BLUE)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertEquals(1, BoardInteractionHelper.getModalRequestCount(driver1),
                "Modal should show 1 request after player0 declares BLUE intent");

        // Player1 is "in doubt" — passes without locking
        api.pass(sid3, pids.get(1));

        // Player1 changes mind: inject a RED closure request directly
        api.addClosureRequest(sid3, "player1", "RED");

        // Player2 must see BOTH requests accumulate in the SAME modal overlay
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.getModalRequestCount(d) >= 2);

        assertEquals(2, BoardInteractionHelper.getModalRequestCount(driver1),
                "Modal should now show 2 accumulated requests (player0 BLUE + player1 RED)");
        assertTrue(BoardInteractionHelper.modalHasColorCell(driver1, "BLUE"),
                "Modal should display the BLUE color indicator for player0's intent");
        assertTrue(BoardInteractionHelper.modalHasColorCell(driver1, "RED"),
                "Modal should display the RED color indicator for player1's intent");
        String modalText = BoardInteractionHelper.getModalText(driver1);
        assertTrue(modalText.contains("player0"),
                "Modal text should mention player0. Actual: " + modalText);
        assertTrue(modalText.contains("player1"),
                "Modal text should mention player1. Actual: " + modalText);
    }

    // ── Longo: self-close yes/no modal ───────────────────────────────────────
    //
    // BLUE descending in LONGO: closing cells are "3" (second-to-last) and "2" (last).
    // Clicking "3" shows a yes/no modal asking whether to close the row.
    // After clicking Yes the lock cross (✕) must appear immediately on the lock icon —
    // the cross is shown as soon as the required cell is pending, before the full
    // lock declaration completes.

    @Test
    void longoYesOnSelfCloseModal_showsLockCrossImmediately() {
        String sid = api.createGame(1, java.util.Map.of("base", "LONGO"));
        String pid = api.getPlayerIds(sid).get(0);

        // 6 normal crosses (pos 0-5, values 16-11) — enough to make "3" eligible.
        // Dice white1=1 + white2=2 = 3, so "3" is reachable.
        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 6);
        api.roll(sid, pid);
        api.setDice(sid, 1, 2);

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must not be visible before crossing '3'");

        // Click "3" → yes/no modal appears; click Yes → cross is applied.
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        // Lock cross must appear immediately once "3" is pending — no extra click needed.
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear on the lock icon immediately after confirming Yes "
                + "on the self-close modal for the second-to-last Longo closing cell");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "Modal must be dismissed after clicking Yes");

        // In a single-player game the lock declaration auto-resolves: the row closes
        // and the turn advances to ROLL.  The player must see the Roll button, not the
        // Confirm button — confirming a move that is already done would be wrong.
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !d.findElements(By.cssSelector(".btn-roll")).isEmpty());

        assertFalse(driver0.findElements(By.cssSelector(".btn-confirm")).size() > 0
                        && driver0.findElement(By.cssSelector(".btn-confirm")).isDisplayed(),
                "Confirm button must NOT be shown after the lock auto-resolved — turn is over");
        assertTrue(driver0.findElement(By.cssSelector(".btn-roll")).isDisplayed(),
                "Roll button must be visible after the Longo lock auto-resolves — player must roll next");
    }

    // ── Single-player lock: Bug regression tests ──────────────────────────────
    //
    // Bug: in a 1-player game declaring lock intent entered LOCK_PENDING with
    // nobody in the passive queue, leaving the game permanently frozen.
    // Fix: when allNonActiveAcknowledged() is immediately true (no other players),
    //      applyDeclareLockIntent auto-applies CrossLockAction and closes the row.

    @Test
    void singlePlayerLockIntentImmediatelyClosesRowViaApi() {
        String sid = api.createGame(1);
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        String blueRowId = api.getRowId(sid, pid, BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pid, blueRowId); // must NOT freeze the game

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> closedRows =
                (java.util.Map<String, Object>) api.getGameState(sid).get("closedRows");
        assertFalse(closedRows == null || closedRows.isEmpty(),
                "BLUE row must be closed immediately after lock intent in a single-player game");
    }

    @Test
    void singlePlayerCrossingClosingCellClosesRowInBrowser() {
        String sid = api.createGame(1);
        String pid = api.getPlayerIds(sid).get(0);

        // 5 crosses (no "2") — clicking "2" will auto-declare lock intent.
        // With no other players to acknowledge, the row closes immediately.
        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 5);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must close in the browser after crossing '2' in a single-player game");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "No lock-intent modal should appear in a single-player game");
    }

    // ── Active player reset from LOCK_PENDING ─────────────────────────────────
    //
    // Bug: applyResetTurn restored the snapshot but did not transition the phase
    // back from LOCK_PENDING to ACTIVE_MOVE, so the declaring player had no valid
    // moves after resetting and the game was frozen for them too.

    @Test
    void activePlayerResetFromLockPendingGoesBackToActiveMove() {
        // Use the 2-player game from @BeforeEach so LOCK_PENDING is NOT auto-resolved
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        String phaseBefore = turnPhase(api.getGameState(sessionId));
        assertEquals("LOCK_PENDING", phaseBefore,
                "Phase should be LOCK_PENDING after declaring intent (player1 still must acknowledge)");

        api.resetTurn(sessionId, playerIds.get(0));

        String phaseAfter = turnPhase(api.getGameState(sessionId));
        assertEquals("ACTIVE_MOVE", phaseAfter,
                "Active player's ResetTurn from LOCK_PENDING must return phase to ACTIVE_MOVE");
    }

    @Test
    void activePlayerCanInteractWithBoardAfterResetFromLockPending() {
        // Player 0 crosses "2" (auto-declares lock intent) → lock cross appears.
        // Resetting must cancel the pending state: cross disappears, row stays open.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Confirm LOCK_PENDING was reached (lock cross visible).
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        // Reset cancels the lock declaration.
        api.resetTurn(sessionId, playerIds.get(0));

        // Lock cross must disappear and row must remain open.
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must be gone after the reset cancelled the lock declaration");
        assertFalse(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must NOT be closed — the reset cancelled the lock intent");
    }

    // ── Full 2-player lock flow: declare → passive confirms → row closes ─────

    /**
     * Verifies the end-to-end lock flow in a real browser:
     *  1. Active player (player0) declares lock intent via the UI lock button.
     *  2. Passive player (player1) sees the lock-intent modal.
     *  3. Passive player clicks "Confirm" (which sends PASS / EndTurn).
     *  4. Server auto-closes the row (last passive acknowledged → auto-resolve).
     *  5. Both players see the BLUE row as closed.
     */
    @Test
    void fullLockFlow_passiveConfirms_rowClosesInBothBrowsers() {
        // Player 0 crosses "2" (auto-declares lock intent).
        // Player 1 confirms → both browsers see BLUE row closed.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed in player0's browser after the full lock flow");
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "BLUE"),
                "BLUE row must be closed in player1's browser after the full lock flow");
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Lock-intent modal must be gone after the row closes");
    }

    // ── Passive player crosses lock row: modal must not reappear ─────────────
    //
    // Bug: after the passive player dismisses the row-closure modal and crosses the
    // locking row, suppressModal=true but pendingCellIds>0 → suppress=false, so the
    // same "do you want to change your last selection?" modal re-appears.
    //
    // Expected: crossing the locking row acknowledges the lock, the row closes, and
    // — since RED was already closed — the game ends. After the 1500 ms delay the
    // score screen appears.

    @Test
    @SuppressWarnings("unchecked")
    void passivePlayerCrossingLockRowSeesRowClosedThenScoreScreen() {
        String p0 = playerIds.get(0);
        String p1 = playerIds.get(1);

        // === Part 1: close RED row via API (first locked row) ===
        api.setCrosses(sessionId, p0, RED_ROW_INDEX, 11);
        api.roll(sessionId, p0);
        api.setDice(sessionId, 1, 1);

        String redRowId = api.getRowId(sessionId, p0, RED_ROW_INDEX);
        api.declareLockIntent(sessionId, p0, redRowId);
        api.pass(sessionId, p1); // player1 acknowledges → RED closes

        Map<String, Object> afterRedLock = api.getGameState(sessionId);
        if ("PASSIVE_MOVE".equals(turnPhase(afterRedLock))) {
            api.pass(sessionId, p1);
        }

        // === Part 2: advance player1's active turn ===
        Map<String, Object> beforeP1Turn = api.getGameState(sessionId);
        assertEquals("ROLL", turnPhase(beforeP1Turn),
                "Expected ROLL phase for player1's turn after RED row closed");

        String yellowRowId = api.getRowId(sessionId, p1, 1);
        String p1YellowCellId = getCellId(sessionId, p1, 1, 0);
        api.roll(sessionId, p1);
        api.setDice(sessionId, 1, 1);
        api.crossCell(sessionId, p1, yellowRowId, p1YellowCellId, false);
        api.pass(sessionId, p1);
        api.pass(sessionId, p0);

        // === Part 3: player0's second active turn — set up BLUE lock ===
        // "2" is pre-crossed via API so the browser can't click it again;
        // the lock declaration is sent directly via the API.
        api.setCrosses(sessionId, p0, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, p1, BLUE_ROW_INDEX, 5);
        api.roll(sessionId, p0);
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, p0);
        driver1 = TestUtils.getDriver(sessionId, p1);
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // === Part 4: player0 declares lock intent for BLUE via API ===
        String blueRowId = api.getRowId(sessionId, p0, BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, p0, blueRowId);

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Player1 should see the row-closure modal after player0 declares BLUE lock intent");

        // === Part 5: player1 acknowledges via the OK button ===
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        // === Part 6: BLUE row must close after acknowledgement ===
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Modal must be gone after player1 acknowledges via OK");

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        // === Part 7: player1 passes their final PASSIVE_MOVE before the game ends ===
        // After a game-ending lock close, unacted passives get one final white+white turn.
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // === Part 8: game ends (RED + BLUE = 2 locked rows); score screen appears ===
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver1.getCurrentUrl().contains("/score"),
                "Player1 must be navigated to the score screen after the game ends. "
                + "Current URL: " + driver1.getCurrentUrl());
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Player0 must be navigated to the score screen after the game ends. "
                + "Current URL: " + driver0.getCurrentUrl());
    }

    @SuppressWarnings("unchecked")
    private String getCellId(String sid, String playerId, int rowIndex, int cellIndex) {
        Map<String, Object> state   = api.getGameState(sid);
        Map<String, Object> layouts = (Map<String, Object>) state.get("sheetLayouts");
        Map<String, Object> layout  = (Map<String, Object>) layouts.get(playerId);
        List<Map<String, Object>> rows  = (List<Map<String, Object>>) layout.get("rows");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) rows.get(rowIndex).get("cells");
        return (String) cells.get(cellIndex).get("id");
    }

    @SuppressWarnings("unchecked")
    private static String turnPhase(java.util.Map<String, Object> state) {
        java.util.Map<String, Object> turn = (java.util.Map<String, Object>) state.get("turnState");
        return turn == null ? null : (String) turn.get("phase");
    }
}
