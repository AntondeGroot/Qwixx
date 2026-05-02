package nl.adg.qwixx.e2e.utils;

import nl.adg.qwixx.e2e.helpers.ApiHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("browser")
@ExtendWith(SpringAppSuiteExtension.class)
public abstract class BaseIntegrationTest {

    protected static final ApiHelper api = new ApiHelper();
}
