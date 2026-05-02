package nl.adg.qwixx.e2e.utils;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SpringAppSuiteExtension implements BeforeAllCallback {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SpringAppSuiteExtension.class);

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
      SpringAppTestHelper.startTestApp();
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
