package nl.adg.qwixx.e2e;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public abstract class SeleniumTestBase {
	protected WebDriver driver;
	protected String baseUrl = System.getProperty("game.url", "http://localhost:4200");
	protected static final long TIMEOUT_SECONDS = 10;

	@BeforeEach
	public void setUp() {
		WebDriverManager.chromedriver().setup();
		ChromeOptions options = new ChromeOptions();
		options.addArguments("--headless=new", "--window-size=1920,1080", "--disable-gpu", "--no-sandbox");
		driver = new ChromeDriver(options);
		driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
	}

	@AfterEach
	public void tearDown() {
		if (driver != null) {
			driver.quit();
		}
	}

	protected WebElement waitForElement(By locator) {
		return new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SECONDS))
			.until(ExpectedConditions.presenceOfElementLocated(locator));
	}

	protected void waitForElementToBeClickable(By locator) {
		new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SECONDS))
			.until(ExpectedConditions.elementToBeClickable(locator));
	}

	protected void waitForElementToDisappear(By locator) {
		new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SECONDS))
			.until(ExpectedConditions.invisibilityOfElementLocated(locator));
	}

	protected boolean isElementVisible(By locator) {
		try {
			return driver.findElement(locator).isDisplayed();
		} catch (Exception e) {
			return false;
		}
	}
}
