package nl.adg.qwixx.e2e.helpers;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
     * Returns the player row element for the given player name, or empty if not found.
     * Uses JavaScript so the lookup is immune to component-boundary XPath quirks.
     */
    public static Optional<WebElement> findPlayerRow(WebDriver driver, String playerName) {
        try {
            WebElement row = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "return Array.from(document.querySelectorAll('.player-row')).find(r => " +
                    "  r.querySelector('.player-name')?.textContent.trim() === arguments[0]);",
                    playerName);
            return Optional.ofNullable(row);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the displayed total for a player (text inside .total-value of that player's row).
     */
    public static int getPlayerDisplayedTotal(WebDriver driver, String playerName) {
        return findPlayerRow(driver, playerName)
                .map(row -> {
                    try {
                        return Integer.parseInt(
                                row.findElement(By.className("total-value")).getText().trim());
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .orElse(0);
    }

    /**
     * Returns true when the player's row has the CSS `top` value corresponding to {@code rank}.
     * Rank 0 = top of screen (80 * 0 = 0 px), rank 1 = 80 px, etc.
     *
     * The score component sets an inline `top: Npx` via Angular's [style.top.px] binding,
     * which we read from the element's style attribute.
     */
    public static boolean isPlayerAtRank(WebDriver driver, String playerName, int rank) {
        return findPlayerRow(driver, playerName)
                .map(row -> {
                    String style = row.getAttribute("style");
                    String expected = "top: " + (rank * 80) + "px";
                    return style != null && style.contains(expected);
                })
                .orElse(false);
    }

    /**
     * Returns true if the player row carries the {@code winner} CSS class (golden glow).
     */
    public static boolean isPlayerRowMarkedWinner(WebDriver driver, String playerName) {
        return findPlayerRow(driver, playerName)
                .map(row -> {
                    String cls = row.getAttribute("class");
                    return cls != null && cls.contains("winner");
                })
                .orElse(false);
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
