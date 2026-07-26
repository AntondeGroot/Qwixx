package nl.adg.qwixx.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.SpringAppTestHelper;
import nl.adg.qwixx.e2e.utils.TestUtils;
import nl.adg.qwixx.game.options.GameOption;
import nl.adg.qwixx.game.options.GameOptionCatalog;
import nl.adg.qwixx.game.options.OptionCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Docs generator (not a normal test): renders the /option-catalog page against the real
 * Spring-served SPA, screenshots each option's sheet preview into docs/option-previews/, and
 * rewrites the auto-generated game-options block in the repo README. Because it renders the live
 * components, the images always track the current styling.
 *
 * <p>Guarded by a system property so the regular {@code verify} run never triggers it. Run with:
 * {@code ./mvnw failsafe:integration-test failsafe:verify -Dit.test=OptionPreviewGeneratorIT
 * -Dgenerate.option.previews=true} (see scripts/generate-option-docs.sh).
 */
@EnabledIfSystemProperty(named = "generate.option.previews", matches = "true")
class OptionPreviewGeneratorIT extends BaseIntegrationTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    private static final Path IMG_DIR = REPO_ROOT.resolve("docs/option-previews");
    private static final Path README = REPO_ROOT.resolve("README.md");
    private static final Path EN_JSON = REPO_ROOT.resolve("client/public/i18n/en.json");
    private static final String IMG_REL = "docs/option-previews";
    private static final String START_MARKER = "<!-- OPTIONS:START -->";
    private static final String END_MARKER = "<!-- OPTIONS:END -->";

    @Test
    void generateOptionDocs() throws Exception {
        Files.createDirectories(IMG_DIR);
        JsonNode i18n = new ObjectMapper().readTree(EN_JSON.toFile());
        List<CatalogEntry> entries = catalogEntries(i18n);
        WebDriver driver = TestUtils.createDriver();
        try {
            captureScreenshots(driver, entries);
        } finally {
            driver.quit();
        }
        rewriteReadme(entries);
    }

    /** One numbered catalog row: its image/DOM key, English label and description. */
    private record CatalogEntry(String key, String label, String description) {}

    /**
     * MODE options are the ones that change the sheet, so they are the ones we screenshot — with the
     * Variant option expanded into two entries (Standard, then Longo). Order matches the catalog page.
     */
    private List<CatalogEntry> catalogEntries(JsonNode i18n) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (GameOption option : GameOptionCatalog.all()) {
            if (option.category() != OptionCategory.MODE) continue;
            if ("base".equals(option.key())) {
                entries.add(new CatalogEntry("standard",
                        resolve(i18n, "gameOption.choice.STANDARD"), resolve(i18n, "gameOption.standardDescription")));
                entries.add(new CatalogEntry("longo",
                        resolve(i18n, "gameOption.choice.LONGO"), resolve(i18n, "gameOption.longoDescription")));
            } else {
                entries.add(new CatalogEntry(option.key(),
                        resolve(i18n, option.labelKey()), resolve(i18n, option.descriptionKey())));
            }
        }
        return entries;
    }

    private void captureScreenshots(WebDriver driver, List<CatalogEntry> entries) throws Exception {
        // Force English so the rendered previews (currently only Bonus B shows translated text) never
        // pick up the headless browser's own locale — the app honours ?locale=en (see detectLocale()).
        driver.get("http://127.0.0.1:" + SpringAppTestHelper.getPort() + "/option-catalog?locale=en");
        // The page sets [data-catalog-ready] once every preview layout has loaded and rendered.
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(d -> !d.findElements(By.cssSelector("[data-catalog-ready]")).isEmpty());
        // Wait for every <img> (the Longo bonus-number stars) to finish loading, or a screenshot
        // taken before they paint would be non-deterministic.
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "return Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)")));
        // Each connector overlay marks itself [data-measured] once it has measured its lines from the
        // rendered cells; wait for all of them so the arrows/connectors are painted before we shoot.
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "return Array.from(document.querySelectorAll('app-connector-overlay'))"
                                + ".every(o => o.getAttribute('data-measured') === 'true')")));

        for (CatalogEntry entry : entries) {
            WebElement shot = driver.findElement(By.cssSelector("[data-opt-key='" + entry.key() + "']"));
            // Scroll the element to the top of the viewport first: ChromeDriver's element screenshot
            // clips a tall element that extends past the current scroll position (it cut Longo's
            // bonus-number track off the bottom otherwise).
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'start'});", shot);
            File png = shot.getScreenshotAs(OutputType.FILE);
            Files.copy(png.toPath(), IMG_DIR.resolve(entry.key() + ".png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rewriteReadme(List<CatalogEntry> entries) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append(START_MARKER).append('\n');
        md.append("<!-- Auto-generated by OptionPreviewGeneratorIT (server e2e). Do not edit between these markers;\n");
        md.append("     run `./scripts/generate-option-docs.sh` to refresh — the pre-push hook regenerates\n");
        md.append("     and blocks the push if these are stale. -->\n\n");

        int number = 1;
        for (CatalogEntry entry : entries) {
            md.append("### ").append(number).append(". ").append(entry.label()).append("\n\n");
            if (!entry.description().isEmpty()) md.append(entry.description()).append("\n\n");
            md.append("![").append(entry.label()).append("](")
                    .append(IMG_REL).append('/').append(entry.key()).append(".png)\n\n");
            number++;
        }

        md.append(END_MARKER);

        String readme = Files.readString(README, StandardCharsets.UTF_8);
        int start = readme.indexOf(START_MARKER);
        int end = readme.indexOf(END_MARKER);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("README is missing the OPTIONS markers");
        }
        String updated = readme.substring(0, start) + md + readme.substring(end + END_MARKER.length());
        Files.writeString(README, updated, StandardCharsets.UTF_8);
    }

    /** Resolves a dotted i18n key (e.g. "gameOption.baseDescription") against the en.json tree. */
    private static String resolve(JsonNode root, String dottedKey) {
        JsonNode node = root;
        for (String segment : dottedKey.split("\\.")) {
            if (node == null) return "";
            node = node.get(segment);
        }
        return node != null && node.isTextual() ? node.asText() : "";
    }
}
