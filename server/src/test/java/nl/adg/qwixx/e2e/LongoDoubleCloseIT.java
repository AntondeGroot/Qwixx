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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Longo variant — "double row close" scenario.
 *
 * Setup mirrors preview scenario 10:
 *   - 3 players, Longo variant
 *   - player0 has 14 crosses in RED  (positions 0–13, values 2–15) — "16" is the last cell
 *   - player0 has 14 crosses in YELLOW (positions 0–13, values 2–15) — "16" is the last cell
 *   - white1 = 8, white2 = 8  →  white+white = 16  →  hits RED "16"
 *   - yellow die = 8           →  white+yellow = 16  →  hits YELLOW "16"
 *
 * In Longo ascending rows the last two cells ("15" and "16") are closing-eligible.
 * Clicking "16" (the last required cell) auto-declares lock intent without a modal;
 * the lock ✕ appears immediately once the cell enters pendingCellIds.
 *
 * The full double-close scenario:
 *   1. player0 clicks RED "16"  → RED lock is auto-crossed.
 *   2. YELLOW "16" must still show as clickable (client sees white+yellow=16 as valid).
 *   3. player0 clicks YELLOW "16" → YELLOW lock is also crossed.
 *   4. player1 and player2 confirm → both rows close → 2 locked rows → game ends.
 *
 * Tests 1 and 2 cover what is verifiable in the UI right now.
 * Tests 3 and 4 close both rows — the first via the UI flow plus API passes,
 * the second (re-entering player0's turn) via the API to keep the test deterministic.
 */
public class LongoDoubleCloseIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX    = 0; // ascending 2→16
    private static final int YELLOW_ROW_INDEX = 1; // ascending 2→16
    private static final int BLUE_ROW_INDEX   = 3; // descending 16→2

    private WebDriver driver0;
    private WebDriver driver1;
    private WebDriver driver2;
    private String    sessionId;
    private List<String> pids;

    @BeforeEach
    void setup() {
        sessionId = api.createGame(3, Map.of("base", "LONGO"));
        pids      = api.getPlayerIds(sessionId);

        // player0: one cross away from closing RED and YELLOW
        api.setCrosses(sessionId, pids.get(0), RED_ROW_INDEX,    14);
        api.setCrosses(sessionId, pids.get(0), YELLOW_ROW_INDEX, 14);
        // player1, player2: some progress elsewhere to make the board look realistic
        api.setCrosses(sessionId, pids.get(1), 2, 5);
        api.setCrosses(sessionId, pids.get(2), BLUE_ROW_INDEX, 4);

        api.roll(sessionId, pids.get(0));
        api.setDice(sessionId, 8, 8);          // white+white = 16  → RED "16"
        api.setColoredDie(sessionId, "YELLOW", 8); // white+yellow = 16 → YELLOW "16"
    }

    @AfterEach
    void tearDown() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
        if (driver2 != null) { driver2.quit(); driver2 = null; }
    }

    // ── Test 1: clicking RED "16" auto-crosses the RED lock ──────────────────────

    /**
     * Clicking the last closing-eligible cell of RED auto-declares lock intent.
     * The lock ✕ must appear on player0's board immediately (pending state), and
     * the passive players must see the row-closure modal.  After both confirm the
     * RED row is closed.
     */
    @Test
    void clickingRed16AutoCrossesRedLockAndClosesRow() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        driver2 = TestUtils.getDriver(sessionId, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);
        TestUtils.waitUntilBoardLoaded(driver2);

        assertFalse(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock must not be crossed before clicking RED-16");
        assertFalse(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED row must not be closed before clicking RED-16");

        // Clicking the last closing-eligible cell triggers auto-lock (no modal for player0)
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross (✕) must appear on player0's board after clicking RED-16");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "The declaring player must NOT see the row-closure modal");

        // Passive players see the closure modal
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1),
                "Player1 must see the row-closure modal");
        assertTrue(BoardInteractionHelper.isModalVisible(driver2),
                "Player2 must see the row-closure modal");

        // Both confirm → RED closes
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        BoardInteractionHelper.clickModalConfirmButton(driver2);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED row must be closed after both passive players confirm");
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "RED"),
                "RED row must be closed in player1's browser too");
    }

    // ── Test 2: YELLOW "16" is clickable while RED lock is pending ───────────────

    /**
     * After clicking RED "16" the RED lock cross appears (lock intent is pending).
     * At this point YELLOW "16" must still carry the {@code clickable} CSS class,
     * because the client computes it as reachable via white+yellow die (8+8 = 16).
     * <p>
     * This verifies the UI correctly advertises the second crossing opportunity that
     * the player can take using their colored-die action.
     */
    @Test
    void yellow16IsClickableAndCrossesSecondLockAfterRed16IsCrossed() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Cross RED "16" — lock cross appears (pending in client)
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // YELLOW "16" must be shown as clickable (white+yellow die = 8+8 = 16)
        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be shown as clickable (reachable via white+yellow=16) "
                + "even while RED lock intent is pending on the client");

        // Clicking YELLOW "16" must also cross the YELLOW lock
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
    }

    // ── Test 3: clicking YELLOW "16" also crosses the YELLOW lock ────────────────

    /**
     * Full double-lock UI flow for player0's board:
     *   RED "16" clicked  → RED lock ✕ appears.
     *   YELLOW "16" clicked → YELLOW lock ✕ appears.
     *
     * Passive-player confirmations are handled via the API to keep the test
     * synchronous; a separate test (test 4) verifies the game-end navigation.
     */
    @Test
    void clickingYellow16AfterRed16AlsoCrossesYellowLock() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Step 1: click RED "16"
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be clickable after RED lock is pending");

        // Step 2: click YELLOW "16"
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");

        // Both lock crosses must appear on player0's board
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED")
                         && BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");

        // Passive players acknowledge via API so the test stays deterministic
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED")
                         || BoardInteractionHelper.isRowClosed(d, "YELLOW")
                         || d.getCurrentUrl().contains("/score"));
        // At least one of the rows must close (or the game ends directly)
        assertTrue(
                BoardInteractionHelper.isRowClosed(driver0, "RED")
                || BoardInteractionHelper.isRowClosed(driver0, "YELLOW")
                || driver0.getCurrentUrl().contains("/score"),
                "After passive players pass, at least one row must close or the game must end");
    }

    // ── Test 4: each passive player's modal closes independently ─────────────────

    /**
     * When player0 declares a double-close (RED + YELLOW), both player1 and player2 see
     * the lock-intent modal.  The desired UX:
     * <ol>
     *   <li>Player1 clicks OK → player1's modal closes immediately, without waiting for player2.</li>
     *   <li>Player2 still has their modal open.</li>
     *   <li>Player2 clicks OK → player2's modal closes, both rows close, game ends.</li>
     * </ol>
     */
    @Test
    void eachPassiveModalClosesIndependentlyOnConfirm() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        driver2 = TestUtils.getDriver(sessionId, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);
        TestUtils.waitUntilBoardLoaded(driver2);

        // player0 declares double-close: RED then YELLOW
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        // Both passive players see the modal
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1), "player1 must see modal");
        assertTrue(BoardInteractionHelper.isModalVisible(driver2), "player2 must see modal");

        // Player1 confirms — their modal must close before player2 has acted
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "player1's modal must close immediately after they confirm, "
                + "without waiting for player2");

        // Player2's modal must still be open (they have not confirmed yet)
        assertTrue(BoardInteractionHelper.isModalVisible(driver2),
                "player2's modal must remain visible until they confirm");

        // Player2 confirms → both rows close, PASSIVE_MOVE phase starts for player1 and player2
        BoardInteractionHelper.clickModalConfirmButton(driver2);
        new WebDriverWait(driver2, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        assertFalse(BoardInteractionHelper.isModalVisible(driver2),
                "player2's modal must close after they confirm");

        // Both passive players get a final white+white turn before the game ends
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1);
        BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
        BoardInteractionHelper.clickPassButton(driver2);

        new WebDriverWait(driver0, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "game must end after both passive players have had their final turn");
    }

    // ── Test 5: closing both RED and YELLOW ends the game ────────────────────────

    /**
     * Closes RED via the UI (player0 clicks RED-16, all passive players confirm), then
     * advances the remaining turns via API until player0 closes YELLOW in a second turn.
     * With two rows locked the game ends and all three browsers navigate to the score screen.
     */
    @Test
    void closingBothRowsEndsGameAndShowsScoreScreen() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        driver2 = TestUtils.getDriver(sessionId, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);
        TestUtils.waitUntilBoardLoaded(driver2);

        // === Part 1: player0 closes RED via the UI ===
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver2);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED must be closed after part 1");

        // === Part 2: advance player1's and player2's turns via API ===
        // After RED locks, it's player1's turn (ROLL phase).
        advancePlayerTurnViaApi(pids.get(1), pids.get(0), pids.get(2));
        advancePlayerTurnViaApi(pids.get(2), pids.get(0), pids.get(1));

        // === Part 3: player0's second turn — close YELLOW ===
        api.roll(sessionId, pids.get(0));
        api.setDice(sessionId, 8, 8);
        api.setColoredDie(sessionId, "YELLOW", 8);

        String yellowRowId  = api.getRowId(sessionId, pids.get(0), YELLOW_ROW_INDEX);
        String yellowLastId = lastCellId(sessionId, pids.get(0), YELLOW_ROW_INDEX);

        // Cross YELLOW-16 via API then declare lock intent
        api.crossCell(sessionId, pids.get(0), yellowRowId, yellowLastId, false);
        api.declareLockIntent(sessionId, pids.get(0), yellowRowId);

        // Passive players acknowledge the YELLOW lock intent
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // Both rows now closed; passives get a final PASSIVE_MOVE turn before game ends
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // All browsers must navigate to the score screen
        new WebDriverWait(driver0, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver2, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));

        assertTrue(driver0.getCurrentUrl().contains("/score"),
                "Player0 must be on the score screen after both rows are locked");
        assertTrue(driver1.getCurrentUrl().contains("/score"),
                "Player1 must be on the score screen after both rows are locked");
        assertTrue(driver2.getCurrentUrl().contains("/score"),
                "Player2 must be on the score screen after both rows are locked");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Advances the given player's turn by rolling then passing (white+white = 16 is
     * available but they don't need to cross anything).  Other players also pass the
     * passive phase.
     */
    private void advancePlayerTurnViaApi(String activeId, String passive1, String passive2) {
        api.roll(sessionId, activeId);
        // Active player gives up (takes a punishment) since we don't care about the dice here.
        // PASS would be rejected — the active player must make at least one move before passing.
        api.giveUp(sessionId, activeId);
        // Passive players also pass their white+white opportunity
        api.pass(sessionId, passive1);
        api.pass(sessionId, passive2);
    }

    /** Returns the cell id of the last regular cell (the closing-eligible "16") in the given row. */
    @SuppressWarnings("unchecked")
    private String lastCellId(String sid, String playerId, int rowIndex) {
        Map<String, Object> state   = api.getGameState(sid);
        Map<String, Object> layouts = (Map<String, Object>) state.get("sheetLayouts");
        Map<String, Object> layout  = (Map<String, Object>) layouts.get(playerId);
        List<Map<String, Object>> rows  = (List<Map<String, Object>>) layout.get("rows");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) rows.get(rowIndex).get("cells");
        // The last regular cell is the second-to-last entry (the final entry is the lock cell).
        // cells list contains only regular cells (lock is in a separate lock field).
        return (String) cells.get(cells.size() - 1).get("id");
    }
}
