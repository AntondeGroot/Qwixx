package nl.adg.qwixx.e2e.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Screenshots a catalog page for the docs: opens it against the Spring-served SPA, waits until every
 * preview has actually painted, then saves one PNG per {@code [data-opt-key]} block. Both docs
 * generators drive their page through this, so the two sets of images are produced the same way.
 */
public final class CatalogPageCapture {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PAINT_TIMEOUT = Duration.ofSeconds(10);

    private CatalogPageCapture() {}

    /**
     * @param path the catalog route, e.g. {@code "/option-catalog"}
     * @param keys the {@code data-opt-key} values to shoot; each becomes {@code <key>.png} in imageDir
     */
    public static void capture(WebDriver driver, String path, List<String> keys, Path imageDir) throws IOException {
        Files.createDirectories(imageDir);
        open(driver, path);
        for (String key : keys) {
            screenshot(driver, key, imageDir);
        }
    }

    private static void open(WebDriver driver, String path) {
        // Force English so rendered text never picks up the headless browser's own locale — the app
        // honours ?locale=en (see detectLocale()).
        driver.get("http://127.0.0.1:" + SpringAppTestHelper.getPort() + path + "?locale=en");
        // The page sets [data-catalog-ready] once every preview layout has loaded and rendered.
        new WebDriverWait(driver, READY_TIMEOUT)
                .until(d -> !d.findElements(By.cssSelector("[data-catalog-ready]")).isEmpty());
        waitForImages(driver);
        waitForConnectorOverlays(driver);
    }

    /** Every &lt;img&gt; (lock icons, Longo's bonus-number stars) must paint, or the shot is non-deterministic. */
    private static void waitForImages(WebDriver driver) {
        new WebDriverWait(driver, PAINT_TIMEOUT)
                .until(d -> script(d, "return Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)"));
    }

    /** Each overlay marks itself [data-measured] once it has measured its lines from the rendered cells. */
    private static void waitForConnectorOverlays(WebDriver driver) {
        new WebDriverWait(driver, PAINT_TIMEOUT)
                .until(d -> script(d, "return Array.from(document.querySelectorAll('app-connector-overlay'))"
                        + ".every(o => o.getAttribute('data-measured') === 'true')"));
    }

    private static void screenshot(WebDriver driver, String key, Path imageDir) throws IOException {
        WebElement shot = driver.findElement(By.cssSelector("[data-opt-key='" + key + "']"));
        // Scroll the element to the top of the viewport first: ChromeDriver's element screenshot clips
        // a tall element that extends past the current scroll position (it cut Longo's bonus-number
        // track off the bottom otherwise).
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'start'});", shot);
        File png = shot.getScreenshotAs(OutputType.FILE);
        Files.copy(png.toPath(), imageDir.resolve(key + ".png"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean script(WebDriver driver, String js) {
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(js));
    }
}
