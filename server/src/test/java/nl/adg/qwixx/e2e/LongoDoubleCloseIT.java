package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Longo variant — "double row close" scenario.
 *
 * New architecture: clicking "16" (last closing cell) does NOT show a YES/NO modal.
 * The client auto-sends DECLARE_LOCK_INTENT after the cross, and the active player
 * must click the green Confirm button (EndTurn) to trigger EVALUATE and close the row.
 * Only "15" / "3" (second-to-last closing cell) shows the YES/NO modal.
 * <p>
 * Setup:
 *   - 3 players, Longo variant
 *   - player0 has 14 crosses in RED  (positions 0–13, values 2–15) — "16" is the last cell
 *   - player0 has 14 crosses in YELLOW (positions 0–13, values 2–15) — "16" is the last cell
 *   - white1 = 8, white2 = 8  →  white+white = 16  →  hits RED "16"
 *   - yellow die = 8           →  white+yellow = 16  →  hits YELLOW "16"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LongoDoubleCloseIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX    = 0; // ascending 2→16
    private static final int YELLOW_ROW_INDEX = 1; // ascending 2→16
    private static final int BLUE_ROW_INDEX   = 3; // descending 16→2

    private WebDriver driver0;
    private WebDriver driver1;
    private WebDriver driver2;
    private String    sessionId;
    private List<String> pids;

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
    void setup() {
        driver0 = TestUtils.ensureAlive(driver0);
        driver1 = TestUtils.ensureAlive(driver1);
        driver2 = TestUtils.ensureAlive(driver2);
        sessionId = api.createGame(3, Map.of("base", "LONGO"));
        pids      = api.getPlayerIds(sessionId);

        api.setCrosses(sessionId, pids.getFirst(), RED_ROW_INDEX,    14);
        api.setCrosses(sessionId, pids.getFirst(), YELLOW_ROW_INDEX, 14);
        api.setCrosses(sessionId, pids.get(1), 2, 5);
        api.setCrosses(sessionId, pids.get(2), BLUE_ROW_INDEX, 4);

        api.roll(sessionId, pids.getFirst());
        api.setDice(sessionId, 8, 8);          // white+white = 16  → RED "16"
        api.setColoredDie(sessionId, "YELLOW", 8); // white+yellow = 16 → YELLOW "16"
    }

    // ── Test 1: clicking RED "16" auto-crosses the RED lock ──────────────────────
    //
    // "16" is the last closing cell — no YES/NO modal.
    // The client auto-sends DECLARE_LOCK_INTENT after the cross.
    // Active player confirms → passives see modal → passives EndTurn → EVALUATE → RED closes.

    @Test
    void clickingRed16AutoCrossesRedLockAndClosesRow() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());
        TestUtils.navigateTo(driver1, sessionId, pids.get(1));
        TestUtils.navigateTo(driver2, sessionId, pids.get(2));

        assertFalse(isLockButtonCrossed(driver0, "RED"),
                "RED lock must not be crossed before clicking RED-16");
        assertFalse(isRowClosed(driver0, "RED"),
                "RED row must not be closed before clicking RED-16");

        // Clicking "16" (last closing cell): no YES/NO modal — auto-declares intent
        clickCellByValue(driver0, "RED", "16");

        // Lock cross appears immediately on player0's board (pendingAutoLock indicator)
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross (✕) must appear on player0's board after clicking RED-16");
        assertFalse(isModalVisible(driver0),
                "The declaring player must NOT see the row-closure modal for the last closing cell");

        // Passive players see the closure modal (DECLARE_LOCK_INTENT auto-fired)
        waitUntilModalVisible(driver1, 8);
        waitUntilModalVisible(driver2, 8);
        assertTrue(isModalVisible(driver1), "Player1 must see the row-closure modal");
        assertTrue(isModalVisible(driver2), "Player2 must see the row-closure modal");

        // Both passives dismiss the notification modal (OK = not EndTurn), then EndTurn via pass button.
        clickModalConfirmButton(driver1); // dismiss notification
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 EndTurns
        clickModalConfirmButton(driver2); // dismiss notification
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2); // player2 EndTurns

        // Active player EndTurns → passive queue empty → EVALUATE → RED closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED"));
        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED"));
        assertTrue(isRowClosed(driver0, "RED"),
                "RED row must be closed after all players complete their turns");
        assertTrue(isRowClosed(driver1, "RED"),
                "RED row must be closed in player1's browser too");
    }

    // ── Test 2: YELLOW "16" is clickable after RED lock is pending ───────────────

    @Test
    void yellow16IsClickableAndCrossesSecondLockAfterRed16IsCrossed() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());

        // Click RED "16" — no YES/NO modal, auto-declares, lock cross appears
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        // YELLOW "16" must be shown as clickable (reachable via white+yellow die)
        assertTrue(isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be shown as clickable (reachable via white+yellow=16) "
                + "even while RED lock intent is pending");

        // Clicking YELLOW "16" — no modal for last closing cell, auto-lock setup fires
        clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");
        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
    }

    // ── Test 3: clicking YELLOW "16" also crosses the YELLOW lock ────────────────

    @Test
    void clickingYellow16AfterRed16AlsoCrossesYellowLock() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());

        // Step 1: click RED "16" — no modal, auto-declares, lock cross appears
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        assertTrue(isCellClickable(driver0, "YELLOW", "16"),
                "YELLOW-16 must be clickable after RED lock is pending");

        // Step 2: click YELLOW "16" — no modal for last closing cell
        clickCellByValue(driver0, "YELLOW", "16");

        // Both lock crosses must appear
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED")
                         && isLockButtonCrossed(d, "YELLOW"));

        assertTrue(isLockButtonCrossed(driver0, "RED"),
                "RED lock cross must still be visible after YELLOW-16 is clicked");
        assertTrue(isLockButtonCrossed(driver0, "YELLOW"),
                "YELLOW lock cross must appear after clicking YELLOW-16");

        // Passive players EndTurn via API
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // Active player confirms → EVALUATE → both rows close
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED")
                         || isRowClosed(d, "YELLOW")
                         || d.getCurrentUrl().contains("/score"));
        assertTrue(
                isRowClosed(driver0, "RED")
                || isRowClosed(driver0, "YELLOW")
                || driver0.getCurrentUrl().contains("/score"),
                "After all players complete their turns, at least one row must close or the game must end");
    }

    // ── Test 4: each passive player's modal closes independently ─────────────────

    @Test
    void eachPassiveModalClosesIndependentlyOnConfirm() {
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());
        TestUtils.navigateTo(driver1, sessionId, pids.get(1));
        TestUtils.navigateTo(driver2, sessionId, pids.get(2));

        // player0 clicks RED "16" — no modal, auto-declares
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        // player0 clicks YELLOW "16" — no modal, auto-declares
        clickCellByValue(driver0, "YELLOW", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "YELLOW"));

        // player0 confirms → PASSIVE_MOVE starts, both passives see the modal
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        waitUntilModalVisible(driver1, 8);
        waitUntilModalVisible(driver2, 8);
        assertTrue(isModalVisible(driver1), "player1 must see modal");
        assertTrue(isModalVisible(driver2), "player2 must see modal");

        // Player1 confirms — their modal must close immediately
        clickModalConfirmButton(driver1);
        new WebDriverWait(driver1, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver1),
                "player1's modal must close immediately after they confirm, without waiting for player2");

        // Player2's modal must still be open
        assertTrue(isModalVisible(driver2),
                "player2's modal must remain visible until they confirm");

        // Player2 dismisses notification modal (OK = not EndTurn), then EndTurns via pass button.
        clickModalConfirmButton(driver2); // dismiss notification
        new WebDriverWait(driver2, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));
        assertFalse(isModalVisible(driver2),
                "player2's modal must close after they dismiss");

        // Both passives EndTurn via board pass button → EVALUATE → 2 rows → game over.
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 EndTurns in PASSIVE_MOVE
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2); // player2 EndTurns → EVALUATE

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
        TestUtils.navigateTo(driver0, sessionId, pids.getFirst());
        TestUtils.navigateTo(driver1, sessionId, pids.get(1));
        TestUtils.navigateTo(driver2, sessionId, pids.get(2));

        // === Part 1: player0 closes RED via the UI ===
        // "16" — no modal, auto-declares, passives see the closure modal
        clickCellByValue(driver0, "RED", "16");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "RED"));

        // Both passives dismiss notification modal, then EndTurn via pass button.
        waitUntilModalVisible(driver1, 8);
        clickModalConfirmButton(driver1); // dismiss notification
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1); // player1 EndTurns

        waitUntilModalVisible(driver2, 8);
        clickModalConfirmButton(driver2); // dismiss notification
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2); // player2 EndTurns

        // Active player0 EndTurns → passive queue empty → EVALUATE → RED closes
        waitUntilPassButtonVisible(driver0, 5);
        clickPassButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(8))
                .until(d -> isRowClosed(d, "RED"));
        assertTrue(isRowClosed(driver0, "RED"), "RED must be closed after part 1");

        // === Part 2: advance player1's and player2's turns via API ===
        advancePlayerTurnViaApi(pids.get(1), pids.getFirst(), pids.get(2));
        advancePlayerTurnViaApi(pids.get(2), pids.getFirst(), pids.get(1));

        // === Part 3: player0's second turn — close YELLOW ===
        api.roll(sessionId, pids.getFirst());
        api.setDice(sessionId, 8, 8);
        api.setColoredDie(sessionId, "YELLOW", 8);

        String yellowRowId  = api.getRowId(sessionId, pids.getFirst(), YELLOW_ROW_INDEX);
        String yellowLastId = lastCellId(sessionId, pids.getFirst(), YELLOW_ROW_INDEX);

        // Cross YELLOW-16 via API then declare lock intent, then EndTurn
        api.crossCell(sessionId, pids.getFirst(), yellowRowId, yellowLastId, false);
        api.declareLockIntent(sessionId, pids.getFirst(), yellowRowId);

        // Passive players EndTurn (1 step each)
        api.pass(sessionId, pids.get(1));
        api.pass(sessionId, pids.get(2));

        // Active player EndTurns → EVALUATE → YELLOW closes → game over
        api.pass(sessionId, pids.getFirst());

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
        api.setCrosses(sid, pids3.getFirst(), RED_ROW_INDEX,  14);
        api.setCrosses(sid, pids3.getFirst(), BLUE_ROW_INDEX, 15); // positions 0-14 = "16"-"2"

        // player2: 6 crosses on BLUE (positions 0-5 = "16"-"11")
        api.setCrosses(sid, pids3.get(2), BLUE_ROW_INDEX, 6);

        // Roll; white+white=16 so player0 can cross RED "16" (last closing cell)
        api.roll(sid, pids3.getFirst());
        api.setDice(sid, 8, 8);

        // player0 declares BLUE lock intent (permanent "2" qualifies — no cell crossed needed)
        String blueRowId0 = api.getRowId(sid, pids3.getFirst(), BLUE_ROW_INDEX);
        api.declareLockIntent(sid, pids3.getFirst(), blueRowId0);

        // player0 crosses RED "16" (auto-detected as intent at EndTurn) and EndTurns
        String redRowId0    = api.getRowId(sid, pids3.getFirst(), RED_ROW_INDEX);
        String redLastId    = lastCellId(sid, pids3.getFirst(), RED_ROW_INDEX);
        api.crossCell(sid, pids3.getFirst(), redRowId0, redLastId, false);
        api.pass(sid, pids3.getFirst()); // EndTurn → autoDetect RED → phase PASSIVE_MOVE

        // Override dice: white+white=3 so player2 can cross BLUE "3" (second-to-last, value=3)
        api.setDice(sid, 1, 2);

        TestUtils.navigateTo(driver1, sid, pids3.get(1));
        TestUtils.navigateTo(driver2, sid, pids3.get(2));

        // Both passives see the notification modal (player0 declared RED and BLUE).
        waitUntilModalVisible(driver1, 20);
        waitUntilModalVisible(driver2, 20);

        // player1 dismisses and EndTurns immediately
        clickModalConfirmButton(driver1);
        waitUntilPassButtonVisible(driver1, 5);
        clickPassButton(driver1);

        // player2 dismisses notification so the board is accessible
        clickModalConfirmButton(driver2);

        // player2 clicks BLUE "3" (second-to-last closing cell) → YES/NO modal → YES
        // The server will reject DECLARE_LOCK_INTENT (BLUE already declared by player0),
        // but player2 still qualifies for the lock cross as a co-locker.
        clickCellByValue(driver2, "BLUE", "3");
        waitUntilModalVisible(driver2, 5);
        clickModalYesButton(driver2);

        // Lock cross must appear (via pendingAutoLock) even though DECLARE_LOCK_INTENT is rejected
        new WebDriverWait(driver2, Duration.ofSeconds(5))
                .until(d -> isLockButtonCrossed(d, "BLUE"));
        assertTrue(isLockButtonCrossed(driver2, "BLUE"),
                "player2 must see BLUE lock cross after clicking YES on '3'");

        // player2 EndTurns → EVALUATE: RED and BLUE both close → 2 rows → game over.
        // Fix: undo buffer cleared AFTER evaluate → canCrossLock sees "3" → lockCrossed=true.
        // Bug: undo buffer cleared BEFORE evaluate → lockCrossed=false → cross disappears.
        waitUntilPassButtonVisible(driver2, 5);
        clickPassButton(driver2);

        // After game-over state arrives the 1500 ms navigation timer fires.
        // During that window the lock cross must remain visible via rowState.lockCrossed=true
        // (not just via the now-cleared pendingAutoLock).
        new WebDriverWait(driver2, Duration.ofSeconds(8))
                .until(d -> isLockButtonCrossed(d, "BLUE")
                         || d.getCurrentUrl().contains("/score"));
        if (!driver2.getCurrentUrl().contains("/score")) {
            assertTrue(isLockButtonCrossed(driver2, "BLUE"),
                    "BLUE lock cross must remain visible in the game-over board state " +
                    "before the score screen appears");
        }

        // All browsers navigate to score screen
        new WebDriverWait(driver2, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        new WebDriverWait(driver1, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/score"));
        assertTrue(driver2.getCurrentUrl().contains("/score"), "player2 must reach score screen");

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
        return (String) cells.getLast().get("id");
    }
}
