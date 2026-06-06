package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.clickCellByValue;
import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.getCrossedCellCount;
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
 * End-to-end tests for two reported broken scenarios in the turn flow:
 *
 *   (a) A passive player can make a white+white cross while the active player
 *       is in ACTIVE_MOVE (before ending their turn).
 *
 *   (b) After the active player ends their turn and the passive player passes,
 *       the former passive player becomes the new active player in ROLL phase
 *       and can roll dice and then cross a cell.
 *
 * RED row (index 0) is ascending: 2, 3, 4 … 12.
 * Dice white1=1, white2=1 → white+white = 2 → RED "2" is always reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TurnFlowIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX = 0;

    private WebDriver driver1;
    private String sessionId;
    private List<String> playerIds;

    @BeforeAll
    void openDriver() {
        driver1 = TestUtils.createDriver();
    }

    @AfterAll
    void closeDriver() {
        if (driver1 != null) { driver1.quit(); driver1 = null; }
    }

    @BeforeEach
    void createGame() {
        driver1   = TestUtils.ensureAlive(driver1);
        sessionId = api.createGame(2);
        playerIds = api.getPlayerIds(sessionId);
        // player0 is the first active player; player1 is initially passive
    }

    // ── (a) Passive player can cross during ACTIVE_MOVE ───────────────────────

    /**
     * API-level: the server must accept a CROSS_WHITE_WHITE from the passive
     * player while the active player is still in ACTIVE_MOVE.
     */
    @Test
    void passivePlayerCanCrossWhiteWhiteCell_duringActiveTurn_viaApi() {
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1); // white+white = 2

        String rowId  = api.getRowId(sessionId, playerIds.get(1), RED_ROW_INDEX);
        String cellId = getCellId(sessionId, playerIds.get(1), RED_ROW_INDEX, 0); // RED "2"

        assertDoesNotThrow(() ->
                api.crossCell(sessionId, playerIds.get(1), rowId, cellId, false),
                "Server must accept a white+white cross from the passive player during ACTIVE_MOVE");

        assertEquals(1, getCrossedCount(sessionId, playerIds.get(1), RED_ROW_INDEX),
                "Passive player should have 1 crossed cell in RED after the white+white cross");
    }

    /**
     * Browser-level: the passive player's own board must show the white+white
     * cell as clickable and record the cross when clicked.
     */
    @Test
    void passivePlayerCanCrossCell_inBrowser_duringActiveTurn() {
        // Pre-roll via API so the board loads already in ACTIVE_MOVE with known dice
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1); // white+white = 2 → RED "2" reachable

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        clickCellByValue(driver1, "RED", "2");

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        assertEquals(1, getCrossedCellCount(driver1, "RED"),
                "Passive player's board must show +1 cross in RED after clicking the white+white cell");
    }

    // ── (b) New active player can roll and cross ──────────────────────────────

    /**
     * API-level: after the active player crosses a cell and ends their turn, and
     * the passive player passes, the game must advance to the next ROLL phase
     * with the former passive player as the new active player who can then roll
     * dice and cross a cell.
     */
    @Test
    void newActivePlayerCanRollAndCross_afterTurnTransition_viaApi() {
        // player0 (active) rolls, crosses one cell, then ends turn
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        String p0RowId  = api.getRowId(sessionId, playerIds.get(0), RED_ROW_INDEX);
        String p0CellId = getCellId(sessionId, playerIds.get(0), RED_ROW_INDEX, 0); // RED "2"
        api.crossCell(sessionId, playerIds.get(0), p0RowId, p0CellId, false);
        api.pass(sessionId, playerIds.get(0)); // active EndTurn → PASSIVE_MOVE

        // player1 (passive) passes without crossing → EVALUATE → player1 becomes active
        api.pass(sessionId, playerIds.get(1));

        Map<String, Object> afterTransition = api.getGameState(sessionId);
        assertEquals("ROLL", turnPhase(afterTransition),
                "Phase must be ROLL at the start of player1's turn");
        assertEquals(playerIds.get(1), activePlayerId(afterTransition),
                "player1 must be the new active player");

        // player1 rolls and crosses a cell as the new active player
        api.roll(sessionId, playerIds.get(1));
        api.setDice(sessionId, 1, 1);

        String p1RowId  = api.getRowId(sessionId, playerIds.get(1), RED_ROW_INDEX);
        String p1CellId = getCellId(sessionId, playerIds.get(1), RED_ROW_INDEX, 0); // player1's RED "2"
        assertDoesNotThrow(() ->
                api.crossCell(sessionId, playerIds.get(1), p1RowId, p1CellId, false),
                "New active player must be able to cross a cell after rolling");

        assertEquals(1, getCrossedCount(sessionId, playerIds.get(1), RED_ROW_INDEX),
                "New active player (player1) should have 1 crossed cell in RED");
    }

    /**
     * Browser-level: once the turn has advanced to player1 via API, player1's
     * board must allow crossing a cell as the active player after a roll.
     */
    @Test
    void newActivePlayerCanRollAndCross_inBrowser() {
        // Advance turn fully to player1's ACTIVE_MOVE via API before opening the browser.
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        String p0RowId  = api.getRowId(sessionId, playerIds.get(0), RED_ROW_INDEX);
        String p0CellId = getCellId(sessionId, playerIds.get(0), RED_ROW_INDEX, 0);
        api.crossCell(sessionId, playerIds.get(0), p0RowId, p0CellId, false);
        api.pass(sessionId, playerIds.get(0)); // → PASSIVE_MOVE
        api.pass(sessionId, playerIds.get(1)); // → EVALUATE → player1 ROLL

        // Roll and fix dice before opening the browser
        api.roll(sessionId, playerIds.get(1));
        api.setDice(sessionId, 1, 1); // white+white = 2 → RED "2" reachable

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        clickCellByValue(driver1, "RED", "2");

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        assertEquals(1, getCrossedCellCount(driver1, "RED"),
                "New active player (player1) must be able to cross RED '2' as the active player in the browser");
    }

    // ── (c) Passive player cross during PASSIVE_MOVE ─────────────────────────

    /**
     * API-level: once the active player ends their turn (→ PASSIVE_MOVE), the
     * passive player must still be able to make their white+white cross.
     */
    @Test
    void passivePlayerCanCrossWhiteWhiteCell_duringPassiveMovPhase_viaApi() {
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1); // white+white = 2

        // Active player crosses with white+white (required before EndTurn)
        String p0RowId  = api.getRowId(sessionId, playerIds.get(0), RED_ROW_INDEX);
        String p0CellId = getCellId(sessionId, playerIds.get(0), RED_ROW_INDEX, 0);
        api.crossCell(sessionId, playerIds.get(0), p0RowId, p0CellId, false);
        api.pass(sessionId, playerIds.get(0)); // → PASSIVE_MOVE

        // Passive player's cross during PASSIVE_MOVE
        String p1RowId  = api.getRowId(sessionId, playerIds.get(1), RED_ROW_INDEX);
        String p1CellId = getCellId(sessionId, playerIds.get(1), RED_ROW_INDEX, 0);
        assertDoesNotThrow(() ->
                api.crossCell(sessionId, playerIds.get(1), p1RowId, p1CellId, false),
                "Server must accept a white+white cross from the passive player during PASSIVE_MOVE");

        assertEquals(1, getCrossedCount(sessionId, playerIds.get(1), RED_ROW_INDEX),
                "Passive player should have 1 crossed cell in RED after crossing in PASSIVE_MOVE");
    }

    /**
     * Browser-level: passive player's board must show the white+white cell as
     * clickable during PASSIVE_MOVE and record the cross when clicked.
     */
    @Test
    void passivePlayerCanCrossCell_inBrowser_duringPassiveMovePhase() {
        // Advance to PASSIVE_MOVE before opening the browser
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        String p0RowId  = api.getRowId(sessionId, playerIds.get(0), RED_ROW_INDEX);
        String p0CellId = getCellId(sessionId, playerIds.get(0), RED_ROW_INDEX, 0);
        api.crossCell(sessionId, playerIds.get(0), p0RowId, p0CellId, false);
        api.pass(sessionId, playerIds.get(0)); // → PASSIVE_MOVE

        TestUtils.navigateTo(driver1, sessionId, playerIds.get(1));

        clickCellByValue(driver1, "RED", "2");

        new WebDriverWait(driver1, Duration.ofSeconds(8))
                .until(d -> getCrossedCellCount(d, "RED") >= 1);

        assertEquals(1, getCrossedCellCount(driver1, "RED"),
                "Passive player's board must show +1 cross in RED after clicking in PASSIVE_MOVE phase");
    }

    // ── State query helpers ───────────────────────────────────────────────────

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
    private int getCrossedCount(String sid, String playerId, int rowIndex) {
        String rowId = api.getRowId(sid, playerId, rowIndex);
        Map<String, Object> state      = api.getGameState(sid);
        Map<String, Object> progress   = (Map<String, Object>) state.get("sheetProgress");
        Map<String, Object> playerProg = (Map<String, Object>) progress.get(playerId);
        Map<String, Object> rowStates  = (Map<String, Object>) playerProg.get("rowStates");
        Map<String, Object> rowState   = (Map<String, Object>) rowStates.get(rowId);
        if (rowState == null) return 0;
        List<?> crossed = (List<?>) rowState.get("crossedCells");
        return crossed == null ? 0 : crossed.size();
    }

    @SuppressWarnings("unchecked")
    private String turnPhase(Map<String, Object> state) {
        Map<String, Object> turn = (Map<String, Object>) state.get("turnState");
        return turn == null ? null : (String) turn.get("phase");
    }

    @SuppressWarnings("unchecked")
    private String activePlayerId(Map<String, Object> state) {
        Map<String, Object> turn = (Map<String, Object>) state.get("turnState");
        return turn == null ? null : (String) turn.get("activePlayerId");
    }
}
