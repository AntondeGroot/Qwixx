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
 * New architecture: clicking "16" (last closing cell) does NOT show a YES/NO modal.
 * The client auto-sends DECLARE_LOCK_INTENT after the cross, and the active player
 * must click the green Confirm button (EndTurn) to trigger EVALUATE and close the row.
 * Only "15" / "3" (second-to-last closing cell) shows the YES/NO modal.
 *
 * Setup:
 *   - 3 players, Longo variant
 *   - player0 has 14 crosses in RED  (positions 0–13, values 2–15) — "16" is the last cell
 *   - player0 has 14 crosses in YELLOW (positions 0–13, values 2–15) — "16" is the last cell
 *   - white1 = 8, white2 = 8  →  white+white = 16  →  hits RED "16"
 *   - yellow die = 8           →  white+yellow = 16  →  hits YELLOW "16"
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

        api.setCrosses(sessionId, pids.get(0), RED_ROW_INDEX,    14);
        api.setCrosses(sessionId, pids.get(0), YELLOW_ROW_INDEX, 14);
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
    //
    // "16" is the last closing cell — no YES/NO modal.
    // The client auto-sends DECLARE_LOCK_INTENT after the cross.
    // Active player confirms → passives see modal → passives EndTurn → EVALUATE → RED closes.

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

        // Clicking "16" (last closing cell): no YES/NO modal — auto-declares intent
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");

        // Lock cross appears immediately on player0's board (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross (✕) must appear on player0's board after clicking RED-16");
        assertFalse(BoardInteractionHelper.isModalVisible(driver0),
                "The declaring player must NOT see the row-closure modal for the last closing cell");

        // Passive players see the closure modal (DECLARE_LOCK_INTENT auto-fired)
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1), "Player1 must see the row-closure modal");
        assertTrue(BoardInteractionHelper.isModalVisible(driver2), "Player2 must see the row-closure modal");

        // Both passives dismiss the notification modal (OK = not EndTurn), then EndTurn via pass button.
        BoardInteractionHelper.clickModalConfirmButton(driver1); // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1); // player1 EndTurns
        BoardInteractionHelper.clickModalConfirmButton(driver2); // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
        BoardInteractionHelper.clickPassButton(driver2); // player2 EndTurns

        // Active player EndTurns → passive queue empty → EVALUATE → RED closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"),
                "RED row must be closed after all players complete their turns");
        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "RED"),
                "RED row must be closed in player1's browser too");
    }

    // ── Test 2: YELLOW "16" is clickable after RED lock is pending ───────────────

    @Test
    void yellow16IsClickableAndCrossesSecondLockAfterRed16IsCrossed() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Click RED "16" — no YES/NO modal, auto-declares, lock cross appears
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // YELLOW "16" must be shown as clickable (reachable via white+yellow die)
        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be shown as clickable (reachable via white+yellow=16) "
                + "even while RED lock intent is pending");

        // Clicking YELLOW "16" — no modal for last closing cell, auto-lock setup fires
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
    }

    // ── Test 3: clicking YELLOW "16" also crosses the YELLOW lock ────────────────

    @Test
    void clickingYellow16AfterRed16AlsoCrossesYellowLock() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        TestUtils.waitUntilBoardLoaded(driver0);

        // Step 1: click RED "16" — no modal, auto-declares, lock cross appears
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        assertTrue(BoardInteractionHelper.isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be clickable after RED lock is pending");

        // Step 2: click YELLOW "16" — no modal for last closing cell
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");

        // Both lock crosses must appear
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED")
                         && BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
        assertTrue(BoardInteractionHelper.isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");

        // Passive players EndTurn via API
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // Active player confirms → EVALUATE → both rows close
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED")
                         || BoardInteractionHelper.isRowClosed(d, "YELLOW")
                         || d.getCurrentUrl().contains("/score"));
        assertTrue(
                BoardInteractionHelper.isRowClosed(driver0, "RED")
                || BoardInteractionHelper.isRowClosed(driver0, "YELLOW")
                || driver0.getCurrentUrl().contains("/score"),
                "After all players complete their turns, at least one row must close or the game must end");
    }

    // ── Test 4: each passive player's modal closes independently ─────────────────

    @Test
    void eachPassiveModalClosesIndependentlyOnConfirm() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        driver2 = TestUtils.getDriver(sessionId, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);
        TestUtils.waitUntilBoardLoaded(driver2);

        // player0 clicks RED "16" — no modal, auto-declares
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // player0 clicks YELLOW "16" — no modal, auto-declares
        BoardInteractionHelper.clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "YELLOW"));

        // player0 confirms → PASSIVE_MOVE starts, both passives see the modal
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        assertTrue(BoardInteractionHelper.isModalVisible(driver1), "player1 must see modal");
        assertTrue(BoardInteractionHelper.isModalVisible(driver2), "player2 must see modal");

        // Player1 confirms — their modal must close immediately
        BoardInteractionHelper.clickModalConfirmButton(driver1);
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
                "player1's modal must close immediately after they confirm, without waiting for player2");

        // Player2's modal must still be open
        assertTrue(BoardInteractionHelper.isModalVisible(driver2),
                "player2's modal must remain visible until they confirm");

        // Player2 dismisses notification modal (OK = not EndTurn), then EndTurns via pass button.
        BoardInteractionHelper.clickModalConfirmButton(driver2); // dismiss notification
        new WebDriverWait(driver2, Duration.ofSeconds(5))
                .until(d -> !BoardInteractionHelper.isModalVisible(d));
        assertFalse(BoardInteractionHelper.isModalVisible(driver2),
                "player2's modal must close after they dismiss");

        // Both passives EndTurn via board pass button → EVALUATE → 2 rows → game over.
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1); // player1 EndTurns in PASSIVE_MOVE
        BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
        BoardInteractionHelper.clickPassButton(driver2); // player2 EndTurns → EVALUATE

        // 2 closed rows → game over → all browsers navigate to score screen
        new WebDriverWait(driver0, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver2, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver0.getCurrentUrl().contains("/score"), "player0 must be on score screen");
        assertTrue(driver1.getCurrentUrl().contains("/score"), "player1 must be on score screen");
        assertTrue(driver2.getCurrentUrl().contains("/score"), "player2 must be on score screen");
    }

    // ── Test 5: closing both RED and YELLOW ends the game ────────────────────────

    @Test
    void closingBothRowsEndsGameAndShowsScoreScreen() {
        driver0 = TestUtils.getDriver(sessionId, pids.get(0));
        driver1 = TestUtils.getDriver(sessionId, pids.get(1));
        driver2 = TestUtils.getDriver(sessionId, pids.get(2));
        TestUtils.waitUntilBoardLoaded(driver0);
        TestUtils.waitUntilBoardLoaded(driver1);
        TestUtils.waitUntilBoardLoaded(driver2);

        // === Part 1: player0 closes RED via the UI ===
        // "16" — no modal, auto-declares, passives see the closure modal
        BoardInteractionHelper.clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "RED"));

        // Both passives dismiss notification modal, then EndTurn via pass button.
        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver1); // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver1, 5);
        BoardInteractionHelper.clickPassButton(driver1); // player1 EndTurns

        BoardInteractionHelper.waitUntilModalVisible(driver2, 8);
        BoardInteractionHelper.clickModalConfirmButton(driver2); // dismiss notification
        BoardInteractionHelper.waitUntilPassButtonVisible(driver2, 5);
        BoardInteractionHelper.clickPassButton(driver2); // player2 EndTurns

        // Active player0 EndTurns → passive queue empty → EVALUATE → RED closes
        BoardInteractionHelper.waitUntilPassButtonVisible(driver0, 5);
        BoardInteractionHelper.clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> BoardInteractionHelper.isRowClosed(d, "RED"));
        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "RED"), "RED must be closed after part 1");

        // === Part 2: advance player1's and player2's turns via API ===
        advancePlayerTurnViaApi(pids.get(1), pids.get(0), pids.get(2));
        advancePlayerTurnViaApi(pids.get(2), pids.get(0), pids.get(1));

        // === Part 3: player0's second turn — close YELLOW ===
        api.roll(sessionId, pids.get(0));
        api.setDice(sessionId, 8, 8);
        api.setColoredDie(sessionId, "YELLOW", 8);

        String yellowRowId  = api.getRowId(sessionId, pids.get(0), YELLOW_ROW_INDEX);
        String yellowLastId = lastCellId(sessionId, pids.get(0), YELLOW_ROW_INDEX);

        // Cross YELLOW-16 via API then declare lock intent, then EndTurn
        api.crossCell(sessionId, pids.get(0), yellowRowId, yellowLastId, false);
        api.declareLockIntent(sessionId, pids.get(0), yellowRowId);

        // Passive players EndTurn (1 step each)
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // Active player EndTurns → EVALUATE → YELLOW closes → game over
        api.pass(sessionId, pids.get(0));

        // All browsers must navigate to the score screen
        new WebDriverWait(driver0, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver2, Duration.ofSeconds(12))
                .until(d -> d.getCurrentUrl().contains("/score"));

        assertTrue(driver0.getCurrentUrl().contains("/score"), "Player0 must be on score screen");
        assertTrue(driver1.getCurrentUrl().contains("/score"), "Player1 must be on score screen");
        assertTrue(driver2.getCurrentUrl().contains("/score"), "Player2 must be on score screen");
    }

    // ── Test 6: passive co-locker's lock cross survives the game-over transition ──
    //
    // Regression (scenario 11): player0 (active) closes RED and BLUE in one turn.
    // player2 (passive) crossed BLUE "3" (second-to-last closing cell) the same turn
    // and is a NON-DECLARANT (player0 already declared BLUE).
    //
    // Bug: endTurnInPassiveMove cleared the undo buffer BEFORE evaluate(), so
    //      canCrossLock returned false for player2 → lockCrossed=false.
    //      The client's pendingAutoLock was then cleared on game-over → lock cross
    //      disappeared for ~1.5 s before the score screen appeared.
    //
    // Fix: undo buffer is now cleared AFTER evaluate() in endTurnInPassiveMove,
    //      so canCrossLock sees "3" as a pending cross and returns true → lockCrossed=true.

    @Test
    @SuppressWarnings("unchecked")
    void passive_coLocker_lockCrossRemainsVisible_beforeScoreScreen() {
        String sid = api.createGame(3, Map.of("base", "LONGO"));
        List<String> pids3 = api.getPlayerIds(sid);

        // player0: 14 crosses on RED ascending ("2"-"15"), all 15 on BLUE descending (incl. "2")
        api.setCrosses(sid, pids3.get(0), RED_ROW_INDEX,  14);
        api.setCrosses(sid, pids3.get(0), BLUE_ROW_INDEX, 15); // positions 0-14 = "16"-"2"

        // player2: 6 crosses on BLUE (positions 0-5 = "16"-"11")
        api.setCrosses(sid, pids3.get(2), BLUE_ROW_INDEX, 6);

        // Roll; white+white=16 so player0 can cross RED "16" (last closing cell)
        api.roll(sid, pids3.get(0));
        api.setDice(sid, 8, 8);

        // player0 declares BLUE lock intent (permanent "2" qualifies — no cell crossed needed)
        String blueRowId0 = api.getRowId(sid, pids3.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids3.get(0), blueRowId0);

        // player0 crosses RED "16" (auto-detected as intent at EndTurn) and EndTurns
        String redRowId0    = api.getRowId(sid, pids3.get(0), RED_ROW_INDEX);
        String redLastId    = lastCellId(sid, pids3.get(0), RED_ROW_INDEX);
        api.crossCell(sid, pids3.get(0), redRowId0, redLastId, false);
        api.pass(sid, pids3.get(0)); // EndTurn → autoDetect RED → phase PASSIVE_MOVE

        // Override dice: white+white=3 so player2 can cross BLUE "3" (second-to-last, value=3)
        api.setDice(sid, 1, 2);

        WebDriver d1 = TestUtils.getDriver(sid, pids3.get(1));
        WebDriver d2 = TestUtils.getDriver(sid, pids3.get(2));
        TestUtils.waitUntilBoardLoaded(d1);
        TestUtils.waitUntilBoardLoaded(d2);

        try {
            // Both passives see the notification modal (player0 declared RED and BLUE)
            BoardInteractionHelper.waitUntilModalVisible(d1, 8);
            BoardInteractionHelper.waitUntilModalVisible(d2, 8);

            // player1 dismisses and EndTurns immediately
            BoardInteractionHelper.clickModalConfirmButton(d1);
            BoardInteractionHelper.waitUntilPassButtonVisible(d1, 5);
            BoardInteractionHelper.clickPassButton(d1);

            // player2 dismisses notification so the board is accessible
            BoardInteractionHelper.clickModalConfirmButton(d2);

            // player2 clicks BLUE "3" (second-to-last closing cell) → YES/NO modal → YES
            // The server will reject DECLARE_LOCK_INTENT (BLUE already declared by player0),
            // but player2 still qualifies for the lock cross as a co-locker.
            BoardInteractionHelper.clickCellByValue(d2, "BLUE", "3");
            BoardInteractionHelper.waitUntilModalVisible(d2, 5);
            BoardInteractionHelper.clickModalYesButton(d2);

            // Lock cross must appear (via pendingAutoLock) even though DECLARE_LOCK_INTENT is rejected
            new WebDriverWait(d2, Duration.ofSeconds(5))
                    .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE"));
            assertTrue(BoardInteractionHelper.isLockButtonCrossed(d2, "BLUE"),
                    "player2 must see BLUE lock cross after clicking YES on '3'");

            // player2 EndTurns → EVALUATE: RED and BLUE both close → 2 rows → game over.
            // Fix: undo buffer cleared AFTER evaluate → canCrossLock sees "3" → lockCrossed=true.
            // Bug: undo buffer cleared BEFORE evaluate → lockCrossed=false → cross disappears.
            BoardInteractionHelper.waitUntilPassButtonVisible(d2, 5);
            BoardInteractionHelper.clickPassButton(d2);

            // After game-over state arrives the 1500 ms navigation timer fires.
            // During that window the lock cross must remain visible via rowState.lockCrossed=true
            // (not just via the now-cleared pendingAutoLock).
            new WebDriverWait(d2, Duration.ofSeconds(8))
                    .until(d -> BoardInteractionHelper.isLockButtonCrossed(d, "BLUE")
                             || d.getCurrentUrl().contains("/score"));
            if (!d2.getCurrentUrl().contains("/score")) {
                assertTrue(BoardInteractionHelper.isLockButtonCrossed(d2, "BLUE"),
                        "BLUE lock cross must remain visible in the game-over board state " +
                        "before the score screen appears");
            }

            // All browsers navigate to score screen
            new WebDriverWait(d2, Duration.ofSeconds(10))
                    .until(d -> d.getCurrentUrl().contains("/score"));
            new WebDriverWait(d1, Duration.ofSeconds(10))
                    .until(d -> d.getCurrentUrl().contains("/score"));
            assertTrue(d2.getCurrentUrl().contains("/score"), "player2 must reach score screen");

            // Server-side confirmation: lockCrossed=true for player2's BLUE row in final state.
            // This is the root assertion — without it the visual cross would disappear.
            Map<String, Object> state3     = api.getGameState(sid);
            Map<String, Object> progMap    = (Map<String, Object>) state3.get("sheetProgress");
            Map<String, Object> p2prog     = (Map<String, Object>) progMap.get(pids3.get(2));
            Map<String, Object> rowStates  = (Map<String, Object>) p2prog.get("rowStates");
            Map<String, Object> layouts    = (Map<String, Object>) state3.get("sheetLayouts");
            Map<String, Object> p2layout   = (Map<String, Object>) layouts.get(pids3.get(2));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) p2layout.get("rows");
            String blueRowId2              = (String) rows.get(BLUE_ROW_INDEX).get("id");
            Map<String, Object> blueState  = (Map<String, Object>) rowStates.get(blueRowId2);
            assertTrue(blueState != null && Boolean.TRUE.equals(blueState.get("lockCrossed")),
                    "player2 must have lockCrossed=true for BLUE in the final game state — " +
                    "without this the lock cross disappears before the score screen");
        } finally {
            d1.quit();
            d2.quit();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void advancePlayerTurnViaApi(String activeId, String passive1, String passive2) {
        api.roll(sessionId, activeId);
        api.giveUp(sessionId, activeId);   // active gives up (no useful move)
        api.pass(sessionId, passive1);     // passives pass
        api.pass(sessionId, passive2);
    }

    @SuppressWarnings("unchecked")
    private String lastCellId(String sid, String playerId, int rowIndex) {
        Map<String, Object> state   = api.getGameState(sid);
        Map<String, Object> layouts = (Map<String, Object>) state.get("sheetLayouts");
        Map<String, Object> layout  = (Map<String, Object>) layouts.get(playerId);
        List<Map<String, Object>> rows  = (List<Map<String, Object>>) layout.get("rows");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) rows.get(rowIndex).get("cells");
        return (String) cells.get(cells.size() - 1).get("id");
    }
}
