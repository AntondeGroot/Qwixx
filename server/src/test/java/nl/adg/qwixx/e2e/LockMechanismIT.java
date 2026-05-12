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

        // Player1 confirms, then completes their passive slot → row closes.
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed after player1 completes their passive slot");
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
    void longoYesOnSelfCloseModal_showsLockCrossPendingThenFiresWhenLastCrossed() {
        // Clicking YES on the second-to-last Longo modal crosses "3" and shows the lock ✕
        // immediately as a pending indicator.  The lock actually fires only when the last
        // required cell "2" is also crossed.  Set the BLUE die so "2" is reachable (white1+BLUE = 1+1 = 2).
        String sid = api.createGame(1, java.util.Map.of("base", "LONGO"));
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 6);
        api.roll(sid, pid);
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1); // white1(1) + BLUE(1) = 2 → "2" reachable

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click "3" (second-to-last required cell) → modal appears → Yes
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        // "3" must be crossed; lock cross MUST appear immediately as a pending indicator
        // (the player said YES, so we show intent even though "2" is still needed)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.getCrossedCellCount(d, "BLUE") >= 7);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear after clicking YES on '3' — pending indicator while '2' is still needed");

        // Click "2" (colored die: white1+BLUE = 1+1 = 2) → lock fires automatically
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must close after crossing '2' (the last required cell)");

        // Single-player: lock auto-resolves → turn advances to ROLL
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !d.findElements(By.cssSelector(".btn-roll")).isEmpty());
        assertTrue(driver0.findElement(By.cssSelector(".btn-roll")).isDisplayed(),
                "Roll button must be visible after the Longo lock auto-resolves");
    }

    @Test
    void longoYesOnSelfCloseModal_passivePlayerSeesModalImmediately() {
        // When the active player clicks "3" (second-to-last required cell) and clicks YES,
        // DECLARE_LOCK_INTENT is sent immediately. The passive player must see the row-closure
        // modal even before the active player has crossed "2" (the last required cell).
        String sid = api.createGame(2, java.util.Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1); // white+BLUE = 1+1 = 2 → "2" reachable later

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Active player clicks "3" → YES
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        // Passive player must see the row-closure modal immediately after YES
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Passive player must see the row-closure modal after active clicks YES "
                + "on the second-to-last Longo cell — lock intent declared before '2' is crossed");
    }

    // ── Passive closes own row while acknowledging an active lock ─────────────
    //
    // Scenario: player0 (active) has all 11 crosses in BLUE → declares BLUE lock.
    // Player1 (passive) has 5 crosses in RED and white+white=12 → crosses RED "12"
    // during their BLUE acknowledgement slot.
    //
    // Expected behaviour:
    //   1. Lock ✕ appears on player1's RED immediately (pendingAutoLock indicator).
    //   2. Lock ✕ stays visible after player1 EndTurns (completing BLUE passive slot).
    //   3. After BLUE closes, player1 auto-declares RED intent (checkPendingAutoLock).
    //   4. Player0 sees the RED row-closure modal.
    //   5. Player0 confirms and completes → RED closes → 2 rows locked → game over.

    @Test
    void passive_crossesClosingCell_lockCrossStaysVisible_afterEndTurn() {
        // Setup: 2-player, standard Qwixx. Player0 already has all BLUE cells crossed.
        // Player1 has 5 crosses in RED. white+white=12 → RED "12" reachable.
        String sid = api.createGame(2);
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6);   // white+white = 12 → RED "12" (standard Qwixx closing cell)

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares BLUE lock via API (already has all crosses)
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 sees the BLUE modal; must dismiss it first (it blocks cell clicks)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);   // acknowledge BLUE
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));

        // Player1 crosses RED "12" (white+white = 12)
        BoardInteractionHelper.clickCellByValue(driver1, "RED", "12");

        // Wait for the cross to be reflected in state before proceeding (lock ✕ as sync point)
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // Player1 clicks confirm (green button — pending cross commits the BLUE passive slot)
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // BLUE fires (sole passive completed) → player1 reinvited → auto-declares RED.
        // Player0's browser now sees the RED row-closure modal — use this as a reliable
        // synchronisation point: once player0 sees it, the auto-declare has fully completed
        // and player1's isDeclarantLockPending indicator is stable.
        BoardInteractionHelper.waitUntilModalVisible(driver0, 10);

        // Lock ✕ must be visible on player1's RED row via isDeclarantLockPending
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver1, "RED"),
                "RED lock cross must be visible after player1 auto-declared RED intent — "
                + "isDeclarantLockPending shows the ✕ once rowClosureRequests contains RED");
    }

    @Test
    void passive_crossesClosingCell_redAutoDeclaresAfterBlueFires_gameEnds() {
        // Full end-to-end: player1 crosses RED "12" during BLUE LOCK_PENDING.
        // After BLUE fires, player1 auto-declares RED. Player0 acknowledges → RED closes → game over.
        String sid = api.createGame(2);
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6);   // white+white = 12 → RED "12"

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares BLUE lock
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 acknowledges BLUE (dismiss modal), then crosses RED "12", then completes slot
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);   // acknowledge BLUE
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        BoardInteractionHelper.clickCellByValue(driver1, "RED", "12");
        // Wait for RED cross to be reflected in state (lock ✕ sync point) before completing slot.
        // Without this, waitUntilPassButtonVisible can return immediately (pass button was already
        // visible), causing clickPassButton to cancel the in-flight CROSS_WHITE_WHITE request.
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // BLUE fires (sole passive completed slot) → player1 reinvited → auto-declares RED
        // → player0 sees RED modal
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE must close after player1 completes passive slot");

        // Player0 sees the RED row-closure modal (player1 auto-declared RED)
        BoardInteractionHelper.waitUntilModalVisible(driver0, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver0),
                "Player0 must see RED row-closure modal after player1 auto-declares RED intent");

        // Player0 confirms RED → sees a pass button (passive declared during PASSIVE_MOVE reinvite,
        // so no auto-pass) → clicks pass → RED fires → game over (BLUE + RED = 2 rows).
        BoardInteractionHelper.clickModalConfirmButton(driver0);
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        // Both rows closed → game over → all browsers navigate to /score
        new WebDriverWait(driver0, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Player0 must be on the score screen after 2 rows closed");
        assertTrue(driver1.getCurrentUrl().contains("/score"),
                "Player1 must be on the score screen after 2 rows closed");
    }

    @Test
    void passive_crossesClosingCell_threePlayer_player2SeesRedModal() {
        // 3-player: player0 declares BLUE, player1 crosses RED "12" during BLUE LOCK_PENDING.
        // After BLUE fires, player1 auto-declares RED. Player2 must see the RED modal.
        String sid = api.createGame(3);
        List<String> pids = api.getPlayerIds(sid);
        WebDriver driver2 = null;
        try {
            api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
            api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
            api.roll(sid, pids.get(0));
            api.setDice(sid, 6, 6);   // white+white = 12

            driver0 = TestUtils.getDriver(sid, pids.get(0));
            driver1 = TestUtils.getDriver(sid, pids.get(1));
            driver2 = TestUtils.getDriver(sid, pids.get(2));
            TestUtils.waitUntilBoardLoaded(driver0);
            TestUtils.waitUntilBoardLoaded(driver1);
            TestUtils.waitUntilBoardLoaded(driver2);

            // Player0 declares BLUE
            String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
            api.declareLockIntent(sid, pids.get(0), blueRowId);
            BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
            BoardInteractionHelper.waitUntilModalVisible(driver2, 8);

            // Player1 acknowledges BLUE (dismiss modal), then crosses RED "12", then completes slot
            BoardInteractionHelper.clickModalConfirmButton(driver1);
            new WebDriverWait(driver1, Duration.ofSeconds(5))
                    .until(d -> !BoardInteractionHelper.isModalVisible(d));
            BoardInteractionHelper.clickCellByValue(driver1, "RED", "12");
            // Wait for RED cross to be reflected in state (lock ✕ sync point) before completing slot.
            new WebDriverWait(driver1, Duration.ofSeconds(5))
                    .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
            BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
            BoardInteractionHelper.clickPassButton(driver1);

            // Player2 acknowledges BLUE and completes slot → BLUE fires → player1 reinvited → auto-declares RED
            BoardInteractionHelper.clickModalConfirmButton(driver2);
            BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
            BoardInteractionHelper.clickPassButton(driver2);

            // BLUE must be closed
            new WebDriverWait(driver0, Duration.ofSeconds(8))
                    .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

            // Player2 must see RED modal (player1 auto-declared RED intent after being reinvited)
            BoardInteractionHelper.waitUntilModalVisible(driver2, 10);
            assertTrue(BoardInteractionHelper.isModalVisible(driver2),
                    "Player2 must see RED row-closure modal after player1 auto-declares RED "
                    + "— this verifies the passive auto-declare chain works in 3-player games");
        } finally {
            if (driver2 != null) driver2.quit();
        }
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
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed in player0's browser after player1 completes their slot");
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "BLUE"),
                "BLUE row must be closed in player1's browser after player1 completes their slot");
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
        api.pass(sessionId, p1); // player1 acknowledges
        api.pass(sessionId, p1); // player1 completes passive slot → RED closes

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

        // === Part 6: modal must close, then player1 completes their final passive-move slot ===
        // In a game-ending lock, player1 stays in queue after acknowledging so they can
        // make one final white+white cross.  The BLUE row closes once they pass/complete.
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Modal must be gone after player1 acknowledges via OK");

        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // === Part 7: BLUE row must close after player1 completes their passive slot ===
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

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
