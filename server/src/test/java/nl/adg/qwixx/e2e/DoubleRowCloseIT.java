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
 * End-to-end tests for the "double row close" feature in standard Qwixx.
 *
 * Setup:
 *   - 2 players, standard variant
 *   - player0 has 5 crosses in RED  (positions 0–4, values 2–6)
 *   - player0 has 5 crosses in YELLOW (positions 0–4, values 2–6)
 *   - white1 = 6, white2 = 6  →  white+white = 12  →  hits RED "12"   (lock-eligible)
 *   - yellow die = 6           →  white+yellow = 12  →  hits YELLOW "12" (lock-eligible)
 *
 * In standard Qwixx, RED and YELLOW are ascending rows (2→12); the last cell ("12")
 * is the only closing-eligible cell.  Lock minimum = 6 crosses (5 pre-set + "12" = 6).
 *
 * Expected flow:
 *   1. player0 clicks RED "12"  → RED lock auto-crossed (no modal for player0).
 *   2. YELLOW "12" is still shown as clickable (colored die option: white+yellow=12).
 *   3. player0 clicks YELLOW "12" → YELLOW lock also auto-crossed.
 *   4. player1 confirms via the modal → RED and YELLOW both close → 2 rows locked → game ends.
 */
public class DoubleRowCloseIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX    = 0; // ascending 2→12
    private static final int YELLOW_ROW_INDEX = 1; // ascending 2→12

    private WebDriver driver0;
    private WebDriver driver1;
    private String    sessionId;
    private List<String> pids;

    @BeforeEach
    void setup() {
        sessionId = api.createGame(2);
        pids      = api.getPlayerIds(sessionId);

        // player0: 5 crosses in RED and YELLOW — "12" is the next and final cell
        api.setCrosses(sessionId, pids.get(0), RED_ROW_INDEX,    5);
        api.setCrosses(sessionId, pids.get(0), YELLOW_ROW_INDEX, 5);

        api.roll(sessionId, pids.get(0));
        api.setDice(sessionId, 6, 6);              // white+white = 12  → RED "12"
        api.setColoredDie(sessionId, "YELLOW", 6); // white+yellow = 12 → YELLOW "12"
    }

    @AfterEach
    void tearDown() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
    }

    // ── Test 1: clicking RED "12" auto-crosses the RED lock ──────────────────────

    @Test
    void clickingRed12AutoCrossesRedLock() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock must not be crossed before clicking RED-12");

        BoardInteractionHelper.clickCellByValue(driver0, "RED", "12");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must appear after clicking RED-12");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "Declaring player must NOT see the row-closure modal");

        // Passive player (player1) sees the modal
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Player1 must see the row-closure modal");

        // player1 confirms → RED row closes
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED must be closed after player1 confirms");
    }

    // ── Test 2: YELLOW "12" is clickable while RED lock is pending ───────────────

    @Test
    void yellow12IsClickableAndCrossesSecondLockAfterRed12IsCrossed() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        BoardInteractionHelper.clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // YELLOW "12" must have the .clickable CSS class (white+yellow die = 6+6 = 12)
        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "12"),
                "YELLOW-12 must be shown as clickable (white+yellow=12) "
                + "even while RED lock intent is pending");

        // Clicking YELLOW "12" must also cross the YELLOW lock
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-12");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-12 is clicked");
    }

    // ── Test 3: clicking YELLOW "12" also crosses the YELLOW lock ────────────────

    @Test
    void clickingYellow12AfterRed12AlsoCrossesYellowLock() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click RED "12" — RED lock cross appears (lock intent declared)
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "12"),
                "YELLOW-12 must be clickable after RED lock is pending");

        // Click YELLOW "12" — YELLOW lock cross must also appear
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED")
                         && BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-12 is clicked");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-12");
    }

    // ── Test 4: both rows close and game ends ─────────────────────────────────────

    /**
     * Full double-close flow in one turn:
     *   player0 clicks RED "12" → RED lock pending.
     *   player0 clicks YELLOW "12" → YELLOW lock also queued.
     *   player1 confirms the combined modal → both rows close → 2 rows locked → game ends.
     */
    @Test
    void clickingBothClosingCellsAndConfirmingEndsGame() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);

        // player0 closes RED then YELLOW in the same turn
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        // player1 sees the lock-intent modal and confirms
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);

        // Both rows must close
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED")
                         && BoardInteractionHelper.isRowClosed(d, "YELLOW"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED must be closed after player1 confirms");
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "YELLOW"),
                "YELLOW must be closed after player1 confirms");

        // player1 gets a final PASSIVE_MOVE turn before the game ends
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);

        // 2 rows locked → game over → both players navigate to score screen
        new WebDriverWait(driver0, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Player0 must be on the score screen after game ends");
        assertTrue(driver1.getCurrentUrl().contains("/score"),
                "Player1 must be on the score screen after game ends");
    }
}
