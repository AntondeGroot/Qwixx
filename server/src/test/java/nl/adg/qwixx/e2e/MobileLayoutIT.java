package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static nl.adg.qwixx.e2e.helpers.BoardInteractionHelper.*;
import static nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper.*;
import static nl.adg.qwixx.e2e.utils.TestUtils.getPortraitDriver;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilBoardLoaded;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilScoreLoaded;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mobile-layout integration tests.
 *
 * Both the lock-intent modal and the score winner modal use {@code position: fixed}
 * for their overlay.  In portrait orientation the board/score host element has
 * {@code transform: rotate(90deg)}, which makes any descendant {@code position: fixed}
 * anchor to the rotated element rather than the real viewport — the overlay ends up
 * clipped or positioned off-screen and the user cannot interact with it.
 *
 * These tests run Chrome at 390×844 (iPhone 14 Pro portrait) to exercise the same
 * CSS path that fires on real mobile devices, and verify that:
 *   1. The modal overlay is visible.
 *   2. The overlay sits fully inside the viewport (bounding-rect check).
 *   3. The buttons inside the modal are actually clickable.
 *   4. Clicking a button produces the expected game-state change.
 *
 * Window size: 390 × 844  (portrait — triggers @media (orientation: portrait))
 */
public class MobileLayoutIT extends BaseIntegrationTest {

    private static final int BLUE_ROW_INDEX    = 3;
    private static final int BLUE_ROW_ALL_CELLS = 11;
    private static final int RED_ROW_INDEX     = 0;

    private WebDriver driver0;
    private WebDriver driver1;
    private String sessionId;
    private List<String> playerIds;

    @BeforeEach
    void createGame() {
        sessionId = api.createGame(2);
        playerIds = api.getPlayerIds(sessionId);
    }

    @AfterEach
    void tearDown() {
        if (driver0 != null) { driver0.quit(); driver0 = null; }
        if (driver1 != null) { driver1.quit(); driver1 = null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lock-intent modal on mobile
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The score-screen host is rotated 90 ° in portrait mode.
     * Verifies that the rotation CSS is actually active at 390×844.
     */
    @Test
    void boardHostIsRotated90DegreesInPortraitViewport() {
        api.roll(sessionId, playerIds.get(0));
        driver0 = getPortraitDriver(sessionId, playerIds.get(0));
        waitUntilBoardLoaded(driver0);

        assertTrue(isBoardHostRotated(driver0),
                "Board :host must have transform: rotate(90deg) in portrait mode");
    }

    /**
     * The lock-intent modal must appear in the passive player's browser when
     * viewed in portrait (mobile) orientation.
     */
    @Test
    void lockIntentModalAppearsInPortraitViewport() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = getPortraitDriver(sessionId, playerIds.get(1));
        waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);

        assertTrue(isModalVisible(driver1),
                "Lock-intent modal must be visible in a portrait (mobile) viewport");
    }

    /**
     * The modal overlay must be fully within the real viewport in portrait mode.
     *
     * Regression guard: when position:fixed was inside the board's rotated :host,
     * getBoundingClientRect() showed the overlay outside the 390×844 viewport bounds.
     */
    @Test
    void lockIntentModalOverlayIsWithinViewportBoundsOnMobile() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = getPortraitDriver(sessionId, playerIds.get(1));
        waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);

        assertTrue(isModalOverlayWithinViewport(driver1),
                "Modal overlay must be fully within the viewport on mobile — " +
                "position:fixed inside a CSS transform breaks this");
    }

    /**
     * The confirm button inside the lock-intent modal must be interactable
     * (clickable) in portrait orientation — if the overlay is off-screen or
     * clipped, Selenium's click() will throw ElementNotInteractableException.
     */
    @Test
    void lockIntentModalConfirmButtonIsClickableOnMobile() {
        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
        api.roll(sessionId, playerIds.get(0));
        api.setDice(sessionId, 1, 1);

        driver1 = getPortraitDriver(sessionId, playerIds.get(1));
        waitUntilBoardLoaded(driver1);

        String blueRowId = api.getRowId(sessionId, playerIds.get(0), BLUE_ROW_INDEX);
        api.declareLockIntent(sessionId, playerIds.get(0), blueRowId);

        waitUntilModalVisible(driver1, 8);

        // This click must NOT throw — if the button is off-screen it will
        assertDoesNotThrow(() -> clickModalConfirmButton(driver1),
                "Confirm button must be interactable in a portrait (mobile) viewport");
    }

    /**
     * Full lock flow on mobile: player0 declares intent (desktop), player1 sees
     * modal in portrait, clicks Confirm, row closes for both players.
     */
