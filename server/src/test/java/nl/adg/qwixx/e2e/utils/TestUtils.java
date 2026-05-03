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
        WebDriver driver = new ChromeDriver(buildOptions());
        driver.get(BASE_URL + "/?sessionid=" + sessionId + "&playerid=" + playerId);
        return driver;
    }

    public static WebDriver getScoreDriver(String sessionId) {
        WebDriver driver = new ChromeDriver(buildOptions());
        driver.get(BASE_URL + "/score/" + sessionId);
        return driver;
    }

    public static void waitUntilScoreLoaded(WebDriver driver) {
        new WebDriverWait(driver, Duration.ofSeconds(25)).until(d -> {
            try {
                return !d.findElements(By.className("score-table")).isEmpty();
            } catch (StaleElementReferenceException e) {
                return false;
            }
        });
    }

    private static ChromeOptions buildOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--mute-audio");
        // unique profile dir prevents cross-instance conflicts in parallel tests
        options.addArguments("--user-data-dir=/tmp/chrome-user-data-" + System.nanoTime());
        return options;
    }

    public static void waitUntilBoardLoaded(WebDriver driver) {
        // Chrome startup + Angular bundle load + API call can take 10–15 s in a cold test run
        new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
            try {
                return d.findElements(By.className("row")).stream()
                        .anyMatch(WebElement::isDisplayed);
            } catch (StaleElementReferenceException e) {
                return false;
            }
        });
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
