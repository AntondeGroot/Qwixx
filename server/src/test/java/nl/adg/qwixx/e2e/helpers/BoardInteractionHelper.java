package nl.adg.qwixx.e2e.helpers;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class BoardInteractionHelper {

    // All board-specific queries are scoped to the current player's own sheet
    // to avoid counting cells from other players' mini-boards in the sidebar.
    private static final String SHEET = "//section[contains(@class,'current-player')]";

    // ── Cell interaction ───────────────────────────────────────────────────────

    public static void clickCell(WebDriver driver, String rowColor, int cellIndex) {
        WebElement row = driver.findElement(
                By.xpath(SHEET + "//div[@data-color='" + rowColor + "' and contains(@class,'row')]"));
        List<WebElement> cells = row.findElements(By.className("cell"));
        if (cellIndex < cells.size()) {
            cells.get(cellIndex).click();
        } else {
            throw new IllegalArgumentException(
                    "Cell index " + cellIndex + " out of range (row has " + cells.size() + " cells)");
        }
    }

    /** Clicks the cell whose displayed value equals {@code displayValue} in the given row color. */
    public static void clickCellByValue(WebDriver driver, String rowColor, String displayValue) {
        // Match on the individual cell div's data-color so this works even when a single row
        // contains cells of different colors (future multi-color row variants).
        // Clicking the span propagates up to the cell div's click handler via normal DOM bubbling.
        WebElement span = driver.findElement(By.xpath(
                SHEET + "//div[@data-color='" + rowColor + "' and contains(@class,'cell')" +
                " and not(contains(@class,'lock'))]" +
                "/span[contains(@class,'cell-value') and normalize-space(.)='" + displayValue + "']"));
        span.click();
    }

    // ── Cell state queries ─────────────────────────────────────────────────────

    public static int getCrossedCellCount(WebDriver driver, String rowColor) {
        List<WebElement> crossed = driver.findElements(By.xpath(
                SHEET + "//div[@data-color='" + rowColor + "']" +
                "//div[contains(@class,'cell') and contains(@class,'crossed')]"));
        return crossed.size();
    }

    public static boolean isRowClosed(WebDriver driver, String rowColor) {
        try {
            WebElement row = driver.findElement(By.xpath(
                    SHEET + "//div[@data-color='" + rowColor + "' and contains(@class,'row')]"));
            String classes = row.getAttribute("class");
            return classes != null && classes.contains("closed");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lock cell ──────────────────────────────────────────────────────────────

    /** Clicks the lock cell for the given row color. */
    public static void clickLockButton(WebDriver driver, String rowColor) {
        driver.findElement(lockCellLocator(rowColor)).click();
    }

    /** Returns true if the lock cell has the {@code lock-clickable} CSS class. */
    public static boolean isLockButtonClickable(WebDriver driver, String rowColor) {
        String classes = driver.findElement(lockCellLocator(rowColor)).getAttribute("class");
        return classes != null && classes.contains("lock-clickable");
    }

    private static By lockCellLocator(String rowColor) {
        return By.xpath(
                SHEET + "//div[@data-color='" + rowColor + "' and contains(@class,'lock-cell')]");
    }

    // ── Row-closure modal ──────────────────────────────────────────────────────

    public static boolean isModalVisible(WebDriver driver) {
        try {
            return driver.findElement(By.className("modal-overlay")).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    /** Waits up to {@code seconds} seconds for the lock-intent modal to appear. */
    public static void waitUntilModalVisible(WebDriver driver, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds)).until(d -> isModalVisible(d));
    }

    /** Returns the visible text inside the modal body. */
    public static String getModalText(WebDriver driver) {
        try {
            return driver.findElement(By.className("modal-body")).getText();
        } catch (Exception e) {
            return "";
        }
    }

    /** Returns true if the modal contains a color indicator cell matching the given row color. */
    public static boolean modalHasColorCell(WebDriver driver, String rowColor) {
        try {
            String cssClass = "cell-" + rowColor.toLowerCase();
            return !driver.findElements(By.xpath(
                    "//div[contains(@class,'modal-content')]" +
                    "//div[contains(@class,'" + cssClass + "')]")).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the number of individual lock-intent request items visible in the modal. */
    public static int getModalRequestCount(WebDriver driver) {
        try {
            return driver.findElements(By.xpath(
                    "//div[contains(@class,'modal-content')]" +
                    "//div[contains(@class,'request-item')]")).size();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void clickModalConfirmButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-primary')]")).click();
    }

    public static void clickModalChangeButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-secondary')]")).click();
    }
}
