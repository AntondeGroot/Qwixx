package nl.adg.qwixx;

import static org.assertj.core.api.Assertions.assertThat;

import nl.adg.qwixx.web.GamesApiDelegateImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Loads the full application context and asserts a real bean wires. Unit tests construct their
 * collaborators by hand and ArchUnit is static, so neither exercises Spring's DI — without this,
 * broken wiring (a component with two constructors and no {@code @Autowired}, a missing bean) would
 * only surface when the app actually starts. The default {@code webEnvironment = MOCK} loads the
 * FULL context — including the {@code @RestController}/SSE web beans — without binding a real port,
 * which is what this web app needs (NONE strips the servlet context and the web beans fail to wire).
 * The context is injected as a parameter rather than an {@code @Autowired} field so NullAway has
 * nothing to flag if it is ever added.
 */
@SpringBootTest(classes = QwixxApplication.class)
class QwixxApplicationTests {

    @Test
    void contextLoadsAndKeyBeanWires(ApplicationContext context) {
        assertThat(context.getBean(GamesApiDelegateImpl.class)).isNotNull();
    }
}
