package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.RetryOnChromeFailure;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LockMechanismIT extends BaseIntegrationTest {

    private static final int BLUE_ROW_INDEX = 3;
    private static final int BLUE_ROW_ALL_CELLS = 11; // 12..2 inclusive
    private static final int RED_ROW_INDEX = 0;

    private WebDriver driver0;
    private WebDriver driver1;
    private WebDriver driver2;
    private String sessionId;
    private List<String> playerIds;

    @BeforeAll
    void openDrivers() {
        driver0 = TestUtils.createDriver();
        driver1 = TestUtils.createDriver();
        driver2 = TestUtils.createDriver();
    }

    @AfterAll
    void closeDrivers() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
        if (driver2 != null) { driver2.quit(); driver2 = null; }
    }

    @BeforeEach
    void createGame() {
        driver0 = TestUtils.ensureAlive(driver0);
        driver1 = TestUtils.ensureAlive(driver1);
        driver2 = TestUtils.ensureAlive(driver2);
        sessionId = api.createGame(2);
        playerIds = api.getOrderedPlayerIds(sessionId);
    }

    // ── Lock eligibility ───────────────────────────────────────────────────────

    @Test
    @ExtendWith(RetryOnChromeFailure.Extension.class)
    @RetryOnChromeFailure
    void lockBecomesAutoCrossedAfterCrossingClosingCell() {
        // Use local session so @RetryOnChromeFailure can re-run safely without stale API state.
        String sid = api.createGame(2);
        List<String> pids = api.getOrderedPlayerIds(sid);

        // Setup: 5 crosses (no "2"), dice 1+1 → white+white sum = 2 → "2" cell is reachable
        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 1);

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));

        assertFalse(isLockButtonCrossed(driver0, "BLUE"),
                "Lock should not be crossed before crossing '2'");

        // Crossing "2" auto-declares lock intent (client sends DECLARE_LOCK_INTENT after cross)
        clickCellByValue(driver0, "BLUE", "2");

        // Lock cross appears immediately on player0's board (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));
        assertTrue(isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear on player0's board as soon as '2' is pending");

        // Player1 sees the notification modal; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player0 EndTurns → passive queue empty → EVALUATE → row closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed after both players complete their turns");
    }

    // ── Lock-intent modal: declaring player ──────────────────────────────────

    @Test
    void activePlayerSeesNoModalAfterCrossingClosingCell() {
        // Player 0 has 5 crosses (no "2"); clicking "2" auto-declares lock intent.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));

        clickCellByValue(driver0, "BLUE", "2");

        // Wait for lock cross to appear (sync point for auto-declare completion)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));

        // The declaring player must NOT see the row-closure modal
        assertFalse(isModalVisible(driver0),
                "The player who crossed the closing cell should NOT see the confirmation modal");
    }

    @Test
    void activePlayerSeesNoModalAfterDeclaringLockIntentViaApi() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        TestUtils.wait(3000);

        assertFalse(isModalVisible(driver0),
                "The declaring player should NOT see the lock-intent modal");
    }

    // ── Lock-intent modal: other player ───────────────────────────────────────

    @Test
    void otherPlayerSeesModalWhenLockIntentDeclaredViaApi() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
                "Player 1 should see the lock-intent modal when player 0 declares intent");
    }

    @Test
    void modalContainsDeclaringPlayerName() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);
        String modalText = getModalText(driver1);
        String declarantName = api.getPlayerName(sessionId, playerIds.get(0));
        assertTrue(modalText.contains(declarantName),
                "Modal should contain the declaring player's name '" + declarantName + "'. Actual text: " + modalText);
    }

    @Test
    void modalContainsCorrectRowColorIndicator() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);
        assertTrue(modalHasColorCell(driver1, "BLUE"),
                "Modal should display a BLUE color indicator (CSS class 'cell-blue')");
    }

    @Test
    void bothPlayersSeeLockIntentModalCorrectly() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);
        TestUtils.wait(1000); // give player 0's board time to poll and NOT show modal

        assertFalse(isModalVisible(driver0),
                "Player 0 (declarer) should NOT see the modal");
        assertTrue(isModalVisible(driver1),
                "Player 1 (passive) SHOULD see the modal");
        String p0Name = api.getPlayerName(sessionId, playerIds.get(0));
        assertTrue(getModalText(driver1).contains(p0Name),
                "Modal should name player 0 as the one who declared intent");
        assertTrue(modalHasColorCell(driver1, "BLUE"),
                "Modal should show the BLUE color indicator");
    }

    // ── Three-player accumulation ─────────────────────────────────────────────

    @Test
    void threePlayersAccumulateLockIntentRequestsInSingleModal() {
        String sid3 = api.createGame(3);
        List<String> pids = api.getOrderedPlayerIds(sid3);

        api.setCrosses(sid3, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid3, pids.get(0));
        api.setDice(sid3, 1, 1);

        // Open player2's browser — they are the passive observer
        TestUtils.navigateTo(driver1, sid3, pids.get(2));

        // Player0 declares lock intent for BLUE
        String blueRowId = api.getRowId(sid3, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid3, pids.get(0), blueRowId);

        // Player2's modal must appear and show exactly 1 request (player0 / BLUE)
        waitUntilModalVisible(driver1, 8);
        assertEquals(1, getModalRequestCount(driver1),
                "Modal should show 1 request after player0 declares BLUE intent");

        // Inject a RED closure request directly (player1's UUID as playerId)
        api.addClosureRequest(sid3, pids.get(1), "RED");

        // Player2 must see BOTH requests accumulate in the SAME modal overlay
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> getModalRequestCount(d) >= 2);

        assertEquals(2, getModalRequestCount(driver1),
                "Modal should now show 2 accumulated requests (player0 BLUE + player1 RED)");
        assertTrue(modalHasColorCell(driver1, "BLUE"),
                "Modal should display the BLUE color indicator for player0's intent");
        assertTrue(modalHasColorCell(driver1, "RED"),
                "Modal should display the RED color indicator for player1's intent");
        String modalText = getModalText(driver1);
        String pid0Name = api.getPlayerName(sid3, pids.get(0));
        String pid1Name = api.getPlayerName(sid3, pids.get(1));
        assertTrue(modalText.contains(pid0Name),
                "Modal text should mention " + pid0Name + ". Actual: " + modalText);
        assertTrue(modalText.contains(pid1Name),
                "Modal text should mention " + pid1Name + ". Actual: " + modalText);
    }

    // ── Longo: self-close yes/no modal ───────────────────────────────────────
    //
    // BLUE descending in LONGO: closing cells are "3" (second-to-last) and "2" (last).
    // Clicking "3" shows a YES/NO modal. YES fires DECLARE_LOCK_INTENT immediately.
    // After clicking "2", the row auto-closes at the active player's EndTurn.

    @Test
    void longoYesOnSelfCloseModal_showsLockCrossPendingThenFiresWhenLastCrossed() {
        String sid = api.createGame(1, java.util.Map.of("base", "LONGO"));
        String pid = api.getOrderedPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 6);
        api.roll(sid, pid);
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1); // white1(1) + BLUE(1) = 2 → "2" reachable

        TestUtils.navigateTo(driver0, sid, pid);

        // Click "3" (second-to-last closing cell) → YES/NO modal appears → Yes
        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);
        clickModalYesButton(driver0);

        // "3" must be crossed; lock cross MUST appear immediately (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "BLUE") >= 7);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));
        assertTrue(isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must appear after clicking YES on '3'");

        // Click "2" (colored die: white1+BLUE = 1+1 = 2) → row closes via active EndTurn
        clickCellByValue(driver0, "BLUE", "2");

        // Single-player: no passives → active EndTurn → EVALUATE → row closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE row must close after clicking '2' and confirming");

        // Turn advances to ROLL
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !d.findElements(By.cssSelector(".btn-roll")).isEmpty());
        assertTrue(driver0.findElement(By.cssSelector(".btn-roll")).isDisplayed(),
                "Roll button must be visible after the lock resolves");
    }

    @Test
    @ExtendWith(RetryOnChromeFailure.Extension.class)
    @RetryOnChromeFailure
    void longoYesOnSelfCloseModal_passivePlayerSeesModalImmediately() {
        String sid = api.createGame(2, java.util.Map.of("base", "LONGO"));
        List<String> pids = api.getOrderedPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);
        api.setColoredDie(sid, "BLUE", 1);

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));

        // Active player clicks "3" → YES → DECLARE_LOCK_INTENT sent immediately
        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);
        clickModalYesButton(driver0);

        // Passive player must see the row-closure modal immediately after YES
        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
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
        List<String> pids = api.getOrderedPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.setCrosses(sid, pids.get(1), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2); // white+white = 3 → "3" reachable

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));

        // player0 (active) crosses "3" → YES/NO modal → YES → DECLARE_LOCK_INTENT fires
        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);
        clickModalYesButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));
        assertTrue(isLockButtonCrossed(driver0, "BLUE"),
                "player0 must see BLUE lock cross after clicking YES");

        // player1 (passive, fresh) dismisses the notification (OK = dismiss, 1-button layout).
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);

        // player1 crosses "3" → YES/NO lock-confirm modal appears → YES
        clickCellByValue(driver1, "BLUE", "3");
        waitUntilModalVisible(driver1, 5);
        clickModalYesButton(driver1);

        // KEY ASSERTIONS: player1 must see the BLUE lock cross on their board AND
        // the notification modal must NOT re-appear and cover it.
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));
        assertTrue(isLockButtonCrossed(driver1, "BLUE"),
                "player1 must see BLUE lock cross after clicking YES on '3'");
        assertFalse(isModalVisible(driver1),
                "Notification modal must NOT cover the board — player1 must see their lock cross");

        // player1 EndTurns via board confirm button (pending cross → hasPendingCross=true)
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // player0 EndTurns → passive queue empty → EVALUATE → BLUE closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
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
        List<String> pids = api.getOrderedPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6); // white+white = 12 → RED "12"

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));

        // Player0 crosses GREEN "12" (not a closing cell) to satisfy hasActed, then declares BLUE.
        String greenRowId0 = api.getRowId(sid, pids.get(0), 2);
        api.crossCell(sid, pids.get(0), greenRowId0, getCellId(sid, pids.get(0), 2, 0), false);
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 sees the BLUE modal; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player0 EndTurns → passive queue empty → EVALUATE → BLUE closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE must close after both players complete their turns");
    }

    @Test
    void passive_crossesClosingCell_redAutoDeclaresAfterBlueFires_gameEnds() {
        // Player0 declares BLUE. Player1 crosses RED "12" → auto-declares RED.
        // Both rows in pendingClosures → EVALUATE → both close → game over.
        String sid = api.createGame(2);
        List<String> pids = api.getOrderedPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6); // white+white = 12 → RED "12"

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));

        // Player0 crosses GREEN "12" (not a closing cell) to satisfy hasActed, then declares BLUE.
        String greenRowId1 = api.getRowId(sid, pids.get(0), 2);
        api.crossCell(sid, pids.get(0), greenRowId1, getCellId(sid, pids.get(0), 2, 0), false);
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);

        // Player1 (fresh passive) dismisses the BLUE notification (OK = dismiss, 1-button layout).
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);

        // Player1 can cross while still in passive queue (modal no longer blocking)
        clickCellByValue(driver1, "RED", "12");

        // Wait for RED lock cross to appear (client auto-declared RED intent)
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver1, "RED"),
                "RED lock cross must appear after player1 crosses '12'");

        // Modal reappears (hasPendingCross=true); player1 confirms (EndTurn)
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);

        // Player0 sees notification about player1's RED declaration; dismiss it first
        waitUntilModalVisible(driver0, 8);
        clickModalConfirmButton(driver0);

        // Player0 EndTurns → EVALUATE → both BLUE and RED close → game over
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

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
        List<String> pids = api.getOrderedPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sid, pids.get(1), RED_ROW_INDEX, 5);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 6, 6); // white+white = 12

        TestUtils.navigateTo(driver0, sid, pids.get(0));
        TestUtils.navigateTo(driver1, sid, pids.get(1));
        TestUtils.navigateTo(driver2, sid, pids.get(2));

        // Player0 crosses GREEN "12" (not a closing cell) to satisfy hasActed, then declares BLUE.
        String greenRowId2 = api.getRowId(sid, pids.get(0), 2);
        api.crossCell(sid, pids.get(0), greenRowId2, getCellId(sid, pids.get(0), 2, 0), false);
        String blueRowId = api.getRowId(sid, pids.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids.get(0), blueRowId);
        waitUntilModalVisible(driver1, 8);
        waitUntilModalVisible(driver2, 8);

        // Player1 (fresh passive) dismisses BLUE notification (OK = dismiss, 1-button layout).
        clickModalConfirmButton(driver1);

        // Player1 crosses RED "12" (modal no longer blocking)
        clickCellByValue(driver1, "RED", "12");
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isLockButtonCrossed(d, "RED"));

        // Modal reappears for player1 (hasPendingCross=true); Confirm = EndTurn
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1); // player1 EndTurns (hasPendingCross=true)

        // Player2 sees notification; OK = dismiss, then passes via board button.
        clickModalConfirmButton(driver2);
        waitUntilPassButtonVisible(driver2, 8);
        clickPassButton(driver2);

        // Player0 sees notification about player1's RED declaration; dismiss it first
        waitUntilModalVisible(driver0, 8);
        clickModalConfirmButton(driver0);

        // Player0 EndTurns → EVALUATE → BLUE and RED close → game over (2 rows)
        waitUntilPassButtonVisible(driver0, 8);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(10))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE must close after all players complete their turns");
        assertTrue(isRowClosed(driver0, "RED"),
                "RED must also close at EVALUATE (both were pending)");
    }

    // ── Single-player lock ─────────────────────────────────────────────────────

    @Test
    void singlePlayerLockIntentClosesRowAfterEndTurn() {
        // In a 1-player game: declareLockIntent records the intent; EndTurn triggers EVALUATE.
        String sid = api.createGame(1);
        String pid = api.getOrderedPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        String redRowId = api.getRowId(sid, pid, RED_ROW_INDEX);
        String redCellId = getCellId(sid, pid, RED_ROW_INDEX, 0); // RED "2" — not a closing cell
        api.crossCell(sid, pid, redRowId, redCellId, false); // white+white=2, satisfies hasActed
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
        String pid = api.getOrderedPlayerIds(sid).get(0);

        api.setCrosses(sid, pid, BLUE_ROW_INDEX, 5);
        api.roll(sid, pid);
        api.setDice(sid, 1, 1);

        TestUtils.navigateTo(driver0, sid, pid);

        // Click "2" → client auto-declares intent → lock cross appears
        clickCellByValue(driver0, "BLUE", "2");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));

        // Click green Confirm → EndTurn → EVALUATE → row closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE row must close after crossing '2' and confirming in a single-player game");
        assertFalse(isModalVisible(driver0),
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

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));

        clickCellByValue(driver0, "BLUE", "2");

        // Lock cross appears (pendingAutoLock)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));

        // Reset cancels the pending intent
        api.resetTurn(sessionId, playerIds.get(0));

        // Lock cross must disappear and row must remain open
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> !isLockButtonCrossed(d, "BLUE"));

        assertFalse(isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must be gone after the reset cancelled the pending intent");
        assertFalse(isRowClosed(driver0, "BLUE"),
                "BLUE row must NOT be closed — the reset cancelled the lock intent");
    }

    // ── Active player sees notification when PASSIVE declares ─────────────────
    //
    // These three tests cover the reverse direction: passive declares, active sees.
    // Setup: player1 has all BLUE cells crossed (qualifies to declare).
    //        player0 rolls with white+white = 7 and crosses RED "7" first.
    //        Then player1 declares BLUE lock intent via the API.

    @Test
    void passive_declaresIntent_activePlayerSeesNotificationModal() {
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 3, 4); // white+white = 7

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player0 (active) crosses RED "7" — creates a pending cross in the undo buffer
        clickCellByValue(driver0, "RED", "7");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        // Player1 (passive) declares BLUE lock intent
        String blueRowId = api.getRowId(sessionId, playerIds.get(1), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(1), blueRowId);

        // Active player must see the notification modal (player1 is NOT the declarant)
        waitUntilModalVisible(driver0, 8);
        assertTrue(isModalVisible(driver0),
                "Active player must see notification modal when passive declares lock intent");
        assertTrue(modalHasColorCell(driver0, "BLUE"),
                "Modal must show BLUE indicator for player1's intent");
        String declarantName = api.getPlayerName(sessionId, playerIds.get(1));
        assertTrue(getModalText(driver0).contains(declarantName),
                "Modal must name the passive declarant (" + declarantName + ")");

        // Player1 (declarant) must NOT see their own declaration as a modal
        assertFalse(isModalVisible(driver1),
                "Declarant (player1) must NOT see their own lock-intent modal");
    }

    @Test
    void passive_declaresIntent_activePlayerDismissesNotificationAndEndsNormally() {
        // Passive declares BEFORE the active has crossed anything → active sees OK-only
        // (notification only, no pending cross). Active dismisses, then crosses normally and EndTurns.
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 3, 4); // white+white = 7

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Passive declares before active has crossed anything → active sees OK-only modal
        String blueRowId = api.getRowId(sessionId, playerIds.get(1), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(1), blueRowId);

        // Active dismisses the notification via OK (no pending cross → notification-only)
        waitUntilModalVisible(driver0, 8);
        clickModalConfirmButton(driver0);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must be dismissed after active player clicks OK");

        // Active can still interact with the board normally
        clickCellByValue(driver0, "RED", "7");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        // Active EndTurns → PASSIVE_MOVE
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Passive EndTurns → EVALUATE → BLUE closes
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE row must close after EVALUATE");
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver1, "BLUE"),
                "BLUE row must be closed in player1's browser too");
    }

    @Test
    void passive_declaresIntent_activePlayerClicksChangeToRevertMove() {
        // When the active player has a pending cross, the modal now shows both
        // "Change" (→ RESET_TURN) and "Confirm" (→ EndTurn/PASS) — same as for passive.
        // Active clicks Change → cross is undone via RESET_TURN.
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 3, 4); // white+white = 7

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player0 crosses RED "7" — pending cross in undo buffer
        clickCellByValue(driver0, "RED", "7");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(1), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(1), blueRowId);

        // Active has a pending cross → modal shows "Change" button
        waitUntilModalVisible(driver0, 8);
        clickModalChangeButton(driver0); // → RESET_TURN
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must be dismissed after active player clicks Change");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") == 0);
        assertEquals(0, getCrossedCellCount(driver0, "RED"),
                "RED cross must be undone after active player clicks Change (RESET_TURN)");
    }

    @Test
    void passive_declaresIntent_activePlayerClicksOkAndContinuesTurn() {
        // Active has a pending cross → modal shows Change + OK buttons.
        // OK dismisses the notification; active can still interact with the board and EndTurn normally.
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 3, 4); // white+white = 7

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        clickCellByValue(driver0, "RED", "7");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(1), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(1), blueRowId);

        // Active clicks OK → modal dismisses, active still in ACTIVE_MOVE with pending RED cross
        waitUntilModalVisible(driver0, 8);
        clickModalConfirmButton(driver0); // OK = dismiss, NOT EndTurn
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must be gone after active clicks OK");

        // RED cross is still pending (active did NOT EndTurn yet)
        assertEquals(1, getCrossedCellCount(driver0, "RED"),
                "RED cross must still be pending after OK (active still in their turn)");

        // Active EndTurns normally via board button → PASSIVE_MOVE
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Passive EndTurns → EVALUATE → BLUE closes
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE must close after EVALUATE");
    }

    @Test
    void active_skipsDeclaration_passive_declares_both_get_lock_cross() {
        // Active player (player0) has the BLUE closing cell permanently and COULD declare,
        // but deliberately EndTurns without declaring.
        // Passive player (player1) also qualifies and declares BLUE in PASSIVE_MOVE.
        // Active player sees the notification (informational — no undo after EndTurn).
        // At EVALUATE both players have the closing cell permanently → both get lockCrossed=true.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 3, 4); // white+white = 7 → RED "7" reachable; BLUE not involved

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player0 crosses RED "7" — eligible for BLUE lock but deliberately ignores it
        clickCellByValue(driver0, "RED", "7");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        // Player0 EndTurns without declaring BLUE → phase → PASSIVE_MOVE
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Player1 (passive) declares BLUE lock intent
        String blueRowId = api.getRowId(sessionId, playerIds.get(1), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(1), blueRowId);

        // Player0 is notified (informational only — they already EndTurned and cannot undo)
        waitUntilModalVisible(driver0, 8);
        assertTrue(isModalVisible(driver0),
                "Active player must see notification after passive declares in PASSIVE_MOVE");
        clickModalConfirmButton(driver0);

        // Player1 EndTurns → EVALUATE → BLUE closes
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE must be closed after EVALUATE");

        // Both players get the lock cross — player0 qualifies as co-locker via permanent
        // closing cell even though they never explicitly declared intent.
        assertTrue(isLockButtonCrossed(driver0, "BLUE"),
                "Player0 must have the BLUE lock cross (co-qualified via permanent closing cell)");
        assertTrue(isLockButtonCrossed(driver1, "BLUE"),
                "Player1 (declarant) must also have the BLUE lock cross");
    }

    // ── Scenario 10: Longo active player can close two rows in one turn ────────

    @Test
    @ExtendWith(RetryOnChromeFailure.Extension.class)
    @RetryOnChromeFailure
    void longo_activeEndsTurn_passiveDeclares_activeGetsModalAndReverts() {
        // The exact scenario that was impossible before:
        // Player0 crosses RED "16" and EndTurns.  Player1 passes (no cross).
        // Player2 crosses YELLOW "16" (the last passive) — instead of evaluate running,
        // player0 is re-queued and shown a notification with a Change button.
        // Player0 clicks Change → reverts to ACTIVE_MOVE → crosses YELLOW "16" too →
        // EndTurns → EVALUATE → both rows close → player0 gets lock crosses for both.
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getOrderedPlayerIds(sid);
        String p0 = pids3.get(0);
        String p1 = pids3.get(1);
        String p2 = pids3.get(2);

        int redRowIdx    = 0;
        int yellowRowIdx = 1;
        int lastCellIdx  = 14; // position 14 = value "16" in Longo

        api.setCrosses(sid, p0, redRowIdx,    14); // p0: 14 crosses in RED, 0 in YELLOW
        api.setCrosses(sid, p0, yellowRowIdx, 14); // p0: 14 crosses in YELLOW
        api.setCrosses(sid, p2, yellowRowIdx, 14); // p2: 14 crosses in YELLOW → can close it

        api.roll(sid, p0);
        api.setDice(sid, 8, 8);              // white+white = 16
        api.setColoredDie(sid, "YELLOW", 8); // white+yellow = 16

        TestUtils.navigateTo(driver0, sid, p0);
        TestUtils.navigateTo(driver1, sid, p1);

        // Player0 crosses RED "16" (white+white=16) → auto-declares RED, then EndTurns
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0); // EndTurn → PASSIVE_MOVE

        // Player1 sees RED notification; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player2 (via API): crosses YELLOW "16", declares intent, EndTurns.
        // This is the last passive — instead of EVALUATE running, the server detects that
        // player0 could also lock YELLOW (14 crosses, no closing cell yet) and re-queues player0.
        String p2YellowRowId = api.getRowId(sid, p2, yellowRowIdx);
        String p2Yellow16Id  = getCellId(sid, p2, yellowRowIdx, lastCellIdx);
        api.crossCell(sid, p2, p2YellowRowId, p2Yellow16Id, false);
        api.declareLockIntent(sid, p2, p2YellowRowId);
        api.pass(sid, p2); // last passive EndTurns → player0 re-queued

        // === KEY ASSERTION: player0 is re-queued and sees the notification ===
        waitUntilModalVisible(driver0, 8);
        assertTrue(modalHasColorCell(driver0, "YELLOW"),
                "Player0 must see YELLOW notification after being re-queued");

        // Player0 clicks Change → RESET_TURN → back to ACTIVE_MOVE
        clickModalChangeButton(driver0);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must dismiss after player0 clicks Change");

        // Player0 is back in ACTIVE_MOVE; RED cross was cleared (snapshot restored)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isLockButtonCrossed(d, "RED"));
        assertFalse(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must be gone after Change clears the pending cross");

        // Player0 re-crosses RED "16" (activeTurnState was reset, so white+white is usable again)
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must reappear after player0 re-crosses RED '16'");

        // Player0 crosses YELLOW "16" (yellow colored die: white+yellow=16)
        clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after player0 crosses YELLOW '16'");

        // Player0 EndTurns → passive queue is empty → EVALUATE
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // EVALUATE: both RED and YELLOW close; player0 has lock crosses for both
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED")
                         && isRowClosed(d, "YELLOW"));

        assertTrue(isRowClosed(driver0, "RED"),   "RED must be closed");
        assertTrue(isRowClosed(driver0, "YELLOW"), "YELLOW must be closed");
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "Player0 must have RED lock cross");
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "Player0 must have YELLOW lock cross (reverted and crossed it)");
    }

    @Test
    void longo_activeEndsTurn_passiveDeclares_activeClicksOkToEvaluate() {
        // Same setup as longo_activeEndsTurn_passiveDeclares_activeGetsModalAndReverts,
        // but player0 clicks OK instead of Change — this proceeds to EVALUATE without reverting.
        // Player0 already qualifies for YELLOW via "15" (has 14 crosses including position 13="15"),
        // so they get lockCrossed for YELLOW automatically at EVALUATE even without reverting.
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getOrderedPlayerIds(sid);
        String p0 = pids3.get(0);
        String p1 = pids3.get(1);
        String p2 = pids3.get(2);

        api.setCrosses(sid, p0, 0, 14);  // RED: 14 crosses
        api.setCrosses(sid, p0, 1, 14);  // YELLOW: 14 crosses (includes "15" → already qualifies at evaluate)
        api.setCrosses(sid, p2, 1, 14);  // p2: 14 YELLOW crosses → can close YELLOW

        api.roll(sid, p0);
        api.setDice(sid, 8, 8);

        TestUtils.navigateTo(driver0, sid, p0);
        TestUtils.navigateTo(driver1, sid, p1);

        // Player0 crosses RED "16" and EndTurns
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Player1 sees RED notification; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player2 (via API): crosses YELLOW "16", declares, EndTurns → player0 re-queued
        String p2YellowRowId = api.getRowId(sid, p2, 1);
        String p2Yellow16Id  = getCellId(sid, p2, 1, 14);
        api.crossCell(sid, p2, p2YellowRowId, p2Yellow16Id, false);
        api.declareLockIntent(sid, p2, p2YellowRowId);
        api.pass(sid, p2);

        // Player0 sees the modal; clicks OK → proceeds to EVALUATE (no revert)
        waitUntilModalVisible(driver0, 8);
        clickModalConfirmButton(driver0); // OK → PASS → evaluate

        // EVALUATE must run: both RED and YELLOW close (2 rows → game over).
        // Player0 already qualifies for YELLOW via "15" (permanent cross at position 13).
        // After game over the board navigates to /score — wait for that.
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Game must end (2 rows closed) and navigate to score screen");
    }

    @Test
    void longo_activePlayerRevertsEndTurn_thenCrossesBothRows() {
        // Scenario 10b (Longo, 3-player):
        // Player0 crosses RED "16", EndTurns, then sees player1's YELLOW declaration from
        // PASSIVE_MOVE. Player0 clicks Change → RESET_TURN reverts to ACTIVE_MOVE.
        // Player0 then also crosses YELLOW "16" (color die) and EndTurns properly.
        // At EVALUATE both RED and YELLOW close and player0 has lock crosses for both.
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getOrderedPlayerIds(sid);
        String p0 = pids3.get(0);
        String p1 = pids3.get(1);
        String p2 = pids3.get(2);

        int redRowIdx    = 0;
        int yellowRowIdx = 1;
        int lastCellIdx  = 14;

        api.setCrosses(sid, p0, redRowIdx,    14);
        api.setCrosses(sid, p0, yellowRowIdx, 14);
        api.setCrosses(sid, p1, yellowRowIdx, 14);

        api.roll(sid, p0);
        api.setDice(sid, 8, 8);
        api.setColoredDie(sid, "YELLOW", 8);

        TestUtils.navigateTo(driver0, sid, p0);
        TestUtils.navigateTo(driver2, sid, p2);

        // Player0 crosses RED "16" (white+white=16) → auto-declares RED, EndTurns
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0); // EndTurn → PASSIVE_MOVE

        // Player1 (via API) crosses YELLOW "16" and declares YELLOW intent from PASSIVE_MOVE
        String p1YellowRowId = api.getRowId(sid, p1, yellowRowIdx);
        String p1Yellow16Id  = getCellId(sid, p1, yellowRowIdx, lastCellIdx);
        api.crossCell(sid, p1, p1YellowRowId, p1Yellow16Id, false);
        api.declareLockIntent(sid, p1, p1YellowRowId);

        // Player0 sees notification: "player1 wants to close YELLOW" — with Change button
        waitUntilModalVisible(driver0, 8);
        assertTrue(modalHasColorCell(driver0, "YELLOW"),
                "Notification must show YELLOW indicator");

        // Player0 clicks Change → RESET_TURN reverts to ACTIVE_MOVE
        clickModalChangeButton(driver0);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must be dismissed after clicking Change");

        // Player0 is back in ACTIVE_MOVE; RED cross was cleared (snapshot restored)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isLockButtonCrossed(d, "RED"));
        assertFalse(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must be gone after Change clears the pending cross");

        // Player0 re-crosses RED "16" (activeTurnState was reset, so white+white is usable again)
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must reappear after player0 re-crosses RED '16'");

        // Player0 crosses YELLOW "16" (color die: white+yellow=16)
        clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after player0 crosses YELLOW '16'");

        // Player0 EndTurns (now for real, with both crosses)
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Player1 EndTurns via API
        api.pass(sid, p1);

        // Player2 sees notification; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver2, 8);
        clickModalConfirmButton(driver2);
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2);

        // EVALUATE: both RED and YELLOW must close, player0 gets lock crosses for both
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED")
                         && isRowClosed(d, "YELLOW"));

        assertTrue(isRowClosed(driver0, "RED"),   "RED must be closed");
        assertTrue(isRowClosed(driver0, "YELLOW"), "YELLOW must be closed");
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "Player0 must have RED lock cross");
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "Player0 must have YELLOW lock cross");
    }

    @Test
    void longo_activePlayerDismissesNotification_thenCrossesBothRows() {
        // Scenario 10 (Longo, 3-player):
        // Player0 has 14 crosses in RED and YELLOW (values 2-15). Dice: white=8+8=16, yellowDie=8.
        // Player0 crosses RED "16" with white+white → auto-declares RED intent.
        // Player1 (via API) crosses YELLOW "16" with white+white → declares YELLOW intent.
        // Player0 sees the notification, clicks OK (dismisses without ending turn).
        // Player0 can then also cross YELLOW "16" with the yellow color die.
        // At EVALUATE both RED and YELLOW close; player0 gets lock crosses for both.
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getOrderedPlayerIds(sid);
        String p0 = pids3.get(0);
        String p1 = pids3.get(1);
        String p2 = pids3.get(2);

        int redRowIdx    = 0;
        int yellowRowIdx = 1;
        int lastCellIdx  = 14; // Longo row has 15 cells (values 2-16); position 14 = value "16"

        // Player0: 14 crosses in RED and YELLOW — only "16" (the closing cell) remains
        api.setCrosses(sid, p0, redRowIdx,    14);
        api.setCrosses(sid, p0, yellowRowIdx, 14);
        // Player1: 14 crosses in YELLOW — qualifies to close YELLOW this turn
        api.setCrosses(sid, p1, yellowRowIdx, 14);

        api.roll(sid, p0);
        api.setDice(sid, 8, 8);              // white+white = 16
        api.setColoredDie(sid, "YELLOW", 8); // white1+yellow = 8+8 = 16

        TestUtils.navigateTo(driver0, sid, p0);
        TestUtils.navigateTo(driver2, sid, p2);

        // Player0 crosses RED "16" (white+white=16) → auto-declares RED intent
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must appear after player0 crosses RED '16'");

        // Player1 (via API) crosses YELLOW "16" and declares YELLOW intent
        String p1YellowRowId = api.getRowId(sid, p1, yellowRowIdx);
        String p1Yellow16Id  = getCellId(sid, p1, yellowRowIdx, lastCellIdx);
        api.crossCell(sid, p1, p1YellowRowId, p1Yellow16Id, false);
        api.declareLockIntent(sid, p1, p1YellowRowId);

        // Player0 sees the notification: "player1 wants to close the YELLOW row"
        waitUntilModalVisible(driver0, 8);
        assertTrue(modalHasColorCell(driver0, "YELLOW"),
                "Notification must show YELLOW indicator");

        // Player0 clicks OK — dismisses the notification WITHOUT ending their turn
        clickModalConfirmButton(driver0);
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver0),
                "Modal must be dismissed after player0 clicks OK");

        // Player0 crosses YELLOW "16" using the yellow color die (white+yellowDie=16)
        clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after player0 also crosses YELLOW '16'");

        // Player0 EndTurns → PASSIVE_MOVE
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // Player1 EndTurns via API (they have a pending YELLOW cross from the API call above)
        api.pass(sid, p1);

        // Player2 sees notifications; OK = dismiss, then passes via board button → EVALUATE.
        waitUntilModalVisible(driver2, 8);
        clickModalConfirmButton(driver2);
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2);

        // EVALUATE: both RED and YELLOW must close
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED")
                         && isRowClosed(d, "YELLOW"));

        assertTrue(isRowClosed(driver0, "RED"),
                "RED must be closed after EVALUATE");
        assertTrue(isRowClosed(driver0, "YELLOW"),
                "YELLOW must be closed after EVALUATE");
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "Player0 must have RED lock cross");
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "Player0 must have YELLOW lock cross (qualified as co-locker)");
    }

    @Test
    void longo_passiveRevertsEndTurn_thenCrossesClosingCell() {
        // Passive player (player1) EndTurns without crossing YELLOW "16".
        // Player2 (also passive) then crosses YELLOW "16" → declares YELLOW intent.
        // Player1 sees the notification with Change button, clicks Change → RESET_TURN
        // reverts their EndTurn. Player1 is put back in the passive queue so they can
        // also cross YELLOW "16" and get the lock cross bonus.
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getOrderedPlayerIds(sid);
        String p0 = pids3.get(0);
        String p1 = pids3.get(1);
        String p2 = pids3.get(2);

        int yellowRowIdx = 1;
        int lastCellIdx  = 14;

        // Player0: active, some crosses (can use white+white=16 for something else)
        api.setCrosses(sid, p0, 0, 5); // RED: 5 crosses so player0 can EndTurn
        // Player1 and player2: each has 14 crosses in YELLOW, one cell away from closing
        api.setCrosses(sid, p1, yellowRowIdx, 14);
        api.setCrosses(sid, p2, yellowRowIdx, 14);

        api.roll(sid, p0);
        api.setDice(sid, 8, 8); // white+white = 16

        TestUtils.navigateTo(driver1, sid, p1);
        TestUtils.navigateTo(driver2, sid, p2);

        // Player0 EndTurns via API (used white+white=16 on a RED cell conceptually)
        String p0RedRowId = api.getRowId(sid, p0, 0);
        String p0RedCellId = getCellId(sid, p0, 0, 5); // position 5 (next uncrossed)
        api.crossCell(sid, p0, p0RedRowId, p0RedCellId, false);
        api.pass(sid, p0); // player0 EndTurns → PASSIVE_MOVE

        // Player1 (browser) EndTurns without crossing anything (just passes)
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 passes with no cross

        // Player2 (browser) crosses YELLOW "16" → auto-declares YELLOW intent
        clickCellByValue(driver2, "YELLOW", "16");
        new WebDriverWait(driver2, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));

        // Player1 sees notification: "player2 wants to close YELLOW" + Change button
        waitUntilModalVisible(driver1, 8);
        assertTrue(modalHasColorCell(driver1, "YELLOW"),
                "Notification must show YELLOW indicator for player1");

        // Player1 clicks Change → RESET_TURN reverts their EndTurn
        clickModalChangeButton(driver1);
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver1),
                "Modal must dismiss after player1 clicks Change");

        // Player1 is back in the passive queue — can now cross YELLOW "16"
        clickCellByValue(driver1, "YELLOW", "16");
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver1, "YELLOW"),
                "Player1 must have YELLOW lock cross after crossing '16'");

        // Player1 EndTurns (with the YELLOW cross committed)
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player2 EndTurns → queue empty → EVALUATE
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2);

        // EVALUATE: YELLOW must close
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "YELLOW"));

        assertTrue(isRowClosed(driver1, "YELLOW"),
                "YELLOW must be closed after EVALUATE");
        assertTrue(isLockButtonCrossed(driver1, "YELLOW"),
                "Player1 must have YELLOW lock cross (reverted, then crossed)");
        assertTrue(isLockButtonCrossed(driver2, "YELLOW"),
                "Player2 (declarant) must also have YELLOW lock cross");
    }

    // ── Full 2-player lock flow ─────────────────────────────────────────────

    @Test
    void fullLockFlow_passiveConfirms_rowClosesInBothBrowsers() {
        // Active crosses "2" → passive Confirms → active EndTurns → EVALUATE → row closes.
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 5);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Active crosses "2" (auto-declares intent)
        clickCellByValue(driver0, "BLUE", "2");

        // Passive sees notification; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Active EndTurns → passive queue empty → EVALUATE → row closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));

        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE row must be closed in player0's browser");
        assertTrue(isRowClosed(driver1, "BLUE"),
                "BLUE row must be closed in player1's browser");
        assertFalse(isModalVisible(driver1),
                "Lock-intent modal must be gone after the row closes");
    }

    /**
     * Regression test for the bug where all passives had already passed BEFORE
     * the active player declared lock intent, leaving the passive queue empty.
     * The active player's subsequent EndTurn triggered EVALUATE immediately,
     * cutting off passive players who had the notification modal open.
     *
     * Expected (fixed) behaviour: the declaration atomically re-queues every passive
     * who had already left. The active player's EndTurn then enters PASSIVE_MOVE;
     * passive must EndTurn again before EVALUATE fires.
     */
    @Test
    void passive_passesFirst_thenActiveDeclaresLock_passiveIsRequeued_rowClosesAfterBothAct() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver0, sessionId, playerIds.get(0));
        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player1 (passive) passes BEFORE any declaration has been made.
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Active crosses RED "2" (not a closing cell) to satisfy hasActed, then declares BLUE.
        String redRowId0 = api.getRowId(sessionId, playerIds.get(0), RED_ROW_INDEX);
        api.crossCell(sessionId, playerIds.get(0), redRowId0, getCellId(sessionId, playerIds.get(0), RED_ROW_INDEX, 0), false);
        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        // Player1 must be re-queued and see the notification modal.
        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
                "Player1 must see the notification after being re-queued by the declaration");

        // Player1 sees notification (re-queued); OK = dismiss, then passes via board button.
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Active EndTurns → passive queue now empty → EVALUATE → BLUE closes.
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver0, "BLUE"),
                "BLUE must close after both players have EndTurned");
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver1, "BLUE"),
                "BLUE must also be closed in player1's browser");
    }

    /**
     * A passive player who is still in the queue (fresh — never acted this turn) must see
     * a single-button modal (OK only, no Change). The Change button only appears when the
     * player has already acted or been re-queued mid-turn.
     */
    @Test
    void freshPassive_seesOneButtonModal_noChangeButton() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player0 declares while player1 is still fresh in the passive queue (never acted).
        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
                "Player1 must see the notification modal");
        assertFalse(isModalChangeButtonVisible(driver1),
                "Fresh passive must NOT see the Change button — 1-button layout only");
    }

    /**
     * A passive player who already passed before the active declared (and was therefore
     * re-queued) must see the two-button layout (Change + OK). Change lets them undo any
     * prior move and reconsider; OK simply dismisses so they can click PASS on the board.
     */
    @Test
    void reQueuedPassive_seesTwoButtonModal_changeButtonVisible() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        // Player1 passes BEFORE any declaration (they are now outside the passive queue).
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Active declares → player1 is re-queued mid-turn.
        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        // Player1 must see the two-button layout: Change (undo/reconsider) and OK (dismiss).
        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
                "Player1 must see the notification after being re-queued");
        assertTrue(isModalChangeButtonVisible(driver1),
                "Re-queued passive must see the Change button — 2-button layout");
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

        String p0YellowRowId = api.getRowId(sessionId, p0, 1); // YELLOW row
        String p0YellowCell2Id = getCellId(sessionId, p0, 1, 0); // YELLOW "2" — not a closing cell
        api.crossCell(sessionId, p0, p0YellowRowId, p0YellowCell2Id, false); // white+white=2, satisfies hasActed
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
        api.setDice(sessionId, 2, 3); // white+white=5 → YELLOW "5" reachable

        TestUtils.navigateTo(driver0, sessionId, p0);
        TestUtils.navigateTo(driver1, sessionId, p1);

        // === Part 4: player0 crosses YELLOW "5" (not a closing cell) then declares BLUE ===
        api.crossCell(sessionId, p0, p0YellowRowId, getCellId(sessionId, p0, 1, 3), false); // YELLOW pos 3 = "5"
        String blueRowId = api.getRowId(sessionId, p0, BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, p0, blueRowId);

        // Player1 sees notification modal; OK = dismiss, then passes via board button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1);
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver1),
                "Modal must be gone after player1 clicks OK");
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // Player0 EndTurns → passive queue empty → EVALUATE → BLUE closes → 2 rows → game over
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "BLUE"));
        assertTrue(isRowClosed(driver1, "BLUE"),
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
