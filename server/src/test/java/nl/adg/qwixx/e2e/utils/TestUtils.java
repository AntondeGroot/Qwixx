package nl.adg.qwixx.e2e.utils;

import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/***
 *  WARNING
 *  Changes to this file may severely impact performance for the Selenium tests.
 *  Test before committing any changes.
 */
public class TestUtils {

    private static final String BASE_URL = "http://127.0.0.1:4200";

    public static WebDriver getDriver(String sessionId, String playerId) {
        WebDriver driver = new ChromeDriver(buildOptions("1920,1080"));
        driver.get(BASE_URL + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        return driver;
    }

    /** Opens the board with a portrait (phone) viewport so mobile-rotation CSS fires. */
    public static WebDriver getPortraitDriver(String sessionId, String playerId) {
        WebDriver driver = new ChromeDriver(buildOptions("390,844")); // iPhone 14 Pro portrait
        driver.get(BASE_URL + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        return driver;
    }

    /** Creates a desktop driver without navigating anywhere (useful for sessionStorage setup). */
    public static WebDriver createDriver() {
        return new ChromeDriver(buildOptions("1920,1080"));
    }

    public static WebDriver getScoreDriver(String sessionId) {
        WebDriver driver = new ChromeDriver(buildOptions("1920,1080"));
        driver.get(BASE_URL + "/score/" + sessionId + "?fast=1");
        return driver;
    }

    /** Opens the score screen with a portrait (phone) viewport so mobile-rotation CSS fires. */
    public static WebDriver getPortraitScoreDriver(String sessionId) {
        WebDriver driver = new ChromeDriver(buildOptions("390,844"));
        driver.get(BASE_URL + "/score/" + sessionId + "?fast=1");
        return driver;
    }

    public static void waitUntilScoreLoaded(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(25)).until(d -> {
                try {
                    return !d.findElements(By.className("score-table")).isEmpty();
                } catch (StaleElementReferenceException e) {
                    return false;
                }
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            String url    = driver.getCurrentUrl();
            String source = driver.getPageSource();
            String snippet = source.length() > 800 ? source.substring(0, 800) : source;
            throw new AssertionError(
                ".score-table not found after 25 s.\n" +
                "Current URL: " + url + "\n" +
                "Page source: " + snippet, e);
        }
    }

    private static ChromeOptions buildOptions(String windowSize) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-extensions");
        options.addArguments("--window-size=" + windowSize);
        options.addArguments("--mute-audio");
        // unique profile dir prevents cross-instance conflicts in parallel tests
        options.addArguments("--user-data-dir=/tmp/chrome-user-data-" + System.nanoTime());
        return options;
    }

    public static void waitUntilBoardLoaded(WebDriver driver) {
        // Chrome startup + Angular bundle load + API call can take 10–15 s in a cold test run
        try {
            new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
                try {
                    return d.findElements(By.className("row")).stream()
                            .anyMatch(WebElement::isDisplayed);
                } catch (StaleElementReferenceException e) {
                    return false;
                }
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new AssertionError(".row not found on game board after 20 s. URL: "
                    + safeGetUrl(driver), e);
        }
    }

    private static String safeGetUrl(WebDriver driver) {
        try { return driver.getCurrentUrl(); } catch (WebDriverException e) { return "(unavailable)"; }
    }

    public static void clickById(WebDriver driver, String id) {
        new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
            try {
                d.findElement(By.id(id)).click();
                return true;
            } catch (StaleElementReferenceException e) {
                return false;
            }
        });
    }

    public static void waitUntilVisible(WebDriver driver, String className) {
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> d.findElement(By.className(className)).isDisplayed());
    }

    public static void waitUntilHidden(WebDriver driver, String className) {
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElement(By.className(className)).isDisplayed());
    }

    public static void waitUntilPresent(WebDriver driver, String id) {
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> !d.findElements(By.id(id)).isEmpty());
    }

    public static void waitUntilAbsent(WebDriver driver, String id) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.findElements(By.id(id)).isEmpty());
    }

    public static void wait(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
