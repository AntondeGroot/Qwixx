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
 * New architecture: DECLARE_LOCK_INTENT does NOT change phase to LOCK_PENDING.
 * Row closes at EVALUATE — after active EndTurns (→ PASSIVE_MOVE) and all passives EndTurn.
 * Passives need 1 EndTurn (not 2) to complete their slot.
 *
 * BLUE row (index 3) is DESCENDING: 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2
 * The closing-eligible cell is "2" (last position = 10).
 * Lock conditions: at least 5 crosses AND the "2" cell crossed (or pending).
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

        // Crossing "2" auto-declares lock intent (client sends DECLARE_LOCK_INTENT after cross)
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Lock cross appears immediately on player0's board (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear on player0's board as soon as '2' is pending");

        // Player1 sees the notification modal and dismisses it (OK = notification only, not EndTurn)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);  // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);  // player1 EndTurns via board pass button

        // Player0 EndTurns → passive queue empty → EVALUATE → row closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed after both players complete their turns");
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

        // Wait for lock cross to appear (sync point for auto-declare completion)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        // The declaring player must NOT see the row-closure modal
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

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

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

    @Test
    void threePlayersAccumulateLockIntentRequestsInSingleModal() {
        String sid3 = api.createGame(3);
        List<String> pids = api.getPlayerIds(sid3);

        api.setCrosses(sid3, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid3, pids.get(0));
        api.setDice(sid3, 1, 1);

        // Open player2's browser — they are the passive observer
        driver1 = TestUtils.getDriver(sid3, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares lock intent for BLUE
        String blueRowId = api.getRowId(sid3, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid3, pids.get(0), blueRowId);

        // Player2's modal must appear and show exactly 1 request (player0 / BLUE)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertEquals(1, BoardInteractionHelper.getModalRequestCount(driver1),
                "Modal should show 1 request after player0 declares BLUE intent");

        // Inject a RED closure request directly (player1's UUID as playerId)
        api.addClosureRequest(sid3, pids.get(1), "RED");

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
    // Clicking "3" shows a YES/NO modal. YES fires DECLARE_LOCK_INTENT immediately.
    // After clicking "2", the row auto-closes at the active player's EndTurn.

    @Test
    void longoYesOnSelfCloseModal_showsLockCrossPendingThenFiresWhenLastCrossed() {
        String sid = api.createGame(1, java.util.Map.of("base", "LONGO"));
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 6);
        api.roll(sid, pid);
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1); // white1(1) + BLUE(1) = 2 → "2" reachable

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click "3" (second-to-last closing cell) → YES/NO modal appears → Yes
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        // "3" must be crossed; lock cross MUST appear immediately (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.getCrossedCellCount(d, "BLUE") >= 7);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear after clicking YES on '3'");

        // Click "2" (colored die: white1+BLUE = 1+1 = 2) → row closes via active EndTurn
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Single-player: no passives → active EndTurn → EVALUATE → row closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must close after clicking '2' and confirming");

        // Turn advances to ROLL
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !d.findElements(By.cssSelector(".btn-roll")).isEmpty());
        assertTrue(driver0.findElement(By.cssSelector(".btn-roll")).isDisplayed(),
                "Roll button must be visible after the lock resolves");
    }

    @Test
    void longoYesOnSelfCloseModal_passivePlayerSeesModalImmediately() {
        String sid = api.createGame(2, java.util.Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1);

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Active player clicks "3" → YES → DECLARE_LOCK_INTENT sent immediately
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        // Passive player must see the row-closure modal immediately after YES
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Passive player must see the row-closure modal after active clicks YES on second-to-last cell");
    }

    @Test
    void passive_crossesSecondToLastLockCell_seesLockCrossBeforeGameEnds() {
        // Regression test for: passive clicked YES on the lock-confirm modal after the active
        // already declared the same row. The notification modal must stay suppressed so the
        // passive can see their own lock cross on the board (not hidden behind the modal).
        //
        // Setup: both players have 6 crosses in BLUE. white+white=3 → "3" (second-to-last
        // closing cell) is reachable. Active declares first; passive follows.
        String sid = api.createGame(2, java.util.Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.setCrosses(sid, pids.get(1), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2); // white+white = 3 → "3" reachable

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // player0 (active) crosses "3" → YES/NO modal → YES → DECLARE_LOCK_INTENT fires
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver0, 5);
        BoardInteractionHelper.clickModalYesButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "player0 must see BLUE lock cross after clicking YES");

        // player1 (passive) sees the notification modal and dismisses it (OK = notification only)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        // player1 crosses "3" → YES/NO lock-confirm modal appears → YES
        BoardInteractionHelper.clickCellByValue(driver1, "BLUE", "3");
        BoardInteractionHelper.waitUntilModalVisible(driver1, 5);
        BoardInteractionHelper.clickModalYesButton(driver1);

        // KEY ASSERTIONS: player1 must see the BLUE lock cross on their board AND
        // the notification modal must NOT re-appear and cover it.
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver1, "BLUE"),
                "player1 must see BLUE lock cross after clicking YES on '3'");
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Notification modal must NOT cover the board — player1 must see their lock cross");

        // player1 EndTurns via board confirm button (pending cross → hasPendingCross=true)
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // player0 EndTurns → passive queue empty → EVALUATE → BLUE closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE must close after EVALUATE");
    }

    // ── Passive closes own row while active also declared ─────────────────────
    //
    // Player0 (active) declares BLUE. Player1 (passive) also has RED "12" reachable.
    // Player1 crosses RED "12" (client auto-declares RED intent).
    // Both are in pendingClosures → EVALUATE closes both → game ends.

    @Test
    void passive_crossesClosingCell_lockCrossStaysVisible_afterEndTurn() {
        String sid = api.createGame(2);
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6); // white+white = 12 → RED "12"

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares BLUE lock via API
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 sees the BLUE modal and dismisses it (OK = notification, not EndTurn)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1); // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1); // player1 EndTurns via board pass button

        // Player0 EndTurns → passive queue empty → EVALUATE → BLUE closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE must close after both players complete their turns");
    }

    @Test
    void passive_crossesClosingCell_redAutoDeclaresAfterBlueFires_gameEnds() {
        // Player0 declares BLUE. Player1 crosses RED "12" → auto-declares RED.
        // Both rows in pendingClosures → EVALUATE → both close → game over.
        String sid = api.createGame(2);
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6); // white+white = 12 → RED "12"

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        driver1 = TestUtils.getDriver(sid, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Player0 declares BLUE lock
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 sees BLUE modal; dismisses it (OK = notification only) so they can cross RED "12"
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1); // dismiss notification

        // Player1 can cross while still in passive queue (modal no longer blocking)
        BoardInteractionHelper.clickCellByValue(driver1, "RED", "12");

        // Wait for RED lock cross to appear (client auto-declared RED intent)
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver1, "RED"),
                "RED lock cross must appear after player1 crosses '12'");

        // Modal reappears (hasPendingCross=true); player1 confirms (EndTurn)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        // Player0 sees notification about player1's RED declaration; dismiss it first
        BoardInteractionHelper.waitUntilModalVisible(driver0, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver0);

        // Player0 EndTurns → EVALUATE → both BLUE and RED close → game over
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        // Both rows closed → game over → browsers navigate to /score
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
        // 3-player: player0 declares BLUE, player1 crosses RED "12" (auto-declares RED).
        // Both rows in pendingClosures → EVALUATE closes both → player2 doesn't need to see RED modal.
        String sid = api.createGame(3);
        List<String> pids = api.getPlayerIds(sid);
        WebDriver driver2 = null;
        try {
            api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
            api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
            api.roll(sid, pids.get(0));
            api.setDice(sid, 6, 6); // white+white = 12

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

            // Player1 dismisses the BLUE modal (OK = notification only) to cross RED "12"
            BoardInteractionHelper.clickModalConfirmButton(driver1); // dismiss notification

            // Player1 crosses RED "12" (modal no longer blocking)
            BoardInteractionHelper.clickCellByValue(driver1, "RED", "12");
            new WebDriverWait(driver1, Duration.ofSeconds(5))
                    .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

            // Modal reappears for player1 (hasPendingCross=true); Confirm = EndTurn
            BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
            BoardInteractionHelper.clickModalConfirmButton(driver1); // player1 EndTurns (hasPendingCross=true)

            // Player2 dismisses their notification modal, then EndTurns via pass button
            BoardInteractionHelper.clickModalConfirmButton(driver2); // dismiss notification
            BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
            BoardInteractionHelper.clickPassButton(driver2); // player2 EndTurns

            // Player0 sees notification about player1's RED declaration; dismiss it first
            BoardInteractionHelper.waitUntilModalVisible(driver0, 8);
            BoardInteractionHelper.clickModalConfirmButton(driver0);

            // Player0 EndTurns → EVALUATE → BLUE and RED close → game over (2 rows)
            BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
            BoardInteractionHelper.clickPassButton(driver0);

            new WebDriverWait(driver0, Duration.ofSeconds(10))
                    .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
            assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                    "BLUE must close after all players complete their turns");
            assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                    "RED must also close at EVALUATE (both were pending)");
        } finally {
            if (driver2 != null) driver2.quit();
        }
    }

    // ── Single-player lock ─────────────────────────────────────────────────────

    @Test
    void singlePlayerLockIntentClosesRowAfterEndTurn() {
        // In a 1-player game: declareLockIntent records the intent; EndTurn triggers EVALUATE.
        String sid = api.createGame(1);
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        String blueRowId = api.getRowId(sid, pid, BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pid, blueRowId); // records intent, phase stays ACTIVE_MOVE
        api.pass(sid, pid); // EndTurn → EVALUATE → row closes

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> closedRows =
                (java.util.Map<String, Object>) api.getGameState(sid).get("closedRows");
        assertFalse(closedRows == null || closedRows.isEmpty(),
                "BLUE row must be closed after EndTurn in a single-player game");
    }

    @Test
    void singlePlayerCrossingClosingCellClosesRowInBrowser() {
        String sid = api.createGame(1);
        String pid = api.getPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 5);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        driver0 = TestUtils.getDriver(sid, pid);
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click "2" → client auto-declares intent → lock cross appears
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        // Click green Confirm → EndTurn → EVALUATE → row closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must close after crossing '2' and confirming in a single-player game");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "No lock-intent modal should appear in a single-player game");
    }

    // ── Active player reset cancels closing intent ─────────────────────────────

    @Test
    void activePlayerDeclaresIntent_thenResets_intentCancelled() {
        // In the new architecture, declaring intent does NOT change phase.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        // Phase must stay ACTIVE_MOVE (not LOCK_PENDING — that no longer exists)
        String phaseBefore = turnPhase(api.getGameState(sessionId));
        assertEquals("ACTIVE_MOVE", phaseBefore,
                "Declaring intent must NOT change the phase — it stays ACTIVE_MOVE");

        api.resetTurn(sessionId, playerIds.get(0));

        // Phase stays ACTIVE_MOVE, pending closure is cancelled
        String phaseAfter = turnPhase(api.getGameState(sessionId));
        assertEquals("ACTIVE_MOVE", phaseAfter,
                "Phase must stay ACTIVE_MOVE after reset");
    }

    @Test
    void activePlayerCanInteractWithBoardAfterResetFromPendingIntent() {
        // Crossing "2" sets pendingAutoLock on client; resetting must cancel it.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Lock cross appears (pendingAutoLock)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        // Reset cancels the pending intent
        api.resetTurn(sessionId, playerIds.get(0));

        // Lock cross must disappear and row must remain open
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must be gone after the reset cancelled the pending intent");
        assertFalse(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must NOT be closed — the reset cancelled the lock intent");
    }

    // ── Full 2-player lock flow ─────────────────────────────────────────────

    @Test
    void fullLockFlow_passiveConfirms_rowClosesInBothBrowsers() {
        // Active crosses "2" → passive Confirms → active EndTurns → EVALUATE → row closes.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
        driver1 = TestUtils.getDriver(sessionId, playerIds.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // Active crosses "2" (auto-declares intent)
        BoardInteractionHelper.clickCellByValue(driver0, "BLUE", "2");

        // Passive sees notification modal, dismisses it (OK = notification only, not EndTurn)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);  // player1 EndTurns via board pass button

        // Active EndTurns → passive queue empty → EVALUATE → row closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));

        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed in player0's browser");
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "BLUE"),
                "BLUE row must be closed in player1's browser");
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Lock-intent modal must be gone after the row closes");
    }

    // ── Full 2-player lock flow ending in score screen ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void passivePlayerCrossingLockRowSeesRowClosedThenScoreScreen() {
        String p0 = playerIds.get(0);
        String p1 = playerIds.get(1);

        // === Part 1: close RED row via API ===
        api.setCrosses(sessionId, p0, RED_ROW_INDEX, 11);
        api.roll(sessionId, p0);
        api.setDice(sessionId, 1, 1);

        String redRowId = api.getRowId(sessionId, p0, RED_ROW_INDEX);
        api.declareLockIntent(sessionId, p0, redRowId);
        api.pass(sessionId, p1); // player1 EndTurns (passive done)
        api.pass(sessionId, p0); // player0 EndTurns → EVALUATE → RED closes

        Map<String, Object> beforeP1Turn = api.getGameState(sessionId);
        assertEquals("ROLL", turnPhase(beforeP1Turn),
                "Expected ROLL phase for player1's turn after RED row closed");

        // === Part 2: advance player1's active turn ===
        String yellowRowId = api.getRowId(sessionId, p1, 1);
        String p1YellowCellId = getCellId(sessionId, p1, 1, 0);
        api.roll(sessionId, p1);
        api.setDice(sessionId, 1, 1);
        api.crossCell(sessionId, p1, yellowRowId, p1YellowCellId, false);
        api.pass(sessionId, p1); // player1 EndTurns
        api.pass(sessionId, p0); // player0 EndTurns as passive

        // === Part 3: player0's second active turn — set up BLUE lock ===
        api.setCrosses(sessionId, p0, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, p1, BLUE_ROW_INDEX, 5);
        api.roll(sessionId, p0);
        api.setDice(sessionId, 1, 1);

        driver0 = TestUtils.getDriver(sessionId, p0);
        driver1 = TestUtils.getDriver(sessionId, p1);
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // === Part 4: player0 declares lock intent for BLUE ===
        String blueRowId = api.getRowId(sessionId, p0, BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, p0, blueRowId);

        // Player1 sees notification modal and dismisses it (OK = notification only)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "Modal must be gone after player1 dismisses");
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);  // player1 EndTurns via board pass button

        // Player0 EndTurns → passive queue empty → EVALUATE → BLUE closes → 2 rows → game over
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "BLUE"),
                "BLUE must be closed in player1's browser after EVALUATE");

        // Both rows closed → 2 rows → game ends → both players navigate to score screen
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Player0 must be on the score screen. URL: " + driver0.getCurrentUrl());
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver1.getCurrentUrl().contains("/score"),
                "Player1 must be on the score screen. URL: " + driver1.getCurrentUrl());
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
