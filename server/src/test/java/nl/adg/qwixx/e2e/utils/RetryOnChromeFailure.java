package nl.adg.qwixx.e2e.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.openqa.selenium.WebDriverException;

/**
 * Retries a browser test on any {@link WebDriverException} — which covers both a
 * genuinely crashed session (NoSuchSessionException / "not connected to DevTools")
 * <em>and</em> load-induced {@link org.openqa.selenium.TimeoutException}s from a
 * {@code WebDriverWait} (TimeoutException extends WebDriverException). Those timing
 * flakes are the dominant failure mode when a 3-player test drives 3 Chromes against
 * one server under CI load.
 *
 * <p><strong>Registered once on {@code BaseIntegrationTest}, so it applies to every
 * browser IT automatically</strong> — you do NOT annotate individual tests. Add the
 * method-level annotation only to override the retry count for one test:
 * <pre>
 *   &#64;Test
 *   &#64;RetryOnChromeFailure(times = 5)   // optional: override the default of 3
 *   void aParticularlyFlakyTest() { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryOnChromeFailure {

    int DEFAULT_TIMES = 3;

    int times() default DEFAULT_TIMES;

    class Extension implements TestExecutionExceptionHandler {

        private static final ExtensionContext.Namespace NS =
                ExtensionContext.Namespace.create(Extension.class);

        @Override
        public void handleTestExecutionException(ExtensionContext ctx, Throwable t) throws Throwable {
            if (!(t instanceof WebDriverException)) throw t;

            // Retry is on by default for every browser test; the annotation is an optional
            // per-method override of the count, not the on/off switch.
            RetryOnChromeFailure ann = ctx.getRequiredTestMethod()
                    .getAnnotation(RetryOnChromeFailure.class);
            int maxTimes = ann != null ? ann.times() : DEFAULT_TIMES;

            int attempt = ctx.getStore(NS)
                    .getOrComputeIfAbsent("attempt", k -> 1, Integer.class);

            if (attempt >= maxTimes) throw t;

            ctx.getStore(NS).put("attempt", attempt + 1);
            System.out.printf("[RetryOnChromeFailure] %s failed (attempt %d/%d): %s%n",
                    ctx.getDisplayName(), attempt, maxTimes, t.getMessage());

            // Re-run @BeforeEach methods so ensureAlive() can recreate the crashed driver,
            // then re-invoke the test method.
            try {
                Object instance = ctx.getRequiredTestInstance();
                for (Method m : instance.getClass().getDeclaredMethods()) {
                    if (m.isAnnotationPresent(BeforeEach.class)) {
                        m.setAccessible(true);
                        m.invoke(instance);
                    }
                }
                ctx.getRequiredTestMethod().invoke(instance);
            } catch (java.lang.reflect.InvocationTargetException e) {
                handleTestExecutionException(ctx, e.getCause());
            }
        }
    }
}
