package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper.*;
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
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ScoreScreenIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX  = 0;
    private static final int BLUE_ROW_INDEX = 3;

    /** Desktop (1280×800) driver for standard score-screen tests. */
    private WebDriver driver;
    /** Portrait (390×844) driver for mobile layout tests. */
    private WebDriver portraitDriver;
    private String    sessionId;

    @BeforeAll
    void openDrivers() {
        driver         = TestUtils.createDriver();
        portraitDriver = TestUtils.createPortraitDriver();
    }

    @AfterAll
    void closeDrivers() {
        if (driver         != null) { driver.quit();         driver         = null; }
        if (portraitDriver != null) { portraitDriver.quit(); portraitDriver = null; }
    }

    @BeforeEach
    void setupGame() {
        driver        = TestUtils.ensureAlive(driver);
        portraitDriver = TestUtils.ensureAlivePortrait(portraitDriver);
        sessionId = api.createGame(2);

        // Look up by name — shuffle may reorder the player list after game start.
        String player0Id = api.getPlayerIdByName(sessionId, "player0");
        String player1Id = api.getPlayerIdByName(sessionId, "player1");

        // player0: RED 4 crosses (10 pts) + BLUE 6 crosses (21 pts) = 31 total
        api.setCrosses(sessionId, player0Id, RED_ROW_INDEX,  4);
        api.setCrosses(sessionId, player0Id, BLUE_ROW_INDEX, 6);

        // player1: RED 5 crosses (15 pts) + BLUE 3 crosses (6 pts) = 21 total
        api.setCrosses(sessionId, player1Id, RED_ROW_INDEX,  5);
        api.setCrosses(sessionId, player1Id, BLUE_ROW_INDEX, 3);

        // End the game so /scores returns 200 instead of 409
        api.forceFinish(sessionId);
    }

    // ── Basic loading ──────────────────────────────────────────────────────────

    @Test
    void scoreScreenLoadsAndShowsAllPlayers() {
        TestUtils.navigateToScore(driver, sessionId);

        List<String> names = getVisiblePlayerNames(driver);
        assertTrue(names.contains("player0"),
                "Score screen should show player0. Visible names: " + names);
        assertTrue(names.contains("player1"),
                "Score screen should show player1. Visible names: " + names);
    }

    // ── Full animation → winner modal ──────────────────────────────────────────

    @Test
    void winnerModalAppearsWithCorrectWinner() {
        TestUtils.navigateToScore(driver, sessionId);

        // Wait for the full animation + modal to appear (up to 35 s)
        waitUntilWinnerModalVisible(driver, 30);

        String winner = getWinnerName(driver);
        assertEquals("player0", winner,
                "player0 has 31 pts vs player1's 21 pts and should be declared winner");
    }

    @Test
    void winnerIsAtTopOfRankingAfterAnimation() {
        TestUtils.navigateToScore(driver, sessionId);

        waitUntilWinnerModalVisible(driver, 30);

        assertTrue(isPlayerAtRank(driver, "player0", 0),
                "player0 (31 pts) should be at rank 0 (top) after the full animation");
        assertTrue(isPlayerAtRank(driver, "player1", 1),
                "player1 (21 pts) should be at rank 1 after the full animation");
    }

    @Test
    void winnerRowReceivesGoldenWinnerClass() {
        TestUtils.navigateToScore(driver, sessionId);

        waitUntilWinnerModalVisible(driver, 30);

        assertTrue(isPlayerRowMarkedWinner(driver, "player0"),
                "player0's row should carry the 'winner' CSS class for the golden glow");
        assertFalse(isPlayerRowMarkedWinner(driver, "player1"),
                "player1's row should NOT carry the 'winner' CSS class");
    }

    @Test
    void finalDisplayedTotalsMatchExpectedScores() {
        TestUtils.navigateToScore(driver, sessionId);

        waitUntilWinnerModalVisible(driver, 30);

        assertEquals(31, getPlayerDisplayedTotal(driver, "player0"),
                "player0: RED 4×=10 + BLUE 6×=21 → 31 pts");
        assertEquals(21, getPlayerDisplayedTotal(driver, "player1"),
                "player1: RED 5×=15 + BLUE 3×=6 → 21 pts");
    }

    // ── Winner modal: three-button layout ────────────────────────────────────

    @Test
    void winnerModalHasAllThreeButtons() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);

        assertEquals(3, getModalButtonCount(driver),
                "Winner modal must have exactly 3 buttons: View Scores, New Game, Leave Game");
    }

    // ── "View Scores" button: dismiss modal, reveal action bar ───────────────

    @Test
    void viewScoresButtonDismissesModal() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);

        assertTrue(isWinnerModalVisible(driver),
                "Modal must be visible before clicking View Scores");

        clickViewScoresButton(driver);

        assertFalse(isWinnerModalVisible(driver),
                "Modal must be gone after clicking View Scores");
    }

    @Test
    void viewScoresButtonRevealsActionBar() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);

        assertFalse(isActionBarVisible(driver),
                "Action bar must not be visible while the modal is open");

        clickViewScoresButton(driver);

        assertTrue(isActionBarVisible(driver),
                "Action bar must appear after clicking View Scores");
    }

    @Test
    void actionBarHasTwoButtonsAfterViewScores() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);
        clickViewScoresButton(driver);

        assertEquals(3, getActionBarButtonCount(driver),
                "Action bar must have exactly 3 buttons: New Game, Leave Game and Share Scores");
    }

    @Test
    void scoreTableRemainsVisibleAfterViewScores() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);
        clickViewScoresButton(driver);

        List<String> names = getVisiblePlayerNames(driver);
        assertTrue(names.contains("player0") && names.contains("player1"),
                "Score table must still show all player names after modal is dismissed. Found: " + names);
        assertEquals(31, getPlayerDisplayedTotal(driver, "player0"),
                "player0's final total must still be visible after modal is dismissed");
    }

    // ── Navigation from modal buttons ─────────────────────────────────────────

    @Test
    void newGameButtonNavigatesToSettings() {
        TestUtils.navigateToScore(driver, sessionId);
        waitUntilWinnerModalVisible(driver, 30);

       clickNewGameButton(driver);

        // Angular Router navigation is async — wait for the URL to settle
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/settings"));
        assertTrue(driver.getCurrentUrl().contains("/settings"),
                "New Game button must navigate to /settings. Current URL: " + driver.getCurrentUrl());
    }

    @Test
    void leaveGameButtonNavigatesToLobby() {
        // Use a dedicated driver that is discarded after this test. Clicking Leave Game
        // redirects to http://localhost:4100 (the lobby), which is outside the test
        // server domain and results in a connection-refused page. Chrome 148 can lose
        // its DevTools connection after that navigation, which would poison the shared
        // class-level driver and cause NoSuchSession in subsequent tests.
        WebDriver tempDriver = TestUtils.createDriver();
        try {
            TestUtils.navigateToScore(tempDriver, sessionId);
            waitUntilWinnerModalVisible(tempDriver, 30);
            clickLeaveGameButton(tempDriver);
            new WebDriverWait(tempDriver, Duration.ofSeconds(10))
                    .until(d -> d.getCurrentUrl().startsWith("http://localhost:4100"));
            assertTrue(tempDriver.getCurrentUrl().startsWith("http://localhost:4100"),
                    "Leave Game button must redirect to the lobby. Current URL: " + tempDriver.getCurrentUrl());
        } finally {
            tempDriver.quit();
        }
    }

    // ── Mobile portrait layout ────────────────────────────────────────────────

    /**
     * The score screen is no longer rotated on mobile — it renders normally in portrait.
     * The player's final board is shown scaled below the score table instead.
     */
    @Test
    void scoreScreenIsNotRotatedInPortraitMode() {
        TestUtils.navigateToScore(portraitDriver, sessionId);

        assertFalse(isRotated90Degrees(portraitDriver),
                "app-score must NOT have rotate(90deg) in portrait mode — "
                + "the score screen is now a normal portrait layout");
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
        TestUtils.navigateToScore(portraitDriver, sessionId);

        String zoom = (String) ((JavascriptExecutor) portraitDriver).executeScript(
                "const el = document.querySelector('.score-screen');" +
                "if (!el) return 'not-found';" +
                "return window.getComputedStyle(el).zoom;");

        assertEquals("1", zoom,
                ".score-screen zoom must be 1 in portrait mode — zoom < 1 shrinks "
                + "the content and leaves blank background visible around it. "
                + "Got zoom=" + zoom);
    }

    /**
     * Checks that the score table fills most of the viewport width in portrait mode.
     * The table is a normal block (no rotation), so its rect.width should span
     * at least 60% of the viewport width.
     */
    @Test
    void scoreTableCoversEnoughOfPortraitViewport() {
        TestUtils.navigateToScore(portraitDriver, sessionId);

        boolean coversViewport = (boolean) ((JavascriptExecutor) portraitDriver).executeScript(
                "const table = document.querySelector('.score-table');" +
                "if (!table) return false;" +
                "const rect = table.getBoundingClientRect();" +
                "return rect.width > 0 && rect.height > 0 && " +
                "       rect.width >= window.innerWidth * 0.6;");

        assertTrue(coversViewport,
                "Score table must span at least 60% of the portrait viewport width");
    }

    /**
     * Confirms the winner modal (position:fixed) is visible and functional
     * when the score screen is in portrait mode.
     */
    @Test
    void winnerModalIsVisibleInPortraitMode() {
        TestUtils.navigateToScore(portraitDriver, sessionId);
        waitUntilWinnerModalVisible(portraitDriver, 30);

        assertTrue(isWinnerModalVisible(portraitDriver),
                "Winner modal must be visible in portrait mode");
        assertEquals(3, getModalButtonCount(portraitDriver),
                "Winner modal must still show all 3 buttons in portrait mode");

        // Verify the modal is interactive: clicking View Scores works in portrait too
        clickViewScoresButton(portraitDriver);
        assertFalse(isWinnerModalVisible(portraitDriver),
                "View Scores must dismiss the modal in portrait mode");
        assertTrue(isActionBarVisible(portraitDriver),
                "Action bar must appear in portrait mode after dismissing modal");
    }

    /**
     * Player names must be readable in a portrait (390 × 844) mobile viewport.
     * The score table columns use fixed widths that exceed 390 px at desktop sizes,
     * collapsing the name cell to 0 and making names invisible. The portrait layout
     * must reduce column widths so the name cell has room for at least the first
     * few characters of each player's name.
     */
    @Test
    void scoreScreenPortrait_playerNamesAreVisible() {
        TestUtils.navigateToScore(portraitDriver, sessionId);
        waitUntilWinnerModalVisible(portraitDriver, 30);
        clickViewScoresButton(portraitDriver);

        boolean namesVisible = (boolean) ((JavascriptExecutor) portraitDriver).executeScript(
                "const names = document.querySelectorAll('.player-name');" +
                "if (!names.length) return false;" +
                "for (const el of names) {" +
                "  const r = el.getBoundingClientRect();" +
                // Name must have positive rendered width AND the text must not be empty
                "  if (r.width < 20 || el.textContent.trim() === '') return false;" +
                "}" +
                "return true;");

        assertTrue(namesVisible,
                "Every .player-name must have at least 20px rendered width in a 390px portrait " +
                "viewport — currently the fixed bucket-cell widths push the name cell to 0.");
    }

    /**
     * The total-value column must be fully within the viewport in portrait mode.
     * Previously the row content was wider than 390 px, pushing the rightmost
     * column (the total) off-screen so the user had to scroll to see scores.
     */
    @Test
    void scoreScreenPortrait_totalColumnIsWithinViewport() {
        TestUtils.navigateToScore(portraitDriver, sessionId);
        waitUntilWinnerModalVisible(portraitDriver, 30);
        clickViewScoresButton(portraitDriver);

        boolean totalsVisible = (boolean) ((JavascriptExecutor) portraitDriver).executeScript(
                "const totals = document.querySelectorAll('.total-value');" +
                "if (!totals.length) return false;" +
                "for (const el of totals) {" +
                "  const r = el.getBoundingClientRect();" +
                "  if (r.right > window.innerWidth + 1) return false;" +
                "  if (r.width <= 0) return false;" +
                "}" +
                "return true;");

        assertTrue(totalsVisible,
                "All .total-value cells must fit within the 390px viewport width — " +
                "currently they are pushed off-screen by wide bucket and name columns.");
    }

    /**
     * The score screen must not produce horizontal overflow in portrait mode.
     * When content is wider than the viewport the browser makes the page scrollable
     * and shows white background (the browser default) to the right of the host.
     */
    @Test
    void scoreScreenPortrait_noHorizontalOverflow() {
        TestUtils.navigateToScore(portraitDriver, sessionId);

        boolean noOverflow = (boolean) ((JavascriptExecutor) portraitDriver).executeScript(
                // document.documentElement.scrollWidth > window.innerWidth means horizontal scrollbar
                "return document.documentElement.scrollWidth <= window.innerWidth + 1;");

        assertTrue(noOverflow,
                "The score screen must not produce horizontal overflow in a 390px portrait " +
                "viewport — overflow causes the white browser background to show on the right.");
    }
}
