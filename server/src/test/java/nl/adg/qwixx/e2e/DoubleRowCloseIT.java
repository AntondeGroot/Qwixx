package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * End-to-end tests for the "double row close" feature in standard Qwixx.
 * <p>
 * Setup:
 *   - 2 players, standard variant
 *   - player0 has 5 crosses in RED  (positions 0–4, values 2–6)
 *   - player0 has 5 crosses in YELLOW (positions 0–4, values 2–6)
 *   - white1 = 6, white2 = 6  →  white+white = 12  →  hits RED "12"   (lock-eligible)
 *   - yellow die = 6           →  white+yellow = 12  →  hits YELLOW "12" (lock-eligible)
 * <p>
 * In standard Qwixx, RED and YELLOW are ascending rows (2→12); the last cell ("12")
 * is the only closing-eligible cell.  Lock minimum = 6 crosses (5 pre-set + "12" = 6).
 * <p>
 * Expected flow:
 *   1. player0 clicks RED "12"  → RED lock auto-declared (no modal for player0).
 *   2. YELLOW "12" is still shown as clickable (colored die option: white+yellow=12).
 *   3. player0 clicks YELLOW "12" → YELLOW lock also auto-declared.
 *   4. player0 EndTurns → phase = PASSIVE_MOVE.
 *   5. player1 dismisses the notice and EndTurns via the board → EVALUATE → both rows close → game ends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DoubleRowCloseIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX    = 0; // ascending 2→12
    private static final int YELLOW_ROW_INDEX = 1; // ascending 2→12

    private WebDriver driver0;
    private WebDriver driver1;
    private String    sessionId;
    private List<String> pids;

    @BeforeAll
    void openDrivers() {
        driver0 = TestUtils.createDriver();
        driver1 = TestUtils.createDriver();
    }

    @AfterAll
    void closeDrivers() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
    }

    @BeforeEach
    void setup() {
        driver0 = TestUtils.ensureAlive(driver0);
        driver1 = TestUtils.ensureAlive(driver1);
        sessionId = api.createGame(2);
        pids      = api.getPlayerIds(sessionId);

        // player0: 5 crosses in RED and YELLOW — "12" is the next and final cell
        api.setCrosses(sessionId, pids.getFirst(), RED_ROW_INDEX,    5);
        api.setCrosses(sessionId, pids.getFirst(), YELLOW_ROW_INDEX, 5);

        api.roll(sessionId, pids.getFirst());
        api.setDice(sessionId, 6, 6);              // white+white = 12  → RED "12"
        api.setColoredDie(sessionId, "YELLOW", 6); // white+yellow = 12 → YELLOW "12"
    }

    // ── Test 1: clicking RED "12" auto-crosses the RED lock ──────────────────────

    @Test
    void clickingRed12AutoCrossesRedLock() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());
        TestUtils.navigateTo(driver1, sessionId, pids.get(1));

        assertFalse(isLockButtonCrossed(driver0, "RED"),
                "RED lock must not be crossed before clicking RED-12");

        clickCellByValue(driver0, "RED", "12");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must appear after clicking RED-12");
        assertFalse(isModalVisible(driver0),
                "Declaring player must NOT see the row-closure modal");

        // Player1 sees the notification modal and dismisses it (OK = notification only, not EndTurn).
        waitUntilModalVisible(driver1, 8);
        assertTrue(isModalVisible(driver1),
                "Player1 must see the row-closure modal");
        clickModalConfirmButton(driver1); // dismiss notification
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 EndTurns via board pass button

        // player0 EndTurns → passive queue empty → EVALUATE → RED closes.
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED"));
        assertTrue(isRowClosed(driver0, "RED"),
                "RED must be closed after EVALUATE");
    }

    // ── Test 2: YELLOW "12" is clickable while RED lock is pending ───────────────

    @Test
    void yellow12IsClickableAndCrossesSecondLockAfterRed12IsCrossed() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());

        clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        // YELLOW "12" must have the .clickable CSS class (white+yellow die = 6+6 = 12)
        assertTrue(isCellClickable(driver0, "YELLOW", "12"),
                "YELLOW-12 must be shown as clickable (white+yellow=12) "
                + "even while RED lock intent is pending");

        // Clicking YELLOW "12" must also cross the YELLOW lock
        clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-12");
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-12 is clicked");
    }

    // ── Test 3: clicking YELLOW "12" also crosses the YELLOW lock ────────────────

    @Test
    void clickingYellow12AfterRed12AlsoCrossesYellowLock() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());

        // Click RED "12" — RED lock cross appears (lock intent declared)
        clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        assertTrue(isCellClickable(driver0, "YELLOW", "12"),
                "YELLOW-12 must be clickable after RED lock is pending");

        // Click YELLOW "12" — YELLOW lock cross must also appear
        clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED")
                         && isLockButtonCrossed(d, "YELLOW"));

        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-12 is clicked");
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-12");
    }

    // ── Test 4: both rows close and game ends ─────────────────────────────────────

    /**
     * Full double-close flow in one turn:
     *   player0 clicks RED "12" → RED lock auto-declared.
     *   player0 clicks YELLOW "12" → YELLOW lock also auto-declared.
     *   player0 EndTurns → phase = PASSIVE_MOVE.
     *   player1 dismisses the notice and EndTurns via the board (PASSIVE_MOVE → EVALUATE) →
     *   both rows close → 2 rows locked → game ends.
     */
    @Test
    void clickingBothClosingCellsAndConfirmingEndsGame() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());
        TestUtils.navigateTo(driver1, sessionId, pids.get(1));

        // player0 crosses RED "12" (white+white) then YELLOW "12" (white+yellow).
        // Both lock intents are auto-declared by the client.
        clickCellByValue(driver0, "RED", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        clickCellByValue(driver0, "YELLOW", "12");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));

        // player0 EndTurns → phase transitions to PASSIVE_MOVE.
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        // player1 sees the notification modal and dismisses it (OK = notification only, not EndTurn),
        // then EndTurns via the board pass button → passive queue empty → EVALUATE.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1); // dismiss notification
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 EndTurns → EVALUATE

        // Both rows close and the game ends.
        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED")
                         && isRowClosed(d, "YELLOW"));
        assertTrue(isRowClosed(driver0, "RED"),
                "RED must be closed after EVALUATE");
        assertTrue(isRowClosed(driver0, "YELLOW"),
                "YELLOW must be closed after EVALUATE");

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
