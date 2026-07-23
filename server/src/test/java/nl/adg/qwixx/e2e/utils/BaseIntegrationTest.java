package nl.adg.qwixx.e2e.utils;

import nl.adg.qwixx.e2e.helpers.ApiHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

// Retry is registered here so every browser IT that extends this class gets it
// automatically — retrying on WebDriverException (crashed session) and the
// load-induced WebDriverWait TimeoutExceptions it subclasses. Do NOT annotate
// individual tests; use @RetryOnChromeFailure(times = N) only to override the count.
@Tag("browser")
@ExtendWith(SpringAppSuiteExtension.class)
@ExtendWith(RetryOnChromeFailure.Extension.class)
public abstract class BaseIntegrationTest {

    protected static final ApiHelper api = new ApiHelper();
}
