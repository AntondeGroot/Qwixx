package nl.adg.qwixx.e2e.helpers;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
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

    /**
     * Clicks the cell whose displayed value equals {@code displayValue} in the given row color.
     *
     * Strategy: use pure-JS DOM traversal to locate the element (avoiding stale-element
     * exceptions from Angular component host boundaries), then hand the found element back
     * to Selenium for a proper WebElement.click().  JavaScript's own element.click() is
     * less reliable for Angular event bindings in headless Chrome — Selenium's click
     * physically dispatches the event through the browser's input pipeline.
     */
    public static void clickCellByValue(WebDriver driver, String rowColor, String displayValue) {
        WebElement cell = (WebElement) ((JavascriptExecutor) driver).executeScript(
                "const section = document.querySelector('section.current-player');" +
                "if (!section) return null;" +
                "for (const span of section.querySelectorAll('.cell-value')) {" +
                "  if (span.textContent.trim() !== arguments[1]) continue;" +
                "  let cell = span.parentElement;" +
                "  while (cell && !(cell.classList && cell.classList.contains('cell'))) cell = cell.parentElement;" +
                "  if (!cell) continue;" +
                "  let row = cell.parentElement;" +
                "  while (row && !(row.classList && row.classList.contains('row'))) row = row.parentElement;" +
                "  if (!row || row.getAttribute('data-color') !== arguments[0]) continue;" +
                "  return cell;" +
                "}" +
                "return null;",
                rowColor, displayValue);
        if (cell == null)
            throw new NoSuchElementException(
                    "No " + rowColor + " cell with value '" + displayValue + "' found");
        cell.click();
    }

    /**
     * Returns true if the cell with the given display value in the given row colour
     * currently has the {@code clickable} CSS class on the current player's sheet.
     * <p>
     * The {@code clickable} class is set by the cell component whenever the cell's id
     * is in {@code clickableCellIds} — i.e. the client considers it reachable with the
     * current dice, regardless of server-side phase restrictions.
     */
    public static boolean isCellClickable(WebDriver driver, String rowColor, String displayValue) {
        Object result = ((JavascriptExecutor) driver).executeScript(
                "const section = document.querySelector('section.current-player');" +
                "if (!section) return false;" +
                "for (const span of section.querySelectorAll('.cell-value')) {" +
                "  if (span.textContent.trim() !== arguments[1]) continue;" +
                "  let cell = span.parentElement;" +
                "  while (cell && !cell.classList.contains('cell')) cell = cell.parentElement;" +
                "  if (!cell) continue;" +
                "  let row = cell.parentElement;" +
                "  while (row && !row.classList.contains('row')) row = row.parentElement;" +
                "  if (!row || row.getAttribute('data-color') !== arguments[0]) continue;" +
                "  return cell.classList.contains('clickable');" +
                "}" +
                "return false;",
                rowColor, displayValue);
        return Boolean.TRUE.equals(result);
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
//    public static void clickLockButton(WebDriver driver, String rowColor) {
//        driver.findElement(lockCellLocator(rowColor)).click();
//    }

    /** Returns true if the lock cell has the {@code lock-clickable} CSS class. */
//    public static boolean isLockButtonClickable(WebDriver driver, String rowColor) {
//        String classes = driver.findElement(lockCellLocator(rowColor)).getAttribute("class");
//        return classes != null && classes.contains("lock-clickable");
//    }

    public static boolean isLockButtonCrossed(WebDriver driver, String rowColor) {
        By spanLocator = By.xpath(
                SHEET + "//div[@data-color='" + rowColor + "' and contains(@class,'lock-cell')]"
                        + "//span[contains(@class,'cell-cross')]");
        return !driver.findElements(spanLocator).isEmpty();
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

    /** Clicks the "Yes" button in the self-close yes/no modal. */
    public static void clickModalYesButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-primary')]")).click();
    }

    /** Clicks the "No" button in the self-close yes/no modal. */
    public static void clickModalNoButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-secondary')]")).click();
    }

    // ── Pass button ───────────────────────────────────────────────────────────

    /** Clicks the passive-pass button (btn-pass-arrow) visible in PASSIVE_MOVE. */
    public static void clickPassButton(WebDriver driver) {
        driver.findElement(By.className("btn-pass-arrow")).click();
    }

    /** Waits up to {@code seconds} seconds for the passive-pass button to appear. */
    public static void waitUntilPassButtonVisible(WebDriver driver, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                .until(d -> !d.findElements(By.className("btn-pass-arrow")).isEmpty());
    }

    // ── Viewport-bounds check ──────────────────────────────────────────────────

    /**
     * Returns true if the given element's bounding rect is fully inside the browser
     * viewport (allowing 1 px rounding tolerance on each edge).
     *
     * This is the key assertion for the position:fixed / CSS-transform regression:
     * when a fixed element is inside a transformed ancestor its containing block
     * becomes the ancestor rather than the viewport, and the element can end up
     * partially or fully outside the visible area.
     */
    public static boolean isElementWithinViewport(WebDriver driver, WebElement element) {
        return (boolean) ((JavascriptExecutor) driver).executeScript(
                "const rect = arguments[0].getBoundingClientRect();" +
                "return rect.width > 0 && rect.height > 0" +
                "  && rect.top    >= -1 && rect.left   >= -1" +
                "  && rect.bottom <= window.innerHeight + 1" +
                "  && rect.right  <= window.innerWidth  + 1;",
                element);
    }

    /** Convenience: checks the `.modal-overlay` element is within the viewport. */
    public static boolean isModalOverlayWithinViewport(WebDriver driver) {
        try {
            WebElement overlay = driver.findElement(By.className("modal-overlay"));
            return isElementWithinViewport(driver, overlay);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if {@code child}'s bounding rect is fully inside {@code parent}'s bounding
     * rect (allowing 1 px rounding tolerance on each edge).
     */
    public static boolean isElementWithinElement(WebDriver driver, WebElement child, WebElement parent) {
        return (boolean) ((JavascriptExecutor) driver).executeScript(
                "const c = arguments[0].getBoundingClientRect();" +
                "const p = arguments[1].getBoundingClientRect();" +
                "return c.width > 0 && c.height > 0" +
                "  && c.top    >= p.top    - 1" +
                "  && c.left   >= p.left   - 1" +
                "  && c.bottom <= p.bottom + 1" +
                "  && c.right  <= p.right  + 1;",
                child, parent);
    }
}
