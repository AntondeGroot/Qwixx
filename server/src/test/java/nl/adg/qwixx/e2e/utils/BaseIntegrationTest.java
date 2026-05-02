package nl.adg.qwixx.e2e.utils;

import nl.adg.qwixx.game.GameRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

@Tag("browser")
@ExtendWith(SpringAppSuiteExtension.class)
public abstract class BaseIntegrationTest {
  protected WebDriver driver;
  protected String baseUrl = "http://localhost:4200";
  protected static final long TIMEOUT_SECONDS = 10;

  protected void setupDriver() {
    ChromeOptions options = new ChromeOptions();
    options.addArguments("--headless=new", "--window-size=1920,1080", "--disable-gpu", "--no-sandbox");
    driver = new ChromeDriver(options);
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
  }

  protected void teardownDriver() {
    if (driver != null) {
      driver.quit();
    }
  }

  protected void waitForBoardLoad() {
    new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SECONDS))
        .until(ExpectedConditions.presenceOfElementLocated(By.className("row")));
  }

  protected String getSessionId() {
    return SpringAppTestHelper.getSessionId();
  }

  protected String getPlayerId(int index) {
    String sessionId = getSessionId();
    var game = GameRegistry.getGame(sessionId);
    if (game == null || game.players().size() <= index) {
      throw new IllegalArgumentException("Player at index " + index + " not found");
    }
    return game.players().get(index).id().toString();
  }
}
