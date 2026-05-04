package nl.adg.qwixx.e2e.helpers;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class SettingsInteractionHelper {

    /** Wait until the settings form is rendered (game-option controls are present). */
    public static void waitUntilLoaded(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(d -> !d.findElements(By.cssSelector("form button[type='submit']")).isEmpty());
        } catch (org.openqa.selenium.TimeoutException e) {
            String url    = driver.getCurrentUrl();
            String source = driver.getPageSource();
            String snippet = source.length() > 800 ? source.substring(0, 800) : source;
            throw new AssertionError(
                "Settings form (form button[type='submit']) not found after 15 s.\n" +
                "Current URL: " + url + "\n" +
                "Page source: " + snippet, e);
        }
    }

    /** Returns the names shown in the lobby-players list. */
    public static List<String> getLobbyPlayerNames(WebDriver driver) {
        return driver.findElements(By.className("lobby-player"))
                .stream()
                .map(e -> e.getText().trim())
                .toList();
    }

    /** Wait until the lobby shows exactly {@code count} players. */
    public static void waitUntilLobbyHasPlayers(WebDriver driver, int count) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> getLobbyPlayerNames(d).size() == count);
    }

    /**
     * Returns the current checked state of a boolean (checkbox) game option.
     * Uses the option's key as the element ID.
     */
    public static boolean isCheckboxChecked(WebDriver driver, String optionKey) {
        WebElement cb = driver.findElement(By.id(optionKey));
        return cb.isSelected();
    }

    /**
     * Sets a boolean (checkbox) option to the requested state.
     * Clicks only if the current state differs from the target.
     */
    public static void setCheckbox(WebDriver driver, String optionKey, boolean checked) {
        WebElement cb = driver.findElement(By.id(optionKey));
        if (cb.isSelected() != checked) cb.click();
    }

    /**
     * Waits until a boolean option reaches the expected state.
     * Used to verify that a lobby change from another player is synced here.
     */
    public static void waitUntilCheckboxIs(WebDriver driver, String optionKey, boolean expected) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> isCheckboxChecked(d, optionKey) == expected);
    }

    /** Clicks the submit button to start / restart the game. */
    public static void clickStartGame(WebDriver driver) {
        driver.findElement(By.cssSelector("form button[type='submit']")).click();
    }

    /**
     * Injects the player's identity into sessionStorage so the settings page
     * knows which player this browser instance represents.
     * Must be called BEFORE navigating to the settings page.
     */
    public static void setPlayerIdentity(WebDriver driver, String sessionId, String playerId) {
        ((JavascriptExecutor) driver).executeScript(
                "sessionStorage.setItem('qwixx_pid_' + arguments[0], arguments[1]);",
                sessionId, playerId);
    }
}
