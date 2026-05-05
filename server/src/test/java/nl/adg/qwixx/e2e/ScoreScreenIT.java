package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper;
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
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        String winner = ScoreInteractionHelper.getWinnerName(driver);
        assertEquals("player0", winner,
                "player0 has 31 pts vs player1's 21 pts and should be declared winner");
    }

    @Test
    void winnerIsAtTopOfRankingAfterAnimation() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertTrue(ScoreInteractionHelper.isPlayerAtRank(driver, "player0", 0),
                "player0 (31 pts) should be at rank 0 (top) after the full animation");
        assertTrue(ScoreInteractionHelper.isPlayerAtRank(driver, "player1", 1),
                "player1 (21 pts) should be at rank 1 after the full animation");
    }

    @Test
    void winnerRowReceivesGoldenWinnerClass() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertTrue(ScoreInteractionHelper.isPlayerRowMarkedWinner(driver, "player0"),
                "player0's row should carry the 'winner' CSS class for the golden glow");
        assertFalse(ScoreInteractionHelper.isPlayerRowMarkedWinner(driver, "player1"),
                "player1's row should NOT carry the 'winner' CSS class");
    }

    @Test
    void finalDisplayedTotalsMatchExpectedScores() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertEquals(31, ScoreInteractionHelper.getPlayerDisplayedTotal(driver, "player0"),
                "player0: RED 4×=10 + BLUE 6×=21 → 31 pts");
        assertEquals(21, ScoreInteractionHelper.getPlayerDisplayedTotal(driver, "player1"),
                "player1: RED 5×=15 + BLUE 3×=6 → 21 pts");
    }

    // ── Winner modal: three-button layout ────────────────────────────────────

    @Test
    void winnerModalHasAllThreeButtons() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertEquals(3, ScoreInteractionHelper.getModalButtonCount(driver),
                "Winner modal must have exactly 3 buttons: View Scores, New Game, Leave Game");
    }

    // ── "View Scores" button: dismiss modal, reveal action bar ───────────────

    @Test
    void viewScoresButtonDismissesModal() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertTrue(ScoreInteractionHelper.isWinnerModalVisible(driver),
                "Modal must be visible before clicking View Scores");

        ScoreInteractionHelper.clickViewScoresButton(driver);

        assertFalse(ScoreInteractionHelper.isWinnerModalVisible(driver),
                "Modal must be gone after clicking View Scores");
    }

    @Test
    void viewScoresButtonRevealsActionBar() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertFalse(ScoreInteractionHelper.isActionBarVisible(driver),
                "Action bar must not be visible while the modal is open");

        ScoreInteractionHelper.clickViewScoresButton(driver);

        assertTrue(ScoreInteractionHelper.isActionBarVisible(driver),
                "Action bar must appear after clicking View Scores");
    }

    @Test
    void actionBarHasTwoButtonsAfterViewScores() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);
        ScoreInteractionHelper.clickViewScoresButton(driver);

        assertEquals(2, ScoreInteractionHelper.getActionBarButtonCount(driver),
                "Action bar must have exactly 2 buttons: New Game and Leave Game");
    }

    @Test
    void scoreTableRemainsVisibleAfterViewScores() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);
        ScoreInteractionHelper.clickViewScoresButton(driver);

        List<String> names = ScoreInteractionHelper.getVisiblePlayerNames(driver);
        assertTrue(names.contains("player0") && names.contains("player1"),
                "Score table must still show all player names after modal is dismissed. Found: " + names);
        assertEquals(31, ScoreInteractionHelper.getPlayerDisplayedTotal(driver, "player0"),
                "player0's final total must still be visible after modal is dismissed");
    }

    // ── Navigation from modal buttons ─────────────────────────────────────────

    @Test
    void newGameButtonNavigatesToSettings() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        ScoreInteractionHelper.clickNewGameButton(driver);

        // Angular Router navigation is async — wait for the URL to settle
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/settings"));
        assertTrue(driver.getCurrentUrl().contains("/settings"),
                "New Game button must navigate to /settings. Current URL: " + driver.getCurrentUrl());
    }

    @Test
    void leaveGameButtonNavigatesToLobby() {
        driver = TestUtils.getScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        ScoreInteractionHelper.clickLeaveGameButton(driver);

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().startsWith("http://localhost:4100"));
        assertTrue(driver.getCurrentUrl().startsWith("http://localhost:4100"),
                "Leave Game button must redirect to the lobby. Current URL: " + driver.getCurrentUrl());
    }

    // ── Mobile portrait rotation ──────────────────────────────────────────────

    /**
     * Opens the score screen with a portrait viewport (390×844, like an iPhone 14).
     * Verifies that the host has a 90-degree CSS transform applied.
     */
    @Test
    void scoreScreenIsRotated90DegreesInPortraitMode() {
        driver = TestUtils.getPortraitScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        assertTrue(ScoreInteractionHelper.isRotated90Degrees(driver),
                "app-score must have rotate(90deg) transform in portrait mode "
                + "(CSS @media (orientation: portrait) must be active)");
    }

    /**
     * Regression: zoom: var(--mobile-scale) was applied to .score-screen in
     * portrait mode, shrinking the content to ~76 % of the viewport and leaving
     * the dark-blue :host background visible around it.  All other tests passed
     * because the content was technically present — just too small to fill the screen.
     *
     * This test checks that the computed zoom on .score-screen is exactly 1
     * (no scaling), which ensures the content fills the full rotated viewport.
     */
    @Test
    void scoreScreenIsNotZoomedInPortraitMode() {
        driver = TestUtils.getPortraitScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        String zoom = (String) ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
                "const el = document.querySelector('.score-screen');" +
                "if (!el) return 'not-found';" +
                "return window.getComputedStyle(el).zoom;");

        assertEquals("1", zoom,
                ".score-screen zoom must be 1 in portrait mode — zoom < 1 shrinks "
                + "the content and leaves blank background visible around it. "
                + "Got zoom=" + zoom);
    }

    /**
     * Regression: checks that the score content (.score-table) actually fills
     * most of the available space in the rotated viewport.  getBoundingClientRect()
     * after all CSS transforms gives the axis-aligned box in viewport coords;
     * the table should be at least 80 % as tall as the shorter viewport dimension.
     */
    @Test
    void scoreTableCoversEnoughOfPortraitViewport() {
        driver = TestUtils.getPortraitScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);

        boolean coversViewport = (boolean) ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
                "const table = document.querySelector('.score-table');" +
                "if (!table) return false;" +
                "const rect = table.getBoundingClientRect();" +
                // After 90° rotation the 'width' in landscape space corresponds
                // to the rect's height in viewport coordinates.
                // The table should span at least 80% of the shorter viewport side.
                "const shorter = Math.min(window.innerWidth, window.innerHeight);" +
                "return rect.width > 0 && rect.height > 0 && " +
                "       Math.max(rect.width, rect.height) >= shorter * 0.8;");

        assertTrue(coversViewport,
                "Score table must cover at least 80% of the portrait viewport — "
                + "zoom < 1 shrinks it to a fraction of the available space");
    }

    /**
     * Confirms the winner modal (position:fixed inside the rotated host) is still
     * visible and functional when the score screen is in portrait mode.
     * Because the host element creates a new fixed-positioning context via its
     * transform, the modal is correctly rotated alongside the rest of the screen.
     */
    @Test
    void winnerModalIsVisibleInPortraitMode() {
        driver = TestUtils.getPortraitScoreDriver(sessionId);
        TestUtils.waitUntilScoreLoaded(driver);
        ScoreInteractionHelper.waitUntilWinnerModalVisible(driver, 10);

        assertTrue(ScoreInteractionHelper.isWinnerModalVisible(driver),
                "Winner modal must be visible in portrait mode");
        assertEquals(3, ScoreInteractionHelper.getModalButtonCount(driver),
                "Winner modal must still show all 3 buttons in portrait mode");

        // Verify the modal is interactive: clicking View Scores works in portrait too
        ScoreInteractionHelper.clickViewScoresButton(driver);
        assertFalse(ScoreInteractionHelper.isWinnerModalVisible(driver),
                "View Scores must dismiss the modal in portrait mode");
        assertTrue(ScoreInteractionHelper.isActionBarVisible(driver),
                "Action bar must appear in portrait mode after dismissing modal");
    }
}
