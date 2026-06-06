package nl.adg.qwixx.e2e.utils;

import java.time.Duration;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

/***
 *  WARNING
 *  Changes to this file may severely impact performance for the Selenium tests.
 *  Test before committing any changes.
 */
public class TestUtils {

    private static String baseUrl() { return "http://127.0.0.1:" + SpringAppTestHelper.getPort(); }

    public static WebDriver getDriver(String sessionId, String playerId) {
        WebDriver driver = new ChromeDriver(buildOptions("1280,800"));
        primeRulesCookie(driver);
        driver.get(baseUrl() + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        return driver;
    }

    /**
     * Opens the board and blocks until it is fully loaded before returning.
     * Always prefer this over {@link #getDriver} + {@link #waitUntilBoardLoaded}
     * when opening multiple drivers sequentially, to prevent Chrome DevTools
     * disconnects caused by two browser instances loading simultaneously.
     */
    public static WebDriver getDriverAndWait(String sessionId, String playerId) {
        WebDriver driver = getDriver(sessionId, playerId);
        waitUntilBoardLoaded(driver);
        return driver;
    }

    /** Opens the board with a portrait (phone) viewport so mobile-rotation CSS fires. */
    public static WebDriver getPortraitDriver(String sessionId, String playerId) {
        WebDriver driver = new ChromeDriver(buildOptions("390,844")); // iPhone 14 Pro portrait
        primeRulesCookie(driver);
        driver.get(baseUrl() + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        return driver;
    }

    /**
     * Returns the driver as-is if its session is still alive, otherwise quietly
     * recreates it. Call this in {@code @BeforeEach} to prevent a single Chrome
     * crash from cascading NoSuchSessionExceptions into every subsequent test.
     */
    public static WebDriver ensureAlive(WebDriver driver) {
        return ensureAlive(driver, TestUtils::createDriver);
    }

    /** Portrait-driver variant — recreates with {@link #createPortraitDriver()} on session death. */
    public static WebDriver ensureAlivePortrait(WebDriver driver) {
        return ensureAlive(driver, TestUtils::createPortraitDriver);
    }

    private static WebDriver ensureAlive(WebDriver driver, java.util.function.Supplier<WebDriver> factory) {
        if (driver == null) return factory.get();
        try {
            driver.getCurrentUrl(); // throws if the session is dead
            return driver;
        } catch (WebDriverException _) {
            try { driver.quit(); } catch (Exception _) {}
            return factory.get();
        }
    }

    /** Creates a desktop driver and primes the rules-seen cookie so the first-time redirect never fires. */
    public static WebDriver createDriver() {
        WebDriver driver = new ChromeDriver(buildOptions("1280,800"));
        primeRulesCookie(driver);
        return driver;
    }

    /** Creates a portrait driver and primes the rules-seen cookie so the first-time redirect never fires. */
    public static WebDriver createPortraitDriver() {
        WebDriver driver = new ChromeDriver(buildOptions("390,844"));
        primeRulesCookie(driver);
        return driver;
    }

    /**
     * Navigates an existing driver to a game board URL and waits until the board is loaded.
     * Use this instead of {@link #getDriverAndWait} when reusing a driver across tests.
     */
    public static void navigateTo(WebDriver driver, String sessionId, String playerId) {
        driver.get(baseUrl() + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        waitUntilBoardLoaded(driver);
    }

    /**
     * Navigates an existing driver to the score screen and waits until it is loaded.
     * Use this instead of {@link #getScoreDriver} when reusing a driver across tests.
     */
    public static void navigateToScore(WebDriver driver, String sessionId) {
        String url = baseUrl() + "/score/" + sessionId + "?fast=1";
        driver.get(url);
        // Angular's route guard occasionally redirects back to the game URL on first load
        // (race between the guard's HTTP check and the navigation). Retry once if so.
        if (!driver.getCurrentUrl().contains("/score/")) {
            driver.get(url);
        }
        waitUntilScoreLoaded(driver);
    }

    public static WebDriver getScoreDriver(String sessionId) {
        WebDriver driver = new ChromeDriver(buildOptions("1280,800"));
        driver.get(baseUrl() + "/score/" + sessionId + "?fast=1");
        return driver;
    }

    /** Opens the score screen with a portrait (phone) viewport so mobile-rotation CSS fires. */
    public static WebDriver getPortraitScoreDriver(String sessionId) {
        WebDriver driver = new ChromeDriver(buildOptions("390,844"));
        driver.get(baseUrl() + "/score/" + sessionId + "?fast=1");
        return driver;
    }

    public static void waitUntilScoreLoaded(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(25)).until(d -> {
                try {
                    return !d.findElements(By.className("player-row")).isEmpty();
                } catch (StaleElementReferenceException _) {
                    return false;
                }
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            String url    = driver.getCurrentUrl();
            String source = driver.getPageSource();
            String snippet = source.length() > 800 ? source.substring(0, 800) : source;
            throw new AssertionError(
                ".player-row not found after 25 s (score data not yet loaded).\n" +
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
                } catch (StaleElementReferenceException _) {
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
            } catch (StaleElementReferenceException _) {
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
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Navigates to the /rules page to establish the app domain, then sets the
     * qwixx_rules_seen_v1 cookie.  Without this, a fresh Chrome instance has no
     * cookie and the Angular app redirects any /game/* navigation to /rules,
     * causing waitUntilBoardLoaded to time out.
     */
    private static void primeRulesCookie(WebDriver driver) {
        driver.get(baseUrl() + "/rules");
        driver.manage().addCookie(new Cookie("qwixx_rules_seen_v1", "1"));
    }
}
