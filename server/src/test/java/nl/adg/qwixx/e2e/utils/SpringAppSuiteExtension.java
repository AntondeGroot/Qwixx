package nl.adg.qwixx.e2e.utils;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;


public class SpringAppSuiteExtension implements BeforeAllCallback, TestWatcher {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SpringAppSuiteExtension.class);

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    System.err.println("""

        ╔══════════════════════════════════════════════════════════╗
        ║  E2E test failed — leftover Chrome processes can cause   ║
        ║  subsequent runs to flake. Kill them before re-running:  ║
        ║                                                          ║
        ║    pkill -9 -f "Google Chrome"; pkill -9 chromedriver    ║
        ╚══════════════════════════════════════════════════════════╝
        """);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    context.getRoot()
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent("spring-app", key -> new SpringAppResource(), SpringAppResource.class);
  }

  private static class SpringAppResource implements ExtensionContext.Store.CloseableResource {
    private boolean started = false;

    private SpringAppResource() {
      WebDriverManager.chromedriver().setup();
      SpringAppTestHelper.startApp();
      started = true;
    }

    @Override
    public void close() {
      if (started) {
        SpringAppTestHelper.stopApp();
      }
    }
  }
}
