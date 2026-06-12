package nl.adg.qwixx.e2e.helpers;

import java.time.Duration;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

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
     * The click is performed inside the same JS call that locates the element so that
     * there is no window for Angular to replace the DOM node between the query and the
     * click (Chrome 148 throws "Node with given id does not belong to the document" in
     * that window, which the WebDriverWait swallows and retries until timeout).
     */
    public static void clickCellByValue(WebDriver driver, String rowColor, String displayValue) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
                Object clicked = ((JavascriptExecutor) d).executeScript(
                        "const section = document.querySelector('section.current-player');" +
                        "if (!section) return false;" +
                        "for (const span of section.querySelectorAll('.cell-value')) {" +
                        "  if (span.textContent.trim() !== arguments[1]) continue;" +
                        "  let cell = span.parentElement;" +
                        "  while (cell && !(cell.classList && cell.classList.contains('cell'))) cell = cell.parentElement;" +
                        "  if (!cell) continue;" +
                        "  let row = cell.parentElement;" +
                        "  while (row && !(row.classList && row.classList.contains('row'))) row = row.parentElement;" +
                        "  if (!row || row.getAttribute('data-color') !== arguments[0]) continue;" +
                        "  cell.click(); return true;" +
                        "}" +
                        "return false;",
                        rowColor, displayValue);
                return Boolean.TRUE.equals(clicked);
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new NoSuchElementException(
                    "No " + rowColor + " cell with value '" + displayValue + "' found");
        }
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
            String classes = row.getDomAttribute("class");
            return classes != null && classes.contains("closed");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lock cell ──────────────────────────────────────────────────────────────

    // Clicks the lock cell for the given row color.
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

    /**
     * Clicks the OK/Confirm button on the row-closure notification modal.
     *
     * Waits up to 5 s for the button to appear in the DOM (the modal-overlay may render
     * before Angular has rendered the buttons inside it), then clicks via JS to avoid
     * the Chrome 148 "Node with given id does not belong to the document" stale-node error.
     */
    public static void clickModalConfirmButton(WebDriver driver) {
        new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
            Object clicked = ((JavascriptExecutor) d).executeScript(
                    "const btn = document.querySelector('.btn-notification-ok');" +
                    "if (!btn) return false;" +
                    "btn.click(); return true;");
            return Boolean.TRUE.equals(clicked);
        });
    }

    /** Clicks the Change button on the row-closure notification modal. */
    public static void clickModalChangeButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-notification-change')]")).click();
    }

    /** Returns true when the Change button on the notification modal is currently in the DOM. */
    public static boolean isModalChangeButtonVisible(WebDriver driver) {
        return !driver.findElements(By.xpath("//button[contains(@class,'btn-notification-change')]")).isEmpty();
    }

    /** Clicks the "Yes" button in the self-close yes/no modal. */
    public static void clickModalYesButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-lock-yes')]")).click();
    }

    /** Clicks the "No" button in the self-close yes/no modal. */
    public static void clickModalNoButton(WebDriver driver) {
        driver.findElement(By.xpath("//button[contains(@class,'btn-lock-no')]")).click();
    }

    // ── Pass button ───────────────────────────────────────────────────────────

    /**
     * Clicks the passive player's pass or confirm button:
     * <ul>
     *   <li>{@code btn-pass-arrow} — no pending cross, skip the turn</li>
     *   <li>{@code btn-confirm}   — pending cross present, commit it and end the turn</li>
     * </ul>
     */
    public static void clickPassButton(WebDriver driver) {
        List<WebElement> arrowBtns = driver.findElements(By.className("btn-pass-arrow"));
        if (!arrowBtns.isEmpty()) { arrowBtns.get(0).click(); return; }
        driver.findElement(By.className("btn-confirm")).click();
    }

    /** Waits up to {@code seconds} seconds for either the skip or confirm button to appear. */
    public static void waitUntilPassButtonVisible(WebDriver driver, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                .until(d -> !d.findElements(By.className("btn-pass-arrow")).isEmpty()
                         || !d.findElements(By.className("btn-confirm")).isEmpty());
    }

    // ── Viewport-bounds check ──────────────────────────────────────────────────

    /**
     * Returns true if the given element's bounding rect is fully inside the effective
     * rendering area (allowing 1 px rounding tolerance on each edge).
     *
     * When app-root has a CSS transform (the game board in portrait orientation),
     * Chrome anchors position:fixed descendants to app-root and reports
     * getBoundingClientRect() in app-root's LOCAL coordinate space (landscape
     * 844×390 rather than the visual portrait 390×844).  The check detects whether
     * app-root has a transform and swaps the effective dimensions accordingly, so it
     * works correctly on the board page (rotated), the score page (not rotated), and
     * landscape viewports.
     */
    public static boolean isElementWithinViewport(WebDriver driver, WebElement element) {
        return (boolean) ((JavascriptExecutor) driver).executeScript(
                "const rect = arguments[0].getBoundingClientRect();" +
                "const appRoot = document.querySelector('app-root');" +
                "const t = appRoot ? window.getComputedStyle(appRoot).transform : 'none';" +
                "const isRotated = t !== 'none' && t !== '';" +
                // When app-root is rotated, getBoundingClientRect() may return LOCAL coordinates
                // (landscape 844×390) or VIEWPORT coordinates (portrait 390×844) depending on
                // the Chrome version and the element's CSS (position:fixed vs. static).  Using
                // Math.max for both dimensions accepts whichever coordinate system Chrome uses.
                "const effectiveSize = isRotated" +
                "  ? Math.max(window.innerWidth, window.innerHeight)" +
                "  : null;" +
                "const effectiveW = effectiveSize ?? window.innerWidth;" +
                "const effectiveH = effectiveSize ?? window.innerHeight;" +
                "return rect.width > 0 && rect.height > 0" +
                "  && rect.top    >= -1 && rect.left   >= -1" +
                "  && rect.bottom <= effectiveH + 1" +
                "  && rect.right  <= effectiveW + 1;",
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
