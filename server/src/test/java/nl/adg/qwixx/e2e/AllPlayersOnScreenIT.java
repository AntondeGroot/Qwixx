package nl.adg.qwixx.e2e;

import static nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper.*;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilScoreLoaded;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

/**
 * Verifies that all player rows remain fully within the viewport on the score
 * screen in portrait (mobile) orientation, both during the score animation and
 * after dismissing the winner modal via "View Scores".
 *
 * In portrait mode the score :host is rotated 90° so the score table appears
 * in landscape.  The host's DOM width = 100dvh = 844 px and its DOM height =
 * 100dvw = 390 px.  After rotation the visual viewport is 390 × 844.
 * getBoundingClientRect() returns viewport-coordinate rects (after transforms),
 * so the check works the same way on mobile as on desktop.
 *
 * For a maximum-size (5-player) game the rows-container DOM height is
 * 5 × 80 px = 400 px.  Combined with the title and score-table header, the
 * total DOM height of the content exceeds the host's 390 px DOM height, which
 * would push the bottom rows off-screen (to the left side of the rotated view)
 * unless the CSS is sized to fit or the host can accommodate all rows.
 *
 * Score setup — varied crosses force multiple reorderings during the animation
 * so the test exercises the rank transitions, not just the initial layout:
 *
 *   player0 : RED 4 × = 10 pts + BLUE 5 × = 15 pts → 25 pts  (winner)
 *   player1 : RED 5 × = 15 pts                      → 15 pts
 *   player2 : RED 3 × =  6 pts + BLUE 4 × = 10 pts  → 16 pts
 *   player3 : RED 2 × =  3 pts + BLUE 6 × = 21 pts  → 24 pts
 *   player4 : RED 1 × =  1 pt                        →  1 pt
 *
 * Reorder after RED  : player1 (15) > player0 (10) > player2 (6) > …
 * Reorder after BLUE : player0 (25) leads, player3 (24) jumps to 2nd
 */
public class AllPlayersOnScreenIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX  = 0;
    private static final int BLUE_ROW_INDEX = 3;

    private WebDriver driver;
    private String    sessionId;

    @BeforeEach
    void setupFivePlayerGame() {
        sessionId = api.createGame(5);
        List<String> pids = api.getPlayerIds(sessionId);

        api.setCrosses(sessionId, pids.get(0), RED_ROW_INDEX,  4);
        api.setCrosses(sessionId, pids.get(0), BLUE_ROW_INDEX, 5);
        api.setCrosses(sessionId, pids.get(1), RED_ROW_INDEX,  5);
        api.setCrosses(sessionId, pids.get(2), RED_ROW_INDEX,  3);
        api.setCrosses(sessionId, pids.get(2), BLUE_ROW_INDEX, 4);
        api.setCrosses(sessionId, pids.get(3), RED_ROW_INDEX,  2);
        api.setCrosses(sessionId, pids.get(3), BLUE_ROW_INDEX, 6);
        api.setCrosses(sessionId, pids.get(4), RED_ROW_INDEX,  1);

        api.forceFinish(sessionId);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) { driver.quit(); driver = null; }
    }

    /**
     * Checks all five rows are in the viewport at two points during the animation:
     *  1. immediately after the score table renders (animation has just started), and
     *  2. after all columns have been counted and the winner modal has appeared.
     */
    @Test
    void allFivePlayersAreVisibleDuringScoreAnimation() {
        // GIVEN
        driver = TestUtils.getPortraitScoreDriver(sessionId);

        // WHEN
        waitUntilScoreLoaded(driver);

        // THEN
        assertTrue(areAllPlayerRowsWithinViewport(driver),
                "All 5 player rows must be within the viewport at the start of the score animation");

        // WHEN
        waitUntilWinnerModalVisible(driver, 30);

        // THEN
        assertTrue(areAllPlayerRowsWithinViewport(driver),
                "All 5 player rows must be within the viewport once the animation has completed");
    }

    /**
     * After the winner modal is dismissed via "View Scores", the sticky action
     * bar appears at the top and the full score table is shown.  All five player
     * rows must still be within the viewport — the action bar must not push any
     * row off-screen.
     */
    @Test
    void allFivePlayersAreVisibleAfterViewScores() {
        // GIVEN
        driver = TestUtils.getPortraitScoreDriver(sessionId);

        // WHEN
        waitUntilScoreLoaded(driver);
        waitUntilWinnerModalVisible(driver, 30);
        clickViewScoresButton(driver);

        // THEN
        assertTrue(areAllPlayerRowsWithinViewport(driver),
                "All 5 player rows must be within the viewport after clicking 'View Scores'");
    }
}
