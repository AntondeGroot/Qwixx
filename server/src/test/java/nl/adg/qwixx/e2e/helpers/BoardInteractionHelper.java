package nl.adg.qwixx.e2e.helpers;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class BoardInteractionHelper {

	public static void clickCell(WebDriver driver, String rowColor, int cellIndex) {
		// Find all cells in the row and click the one at cellIndex (0-based)
		By rowLocator = By.xpath("//div[@data-color='" + rowColor + "']");
		WebElement row = driver.findElement(rowLocator);
		java.util.List<WebElement> cells = row.findElements(By.className("cell"));
		if (cellIndex < cells.size()) {
			cells.get(cellIndex).click();
		} else {
			throw new IllegalArgumentException("Cell index " + cellIndex + " out of range. Row has " + cells.size() + " cells");
		}
	}

	public static void clickLockButton(WebDriver driver, String rowColor) {
		By lockLocator = By.xpath("//div[@data-color='" + rowColor + "']//div[@class='lock-cell']");
		WebElement lock = driver.findElement(lockLocator);
		lock.click();
	}

	public static boolean isLockButtonClickable(WebDriver driver, String rowColor) {
		By lockLocator = By.xpath("//div[@data-color='" + rowColor + "']//div[@class='lock-cell']");
		WebElement lock = driver.findElement(lockLocator);
		String classes = lock.getAttribute("class");
		return classes != null && classes.contains("lock-clickable");
	}

	public static int getCrossedCellCount(WebDriver driver, String rowColor) {
		List<WebElement> crossed = driver.findElements(By.xpath("//div[@data-color='" + rowColor + "']//div[@class='cell crossed']"));
		return crossed.size();
	}

	public static boolean isRowClosed(WebDriver driver, String rowColor) {
		try {
			WebElement row = driver.findElement(By.xpath("//div[@data-color='" + rowColor + "']"));
			String classes = row.getAttribute("class");
			return classes != null && classes.contains("closed");
		} catch (Exception e) {
			return false;
		}
	}

	public static void clickModalConfirmButton(WebDriver driver) {
		By confirmButton = By.xpath("//button[contains(@class, 'btn-primary')]");
		WebElement button = driver.findElement(confirmButton);
		button.click();
	}

	public static void clickModalChangeButton(WebDriver driver) {
		By changeButton = By.xpath("//button[contains(@class, 'btn-secondary')]");
		WebElement button = driver.findElement(changeButton);
		button.click();
	}

	public static boolean isModalVisible(WebDriver driver) {
		try {
			return driver.findElement(By.className("modal-overlay")).isDisplayed();
		} catch (Exception e) {
			return false;
		}
	}

	public static String getModalText(WebDriver driver) {
		try {
			WebElement modal = driver.findElement(By.className("modal-body"));
			return modal.getText();
		} catch (Exception e) {
			return "";
		}
	}
}