//    @Test
//    void fullLockFlow_mobilePassiveConfirms_rowCloses() {
//        api.setCrosses(sessionId, playerIds.get(0), BLUE_ROW_INDEX, BLUE_ROW_ALL_CELLS);
//        api.roll(sessionId, playerIds.get(0));
//        api.setDice(sessionId, 1, 1);
//
//        // Player 0 on desktop, player 1 on mobile portrait
//        driver0 = TestUtils.getDriver(sessionId, playerIds.get(0));
//        driver1 = TestUtils.getPortraitDriver(sessionId, playerIds.get(1));
//        TestUtils.waitUntilBoardLoaded(driver0);
//        TestUtils.waitUntilBoardLoaded(driver1);
//
//        // Active player declares lock via the desktop browser
//        BoardInteractionHelper.clickLockButton(driver0, "BLUE");
//
//        // Passive player on mobile must see the modal and be able to confirm it
//        BoardInteractionHelper.waitUntilModalVisible(driver1, 8);
//        assertTrue(BoardInteractionHelper.isModalOverlayWithinViewport(driver1),
//                "Modal must be within viewport on mobile before confirming");
//
//        BoardInteractionHelper.clickModalConfirmButton(driver1);
//
//        // Both players must see the BLUE row close
//        new WebDriverWait(driver0, Duration.ofSeconds(8))
//                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
//        new WebDriverWait(driver1, Duration.ofSeconds(8))
//                .until(d -> BoardInteractionHelper.isRowClosed(d, "BLUE"));
//
//        assertTrue(BoardInteractionHelper.isRowClosed(driver0, "BLUE"),
//                "BLUE row must be closed in player0's desktop browser");
//        assertTrue(BoardInteractionHelper.isRowClosed(driver1, "BLUE"),
//                "BLUE row must be closed in player1's mobile browser");
//        assertFalse(BoardInteractionHelper.isModalVisible(driver1),
//                "Modal must disappear after confirming on mobile");
//    }

    // ─────────────────────────────────────────────────────────────────────────
    // Score screen winner modal on mobile
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The score screen :host must NOT be rotated in portrait — the layout is now
     * normal portrait; the player's final board is shown scaled at the bottom.
     */
    @Test
    void scoreHostIsNotRotatedInPortraitViewport() {
        api.setCrosses(sessionId, playerIds.get(0), RED_ROW_INDEX, 4);
        api.forceFinish(sessionId);

        driver0 = TestUtils.getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver0);

        assertFalse(isRotated90Degrees(driver0),
                "Score screen :host must NOT be rotated in portrait mode");
    }

    /**
     * The winner modal must appear in portrait orientation.
     */
    @Test
    void winnerModalAppearsInPortraitViewport() {
        api.setCrosses(sessionId, playerIds.get(0), RED_ROW_INDEX, 4);
        api.forceFinish(sessionId);

        driver0 = TestUtils.getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver0);

        waitUntilWinnerModalVisible(driver0, 25);

        assertTrue(isWinnerModalVisible(driver0),
                "Winner modal must appear on the score screen in portrait (mobile) viewport");
    }

    /**
     * The winner modal overlay must be fully within the viewport on mobile.
     *
     * Regression guard: the score screen's .modal-overlay used position:fixed
     * inside the rotated :host — the same bug that affected the lock-intent modal.
     */
    @Test
    void winnerModalOverlayIsWithinViewportBoundsOnMobile() {
        api.setCrosses(sessionId, playerIds.get(0), RED_ROW_INDEX, 4);
        api.forceFinish(sessionId);

        driver0 = TestUtils.getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver0);

        waitUntilWinnerModalVisible(driver0, 25);

        assertTrue(isWinnerModalWithinViewport(driver0),
                "Winner modal overlay must be fully within the viewport on mobile");
    }

    /**
     * The "View Scores" button inside the winner modal must be interactable in
     * portrait orientation. If position:fixed was broken, the button would be
     * off-screen and Selenium's click() would throw.
     */
    @Test
    void winnerModalViewScoresButtonIsClickableOnMobile() {
        api.setCrosses(sessionId, playerIds.get(0), RED_ROW_INDEX, 4);
        api.forceFinish(sessionId);

        driver0 = TestUtils.getPortraitScoreDriver(sessionId);
        waitUntilScoreLoaded(driver0);

        waitUntilWinnerModalVisible(driver0, 25);

        // This must NOT throw — if the button is off-screen Selenium throws
        assertDoesNotThrow(() -> clickViewScoresButton(driver0),
                "View Scores button must be clickable in portrait (mobile) viewport");

        // After clicking, modal must dismiss and action bar must appear
        assertFalse(isWinnerModalVisible(driver0),
                "Winner modal must close after clicking View Scores on mobile");
        assertTrue(isActionBarVisible(driver0),
                "Action bar must appear after dismissing the winner modal on mobile");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Longo sheet elements on mobile
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the Longo-specific sheet elements are all rendered and visible
     * in a portrait (mobile) viewport:
     *
     *  - Right column as a whole (layout container for the sheet and HUD)
     *  - Bonus track with bonus chips (the "extra numbers" unique to Longo)
     *  - Lock icon for every color row
     *  - Punishment boxes (all four)
     *
     * Uses a single-player Longo game so no dice roll is needed — the bonus numbers
     * are assigned at game creation and are present in the very first game state.
     */
    @Test
    void longoSheetElementsAreRenderedOnMobile() {
        String sid = api.createGame(1, Map.of("base", "LONGO"));
        String pid = api.getPlayerIds(sid).get(0);

        driver0 = getPortraitDriver(sid, pid);
        waitUntilBoardLoaded(driver0);

        // Force --mobile-scale so all elements fit within the host's 844px clip boundary.
        // The component's _scaleEffect computes this asynchronously after each render;
        // we apply it directly here to make the visibility assertions deterministic.
        ((org.openqa.selenium.JavascriptExecutor) driver0).executeScript(
                "document.querySelector('app-board').style.setProperty('--mobile-scale','0.6')");
        TestUtils.wait(150); // let the browser apply the zoom recalculation

        // Right column must be present and have a non-zero height.
        WebElement rightCol = driver0.findElement(By.className("right-column"));
        assertTrue(rightCol.isDisplayed(),
                "Right column must be displayed on mobile");
        assertTrue(rightCol.getSize().height > 0,
                "Right column must have positive height on mobile");

        // Longo-specific: bonus track with at least one visible bonus chip.
        WebElement bonusTrack = driver0.findElement(By.className("bonus-track"));
        assertTrue(bonusTrack.isDisplayed(),
                "Bonus track must be displayed on mobile for a Longo game");
        List<WebElement> bonusChips = driver0.findElements(By.className("bonus-chip"));
        assertFalse(bonusChips.isEmpty(),
                "Bonus chips must be present in the bonus track for a Longo game");
        assertTrue(bonusChips.stream().allMatch(WebElement::isDisplayed),
                "Every bonus chip must be displayed on mobile");

        // Lock icon: every color row must have a rendered, visible lock cell.
        for (String color : List.of("RED", "YELLOW", "GREEN", "BLUE")) {
            WebElement lockCell = driver0.findElement(By.xpath(
                    "//section[contains(@class,'current-player')]" +
                    "//div[@data-color='" + color + "' and contains(@class,'lock-cell')]"));
            assertTrue(lockCell.isDisplayed(),
                    color + " lock cell must be displayed on mobile");
        }

        // Punishment track: all four boxes must be rendered.
        List<WebElement> punishBoxes = driver0.findElements(By.className("punishment-box"));
        assertEquals(4, punishBoxes.size(),
                "Must have exactly 4 punishment boxes");
        assertTrue(punishBoxes.stream().allMatch(WebElement::isDisplayed),
                "Every punishment box must be displayed on mobile");
    }

    /**
     * Verifies that every Longo-specific element (rows, bonus track, punishment track)
     * is fully contained within the {@code .current-player} section — i.e. nothing
     * overflows the sheet boundary.
     */
    @Test
    void longoVariantIsFullyWithinCurrentPlayerElement() {
        String sid = api.createGame(1, Map.of("base", "LONGO"));
        String pid = api.getPlayerIds(sid).get(0);

        driver0 = getPortraitDriver(sid, pid);
        waitUntilBoardLoaded(driver0);

        ((org.openqa.selenium.JavascriptExecutor) driver0).executeScript(
                "document.querySelector('app-board').style.setProperty('--mobile-scale','0.6')");
        TestUtils.wait(150);

        WebElement currentPlayer = driver0.findElement(By.cssSelector("section.current-player"));

        for (String color : List.of("RED", "YELLOW", "GREEN", "BLUE")) {
            WebElement row = driver0.findElement(By.xpath(
                    "//section[contains(@class,'current-player')]" +
                    "//div[@data-color='" + color + "' and contains(@class,'row')]"));
            assertTrue(isElementWithinElement(driver0, row, currentPlayer),
                    color + " row must be fully within the current-player section");
        }

        WebElement bonusTrack = driver0.findElement(By.className("bonus-track"));
        assertTrue(isElementWithinElement(driver0, bonusTrack, currentPlayer),
                "Bonus track must be fully within the current-player section");

        WebElement punishmentTrack = driver0.findElement(By.className("punishment-track"));
        assertTrue(isElementWithinElement(driver0, punishmentTrack, currentPlayer),
                "Punishment track must be fully within the current-player section");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Longo yes/no self-close modal
    //
    // BLUE descending (index 3) in LONGO: 16, 15, …, 4, 3, 2
    //   second-to-last closing cell = "3" (pos 13)
    //   last closing cell           = "2" (pos 14)
    // Setup: 6 normal crosses (pos 0-5, values 16-11), dice white1=1+white2=2=3
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void longoSecondToLastCellShowsYesNoModal() {
        String sid = api.createGame(2, Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2); // white+white = 3 → "3" reachable

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        waitUntilBoardLoaded(driver0);

        assertFalse(isModalVisible(driver0),
                "No modal should be visible before clicking the second-to-last cell");

        clickCellByValue(driver0, "BLUE", "3");

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> isModalVisible(d));

        assertTrue(isModalVisible(driver0),
                "Yes/No modal must appear after clicking the second-to-last closing cell in Longo");
    }

    @Test
    void longoYesOnSelfCloseModalCrossesCell() {
        String sid = api.createGame(2, Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        waitUntilBoardLoaded(driver0);

        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);
        clickModalYesButton(driver0);

        // Modal must close and "3" must be crossed (7 crosses total).
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));

        assertFalse(isModalVisible(driver0),
                "Modal must close after clicking Yes");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "BLUE") >= 7);
        assertTrue(getCrossedCellCount(driver0, "BLUE") >= 7,
                "BLUE row must have at least 7 crosses after confirming with Yes");
    }

    @Test
    void longoNoOnSelfCloseModalStillCrossesCell() {
        // Clicking No on the second-to-last Longo modal declines the lock intent,
        // but the cell itself must still be crossed — the question is only about the lock.
        String sid = api.createGame(2, Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        waitUntilBoardLoaded(driver0);

        int crossesBefore = getCrossedCellCount(driver0, "BLUE");

        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);
        clickModalNoButton(driver0);

        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> !isModalVisible(d));

        assertFalse(isModalVisible(driver0),
                "Modal must close after clicking No");
        new WebDriverWait(driver0, Duration.ofSeconds(5))
                .until(d -> getCrossedCellCount(d, "BLUE") >= crossesBefore + 1);
        assertEquals(crossesBefore + 1, getCrossedCellCount(driver0, "BLUE"),
                "Clicking No must still cross the cell — only the lock intent is declined, not the cross");
        assertFalse(isLockButtonCrossed(driver0, "BLUE"),
                "Lock cross must NOT appear after clicking No (lock intent declined)");
    }

    /**
     * Regression test for the translation bug where the yes/no modal showed raw
     * i18n keys ("rowClosure.selfClose", "rowClosure.yes", "rowClosure.no") instead
     * of translated text.
     *
     * Root cause: TranslationService called use() for all 5 languages concurrently,
     * causing ngx-translate's loadingTranslations observable to be overwritten on
     * each call.  When a non-English language loaded first, pending dropped to false
     * before English was in the store, so the translate pipe cached the key string.
     */
    @Test
    void longoSelfCloseModalShowsTranslatedTextNotRawKeys() {
        String sid = api.createGame(2, Map.of("base", "LONGO"));
        List<String> pids = api.getPlayerIds(sid);

        api.setCrosses(sid, pids.get(0), BLUE_ROW_INDEX, 6);
        api.roll(sid, pids.get(0));
        api.setDice(sid, 1, 2);

        driver0 = TestUtils.getDriver(sid, pids.get(0));
        waitUntilBoardLoaded(driver0);

        clickCellByValue(driver0, "BLUE", "3");
        waitUntilModalVisible(driver0, 5);

        String bodyText = getModalText(driver0);
        String yesText  = driver0.findElement(By.xpath("//button[contains(@class,'btn-primary')]")).getText();
        String noText   = driver0.findElement(By.xpath("//button[contains(@class,'btn-secondary')]")).getText();

        assertFalse(bodyText.contains("rowClosure."),
                "Modal question must not show a raw translation key — got: " + bodyText);
        assertFalse(bodyText.isBlank(),
                "Modal question must not be empty");
        assertFalse(yesText.contains("rowClosure."),
                "Yes button must not show a raw translation key — got: " + yesText);
        assertFalse(yesText.isBlank(),
                "Yes button text must not be empty");
        assertFalse(noText.contains("rowClosure."),
                "No button must not show a raw translation key — got: " + noText);
        assertFalse(noText.isBlank(),
                "No button text must not be empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when the board component's host element has a 90-degree CSS
     * transform applied — the signature that portrait-mode rotation is active.
     * rotate(90deg) produces matrix(0, 1, -1, 0, tx, ty): a≈0, b≈1.
     */
    private boolean isBoardHostRotated(WebDriver driver) {
        try {
            org.openqa.selenium.WebElement host =
                    driver.findElement(org.openqa.selenium.By.tagName("app-board"));
            String transform = host.getCssValue("transform");
            if (transform == null || transform.equals("none")) return false;
            String[] parts = transform.replace("matrix(", "").replace(")", "").split(",");
            double a = Double.parseDouble(parts[0].trim());
            double b = Double.parseDouble(parts[1].trim());
            return Math.abs(a) < 0.1 && Math.abs(b - 1.0) < 0.1;
        } catch (Exception e) {
            return false;
        }
    }
}
