package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the score screen animation.
 *
 * Row layout (standard game):
 *   Index 0 → RED    (ascending  2–12, triangular score)
 *   Index 3 → BLUE   (descending 12–2, triangular score)
 *
 * Test scores (no punishments):
 *   player0: RED 4 crosses = 10 pts,  BLUE 6 crosses = 21 pts  → total 31
 *   player1: RED 5 crosses = 15 pts,  BLUE 3 crosses =  6 pts  → total 21
 *
 * Expected animation:
 *   After RED column  → player1 leads (15 > 10)
 *   After BLUE column → player0 takes the lead (31 > 21), reorder fires
 *   Final winner      → player0
 *
 * TOTAL ANIMATION TIME ≈ 17–19 s (four colour columns + punishment + modal delay).
 * All waits use explicit timeouts sized to cover the full animation safely.
 */
public class ScoreScreenIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX  = 0;
    private static final int BLUE_ROW_INDEX = 3;

    private WebDriver driver;
    private String    sessionId;
    private List<String> playerIds;

    @BeforeEach
    void setupGame() {
        sessionId = api.createGame(2);
        playerIds = api.getPlayerIds(sessionId);

        // player0: RED 4 crosses (10 pts) + BLUE 6 crosses (21 pts) = 31 total
        api.setCrosses(sessionId, playerIds.get(0), RED_ROW_INDEX,  4);
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, 6);

        // player1: RED 5 crosses (15 pts) + BLUE 3 crosses (6 pts) = 21 total
        api.setCrosses(sessionId, playerIds.get(1), RED_ROW_INDEX,  5);
        api.setCrosses(sessionId, playerIds.get(1), BLUE_ROW_INDEX, 3);

        // End the game so /scores returns 200 instead of 409
        api.forceFinish(sessionId);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) { driver.quit(); driver = null; }
    }

    // ── Basic loading ──────────────────────────────────────────────────────────

    @Test
    void scoreScreenLoadsAndShowsAllPlayers() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        List<String> names = ScoreInteractionHelper.getVisiblePlayerNames(driver);
        assertTrue(names.contains("player0"),
                "Score screen should show player0. Visible names: " + names);
        assertTrue(names.contains("player1"),
                "Score screen should show player1. Visible names: " + names);
    }

    // ── Full animation → winner modal ──────────────────────────────────────────

    @Test
    void winnerModalAppearsWithCorrectWinner() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        // Wait for the full animation + modal to appear (up to 35 s)
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 35);

        String winner = ScoreInteractionHelper.getWinnerName(driver);
        assertEquals("player0", winner,
                "player0 has 31 pts vs player1's 21 pts and should be declared winner");
    }

    @Test
    void winnerIsAtTopOfRankingAfterAnimation() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 35);

        assertTrue(ScoreInteractionHelper.isPlayerAtRank(driver, "player0", 0),
                "player0 (31 pts) should be at rank 0 (top) after the full animation");
        assertTrue(ScoreInteractionHelper.isPlayerAtRank(driver, "player1", 1),
                "player1 (21 pts) should be at rank 1 after the full animation");
    }

    @Test
    void winnerRowReceivesGoldenWinnerClass() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 35);

        assertTrue(ScoreInteractionHelper.isPlayerRowMarkedWinner(driver, "player0"),
                "player0's row should carry the 'winner' CSS class for the golden glow");
        assertFalse(ScoreInteractionHelper.isPlayerRowMarkedWinner(driver, "player1"),
                "player1's row should NOT carry the 'winner' CSS class");
    }

    @Test
    void finalDisplayedTotalsMatchExpectedScores() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 35);

        assertEquals(31, ScoreInteractionHelper.getPlayerDisplayedTotal(driver, "player0"),
                "player0: RED 4×=10 + BLUE 6×=21 → 31 pts");
        assertEquals(21, ScoreInteractionHelper.getPlayerDisplayedTotal(driver, "player1"),
                "player1: RED 5×=15 + BLUE 3×=6 → 21 pts");
    }
}
