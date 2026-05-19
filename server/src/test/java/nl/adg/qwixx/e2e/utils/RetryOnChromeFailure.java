package nl.adg.qwixx.e2e.utils;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.openqa.selenium.WebDriverException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Retries a browser test up to {@link #times()} times when Chrome crashes
 * (NoSuchSessionException / WebDriverException with "not connected to DevTools").
 *
 * Usage:
 * <pre>
 *   &#64;Test
 *   &#64;ExtendWith(RetryOnChromeFailure.Extension.class)
 *   &#64;RetryOnChromeFailure
 *   void myFlakyTest() { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryOnChromeFailure {

    int times() default 3;

    class Extension implements TestExecutionExceptionHandler {

        private static final ExtensionContext.Namespace NS =
                ExtensionContext.Namespace.create(Extension.class);

        @Override
        public void handleTestExecutionException(ExtensionContext ctx, Throwable t) throws Throwable {
            if (!(t instanceof WebDriverException)) throw t;

            RetryOnChromeFailure ann = ctx.getRequiredTestMethod()
                    .getAnnotation(RetryOnChromeFailure.class);
            if (ann == null) throw t;

            int attempt = ctx.getStore(NS)
                    .getOrComputeIfAbsent("attempt", k -> 1, Integer.class);

            if (attempt >= ann.times()) throw t;

            ctx.getStore(NS).put("attempt", attempt + 1);
            System.out.printf("[RetryOnChromeFailure] %s crashed (attempt %d/%d): %s%n",
                    ctx.getDisplayName(), attempt, ann.times(), t.getMessage());

            // Re-invoke the test method manually for the next attempt.
            try {
                ctx.getRequiredTestMethod().invoke(ctx.getRequiredTestInstance());
            } catch (java.lang.reflect.InvocationTargetException e) {
                handleTestExecutionException(ctx, e.getCause());
            }
        }
    }
}