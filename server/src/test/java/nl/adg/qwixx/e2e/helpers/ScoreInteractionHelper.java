package nl.adg.qwixx.e2e.helpers;

import java.time.Duration;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ScoreInteractionHelper {

    // ── Modal ──────────────────────────────────────────────────────────────────

    public static boolean isWinnerModalVisible(WebDriver driver) {
        try {
            return !driver.findElements(By.className("winner-modal")).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Waits up to {@code seconds} seconds for the winner modal to appear. */
    public static void waitUntilWinnerModalVisible(WebDriver driver, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                .until(d -> isWinnerModalVisible(d));
    }

    /** Returns the text inside .winner-name (the declared winner). */
    public static String getWinnerName(WebDriver driver) {
        try {
            return driver.findElement(By.className("winner-name")).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ── Player rows ────────────────────────────────────────────────────────────

    /**
     * Returns the displayed total for a player (text inside .total-value of that player's row).
     *
     * Done in a single JS call so there is no stale-element window between finding the
     * player row and reading the total — Chrome 148 can replace the node between the two
     * Java calls, causing the findElement on the returned WebElement to silently return 0.
     */
    public static int getPlayerDisplayedTotal(WebDriver driver, String playerName) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                    "const row = Array.from(document.querySelectorAll('.player-row')).find(r => " +
                    "  r.querySelector('.player-name')?.textContent.trim() === arguments[0]);" +
                    "if (!row) return null;" +
                    "const tv = row.querySelector('.total-value');" +
                    "return tv ? tv.textContent.trim() : null;",
                    playerName);
            return result == null ? 0 : Integer.parseInt(result.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns true when the player's row has the CSS `top` value corresponding to {@code rank}.
     * Rank 0 = top of screen (80 * 0 = 0 px), rank 1 = 80 px, etc.
     */
    public static boolean isPlayerAtRank(WebDriver driver, String playerName, int rank) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                    "const row = Array.from(document.querySelectorAll('.player-row')).find(r => " +
                    "  r.querySelector('.player-name')?.textContent.trim() === arguments[0]);" +
                    "if (!row) return null;" +
                    "return row.getAttribute('style');",
                    playerName);
            String style = result == null ? null : result.toString();
            return style != null && style.contains("top: " + (rank * 80) + "px");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the player row carries the {@code winner} CSS class (golden glow).
     */
    public static boolean isPlayerRowMarkedWinner(WebDriver driver, String playerName) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                    "const row = Array.from(document.querySelectorAll('.player-row')).find(r => " +
                    "  r.querySelector('.player-name')?.textContent.trim() === arguments[0]);" +
                    "return row ? row.getAttribute('class') : null;",
                    playerName);
            String cls = result == null ? null : result.toString();
            return cls != null && cls.contains("winner");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Modal buttons ──────────────────────────────────────────────────────────

    /** Returns how many buttons the winner modal contains. */
    public static int getModalButtonCount(WebDriver driver) {
        try {
            return driver.findElements(By.cssSelector(".winner-modal .btn")).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clicks the "View Scores" button (btn-ghost) and waits until Angular has
     * processed the click — modal gone AND action bar present — before returning.
     * Both signals are set synchronously in the same change-detection cycle, so
     * waiting for both together is the reliable way to avoid race conditions.
     */
    public static void clickViewScoresButton(WebDriver driver) {
        // Use JS click to avoid "Node with given id does not belong to the document" —
        // Chrome 148 rejects a stale node ID when Angular re-renders between findElement
        // and the WebDriver click command. JS click is atomic: query + click in one hop.
        ((JavascriptExecutor) driver).executeScript(
                "document.querySelector('.winner-modal .btn-ghost')?.click();");
        // Wait for: modal gone, action bar present, AND rows correctly repositioned.
        // The last condition covers the afterEveryRender cycle that recalculates rowH
        // to account for the action bar's extra height — without it the assertion that
        // immediately follows this call can fire in the window between the first render
        // (action bar visible, stale rowH) and the corrected second render.
        new WebDriverWait(driver, Duration.ofSeconds(3))
                .until(d -> !isWinnerModalVisible(d)
                         && isActionBarVisible(d)
                         && areAllPlayerRowsWithinViewport(d));
    }

    /** Clicks the "New Game" button (btn-primary) inside the winner modal. */
    public static void clickNewGameButton(WebDriver driver) {
        driver.findElement(By.cssSelector(".winner-modal .btn-primary")).click();
    }

    /** Clicks the "Leave Game" button (btn-secondary) inside the winner modal. */
    public static void clickLeaveGameButton(WebDriver driver) {
        driver.findElement(By.cssSelector(".winner-modal .btn-secondary")).click();
    }

    // ── Action bar ─────────────────────────────────────────────────────────────

    /** Returns true when the sticky action bar (shown after "View Scores") is present. */
    public static boolean isActionBarVisible(WebDriver driver) {
        try {
            return !driver.findElements(By.className("action-bar")).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns how many buttons the action bar contains. */
    public static int getActionBarButtonCount(WebDriver driver) {
        try {
            return driver.findElements(By.cssSelector(".action-bar .btn")).size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Mobile rotation ────────────────────────────────────────────────────────

    /**
     * Returns true if the score screen host element has the {@code --mobile-scale}
     * CSS custom property set as an inline style, which only happens when the
     * Angular component detects portrait orientation (height > width).
     */
    public static boolean isMobileScaleApplied(WebDriver driver) {
        try {
            WebElement host = driver.findElement(By.tagName("app-score"));
            String style = host.getDomAttribute("style");
            return style != null && style.contains("--mobile-scale:");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true when the host element's CSS {@code transform} reports a
     * 90-degree rotation — specifically a matrix with a near-zero main diagonal.
     * This is the signature of {@code rotate(90deg)}.
     */
    public static boolean isRotated90Degrees(WebDriver driver) {
        try {
            WebElement host = driver.findElement(By.tagName("app-score"));
            // rotate(90deg) → matrix(0, 1, -1, 0, tx, ty)
            // getCssValue returns the computed matrix; check that the a component ≈ 0
            String transform = host.getCssValue("transform");
            if (transform == null || transform.equals("none")) return false;
            // matrix(a, b, c, d, tx, ty) — for 90deg: a≈0, b≈1, c≈-1, d≈0
            String[] parts = transform.replace("matrix(", "").replace(")", "").split(",");
            double a = Double.parseDouble(parts[0].trim());
            double b = Double.parseDouble(parts[1].trim());
            return Math.abs(a) < 0.1 && Math.abs(b - 1) < 0.1;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Viewport-bounds check ──────────────────────────────────────────────────

    /**
     * Returns true if the winner modal overlay is fully inside the effective rendering area.
     *
     * Regression guard: when position:fixed was inside a rotated CSS-transform ancestor
     * the overlay anchored to that ancestor rather than the real viewport and sat outside
     * the 390×844 mobile bounds.
     *
     * When app-root has a CSS transform (board page in portrait) Chrome reports
     * getBoundingClientRect() in app-root's LOCAL coordinate space (landscape 844×390).
     * When app-root has no transform (score screen and other pages) Chrome reports
     * normal viewport coordinates.  The check detects the actual transform state so it
     * works correctly on both pages.
     */
    public static boolean isWinnerModalWithinViewport(WebDriver driver) {
        try {
            WebElement overlay = driver.findElement(By.className("modal-overlay"));
            return (boolean) ((JavascriptExecutor) driver).executeScript(
                    "const rect = arguments[0].getBoundingClientRect();" +
                    "const appRoot = document.querySelector('app-root');" +
                    "const t = appRoot ? window.getComputedStyle(appRoot).transform : 'none';" +
                    "const isRotated = t !== 'none' && t !== '';" +
                    "const effectiveW = isRotated ? window.innerHeight : window.innerWidth;" +
                    "const effectiveH = isRotated ? window.innerWidth  : window.innerHeight;" +
                    "return rect.width > 0 && rect.height > 0" +
                    "  && rect.top    >= -1 && rect.left   >= -1" +
                    "  && rect.bottom <= effectiveH + 1" +
                    "  && rect.right  <= effectiveW + 1;",
                    overlay);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Viewport containment ───────────────────────────────────────────────────

    /**
     * Returns true when every {@code .player-row} element is fully inside the
     * effective rendering area (1 px rounding tolerance on each edge).
     *
     * When app-root has a CSS transform (board page in portrait) Chrome reports
     * getBoundingClientRect() in app-root's LOCAL coordinate space (landscape 844×390).
     * When app-root has no transform (score screen in portrait) Chrome reports normal
     * viewport coordinates (portrait 390×844).  The check detects the actual transform
     * state and sizes effective dimensions accordingly.
     */
    public static boolean areAllPlayerRowsWithinViewport(WebDriver driver) {
        return (boolean) ((JavascriptExecutor) driver).executeScript(
                "const rows = document.querySelectorAll('.player-row');" +
                "if (!rows.length) return false;" +
                "const appRoot = document.querySelector('app-root');" +
                "const t = appRoot ? window.getComputedStyle(appRoot).transform : 'none';" +
                "const isRotated = t !== 'none' && t !== '';" +
                "const effectiveW = isRotated ? window.innerHeight : window.innerWidth;" +
                "const effectiveH = isRotated ? window.innerWidth  : window.innerHeight;" +
                "for (const row of rows) {" +
                "  const r = row.getBoundingClientRect();" +
                "  if (r.width <= 0 || r.height <= 0)  return false;" +
                "  if (r.top    < -1)                   return false;" +
                "  if (r.left   < -1)                   return false;" +
                "  if (r.bottom > effectiveH + 1)       return false;" +
                "  if (r.right  > effectiveW + 1)       return false;" +
                "}" +
                "return true;");
    }

    // ── Score table ────────────────────────────────────────────────────────────

    /** Returns all visible player names from .player-name elements. */
    public static List<String> getVisiblePlayerNames(WebDriver driver) {
        return driver.findElements(By.className("player-name"))
                .stream()
                .map(e -> e.getText().trim())
                .filter(s -> !s.isBlank())
                .toList();
    }
}
