package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

import static nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper.*;
import static nl.adg.qwixx.e2e.utils.TestUtils.getPortraitScoreDriver;
import static nl.adg.qwixx.e2e.utils.TestUtils.getScoreDriver;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilScoreLoaded;
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
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        List<String> names = getVisiblePlayerNames(driver);
        assertTrue(names.contains("player0"),
                "Score screen should show player0. Visible names: " + names);
        assertTrue(names.contains("player1"),
                "Score screen should show player1. Visible names: " + names);
    }

    // ── Full animation → winner modal ──────────────────────────────────────────

    @Test
    void winnerModalAppearsWithCorrectWinner() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        // Wait for the full animation + modal to appear (up to 35 s)
        waitUntilWinnerModalVisible(driver, 10);

        String winner = getWinnerName(driver);
        assertEquals("player0", winner,
                "player0 has 31 pts vs player1's 21 pts and should be declared winner");
    }

    @Test
    void winnerIsAtTopOfRankingAfterAnimation() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        waitUntilWinnerModalVisible(driver, 10);

        assertTrue(isPlayerAtRank(driver, "player0", 0),
                "player0 (31 pts) should be at rank 0 (top) after the full animation");
        assertTrue(isPlayerAtRank(driver, "player1", 1),
                "player1 (21 pts) should be at rank 1 after the full animation");
    }

    @Test
    void winnerRowReceivesGoldenWinnerClass() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        waitUntilWinnerModalVisible(driver, 10);

        assertTrue(isPlayerRowMarkedWinner(driver, "player0"),
                "player0's row should carry the 'winner' CSS class for the golden glow");
        assertFalse(isPlayerRowMarkedWinner(driver, "player1"),
                "player1's row should NOT carry the 'winner' CSS class");
    }

    @Test
    void finalDisplayedTotalsMatchExpectedScores() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        waitUntilWinnerModalVisible(driver, 10);

        assertEquals(31, getPlayerDisplayedTotal(driver, "player0"),
                "player0: RED 4×=10 + BLUE 6×=21 → 31 pts");
        assertEquals(21, getPlayerDisplayedTotal(driver, "player1"),
                "player1: RED 5×=15 + BLUE 3×=6 → 21 pts");
    }

    // ── Winner modal: three-button layout ────────────────────────────────────

    @Test
    void winnerModalHasAllThreeButtons() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

        assertEquals(3, getModalButtonCount(driver),
                "Winner modal must have exactly 3 buttons: View Scores, New Game, Leave Game");
    }

    // ── "View Scores" button: dismiss modal, reveal action bar ───────────────

    @Test
    void viewScoresButtonDismissesModal() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

        assertTrue(isWinnerModalVisible(driver),
                "Modal must be visible before clicking View Scores");

        clickViewScoresButton(driver);

        assertFalse(isWinnerModalVisible(driver),
                "Modal must be gone after clicking View Scores");
    }

    @Test
    void viewScoresButtonRevealsActionBar() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

        assertFalse(isActionBarVisible(driver),
                "Action bar must not be visible while the modal is open");

        clickViewScoresButton(driver);

        assertTrue(isActionBarVisible(driver),
                "Action bar must appear after clicking View Scores");
    }

    @Test
    void actionBarHasTwoButtonsAfterViewScores() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);
        clickViewScoresButton(driver);

        assertEquals(2, getActionBarButtonCount(driver),
                "Action bar must have exactly 2 buttons: New Game and Leave Game");
    }

    @Test
    void scoreTableRemainsVisibleAfterViewScores() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);
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
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

       clickNewGameButton(driver);

        // Angular Router navigation is async — wait for the URL to settle
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains("/settings"));
        assertTrue(driver.getCurrentUrl().contains("/settings"),
                "New Game button must navigate to /settings. Current URL: " + driver.getCurrentUrl());
    }

    @Test
    void leaveGameButtonNavigatesToLobby() {
        driver = getScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

        clickLeaveGameButton(driver);

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().startsWith("http://localhost:4100"));
        assertTrue(driver.getCurrentUrl().startsWith("http://localhost:4100"),
                "Leave Game button must redirect to the lobby. Current URL: " + driver.getCurrentUrl());
    }

    // ── Mobile portrait layout ────────────────────────────────────────────────

    /**
     * The score screen is no longer rotated on mobile — it renders normally in portrait.
     * The player's final board is shown scaled below the score table instead.
     */
    @Test
    void scoreScreenIsNotRotatedInPortraitMode() {
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        assertFalse(isRotated90Degrees(driver),
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        String zoom = (String) ((JavascriptExecutor) driver).executeScript(
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        boolean coversViewport = (boolean) ((JavascriptExecutor) driver).executeScript(
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);

        assertTrue(isWinnerModalVisible(driver),
                "Winner modal must be visible in portrait mode");
        assertEquals(3, getModalButtonCount(driver),
                "Winner modal must still show all 3 buttons in portrait mode");

        // Verify the modal is interactive: clicking View Scores works in portrait too
        clickViewScoresButton(driver);
        assertFalse(isWinnerModalVisible(driver),
                "View Scores must dismiss the modal in portrait mode");
        assertTrue(isActionBarVisible(driver),
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);
        clickViewScoresButton(driver);

        boolean namesVisible = (boolean) ((JavascriptExecutor) driver).executeScript(
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 10);
        clickViewScoresButton(driver);

        boolean totalsVisible = (boolean) ((JavascriptExecutor) driver).executeScript(
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
        driver = getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver);

        boolean noOverflow = (boolean) ((JavascriptExecutor) driver).executeScript(
                // document.documentElement.scrollWidth > window.innerWidth means horizontal scrollbar
                "return document.documentElement.scrollWidth <= window.innerWidth + 1;");

        assertTrue(noOverflow,
                "The score screen must not produce horizontal overflow in a 390px portrait " +
                "viewport — overflow causes the white browser background to show on the right.");
    }
}
