package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.helpers.BoardInteractionHelper;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

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

    @Test
    void lockNotClickableWithFewerThanFiveCrosses() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 4);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        assertFalse(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock should not be clickable with only 4 crosses (minimum is 5)");
    }

    @Test
    void lockNotClickableWithFiveCrossesButNoClosingCell() {
        // First 5 cells = values 12, 11, 10, 9, 8 — does not include "2"
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        assertFalse(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock should not be clickable without the closing-eligible cell '2'");
    }

    @Test
    void lockBecomesClickableAfterCrossingClosingCell() {
        // Setup: 5 crosses (no "2"), dice 1+1 → white+white sum = 2 → "2" cell is reachable
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        assertFalse(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock should not be clickable before crossing '2'");

        // Cross the closing-eligible "2" cell via the browser
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Wait for the cross to register and the board to update
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.getCrossedCellCount(d, "BLUE") >= 6);

        assertTrue(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock should be clickable after crossing the closing-eligible cell '2'");
    }

    @Test
    void lockClickableImmediatelyWhenAllCellsIncludingClosingAreCrossed() {
        // All 11 cells crossed (includes the closing-eligible "2")
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        assertTrue(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock should be clickable immediately when all cells including '2' are crossed");
    }

    // ── Lock-intent modal: declaring player ───────────────────────────────────

    @Test
    void activePlayerSeesNoModalAfterClickingLock() {
        // Player 0 is lock-eligible: 5+ crosses and "2" crossed
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Player 0 clicks the lock cell → sends DECLARE_LOCK_INTENT
        BoardInteractionHelper.clickLockButton(driver0, "BLUE");

        // Brief pause to allow any unwanted modal to render
        TestUtils.wait(1000);

        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "The player who declared lock intent should NOT see the confirmation modal");
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
    void singlePlayerClickingLockClosesRowInBrowser() {
        String sid = api.createGame(1);
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        assertTrue(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock button should be clickable before clicking (all 11 cells + closing cell crossed)");

        BoardInteractionHelper.clickLockButton(driver0, "BLUE");

        // Row must close without the game getting stuck — wait up to 8 s for the poll
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row should be closed in the browser after clicking lock in a single-player game");
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
        // Verify the browser is not frozen after the reset — player can click the lock again
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click lock → enters LOCK_PENDING (2-player, not auto-resolved)
        BoardInteractionHelper.clickLockButton(driver0, "BLUE");

        // Simulate the player changing their mind: send RESET_TURN via API
        api.resetTurn(sessionId, playerIds.get(0));

        // Board polls every 2 s — wait for it to pick up the new ACTIVE_MOVE phase
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isLockButtonClickable(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isLockButtonClickable(driver0, "BLUE"),
                "Lock button should be clickable again after resetting from LOCK_PENDING "
                + "(player is back in ACTIVE_MOVE with all crosses intact)");
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
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        // Open both browsers with the state already loaded (no animation triggered)
        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares lock intent via the UI lock button
        BoardInteractionHelper.clickLockButton(driver0, "BLUE");

        // Player1's board must show the lock-intent modal
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);

        // Player1 clicks "Confirm" → sends PASS → last passive acknowledged → row auto-closes
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        // Both players must see BLUE row closed (server auto-closes on last passive EndTurn)
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

    @SuppressWarnings("unchecked")
    private static String turnPhase(java.util.Map<String, Object> state) {
        java.util.Map<String, Object> turn = (java.util.Map<String, Object>) state.get("turnState");
        return turn == null ? null : (String) turn.get("phase");
    }
}
