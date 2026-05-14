package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.util.List;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.getCrossedCellCount;
import static nl.adg.qwixx.e2e.utils.TestUtils.getDriver;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilBoardLoaded;
import static org.junit.jupiter.api.Assertions.*;

public class GameBoardE2ETest extends BaseIntegrationTest {

    private WebDriver driver;
    private String sessionId;
    private List<String> playerIds;

    @BeforeEach
    void setUp() {
        sessionId = api.createGame(2);
        playerIds = api.getPlayerIds(sessionId);

        api.setCrosses(sessionId, playerIds.get(0), 3, 5);
        api.setCrosses(sessionId, playerIds.get(1), 3, 5);

        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver = getDriver(sessionId, playerIds.get(0));
        waitUntilBoardLoaded(driver);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
    }

    @Test
    void boardLoads() {
        assertFalse(driver.findElements(By.className("row")).isEmpty(),
                "Game board should display at least one row");
    }

    @Test
    void player0HasFiveCrossesInBlueRow() {
        int crossed = getCrossedCellCount(driver, "BLUE");
        assertEquals(5, crossed, "Player 0 should see exactly 5 crossed cells in BLUE row");
    }

//    @Test
//    void lockNotClickableWithFiveCrossesOnly() {
//        assertFalse(BoardInteractionHelper.isLockButtonClickable(driver, "BLUE"),
//                "Lock requires cell '12' to also be crossed — 5 crosses alone are not enough");
//    }

    @Test
    void lockButtonIsVisible() {
        assertFalse(driver.findElements(By.className("lock-cell")).isEmpty(),
                "Lock cells should be visible for all rows");
    }
}
