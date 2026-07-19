package nl.adg.qwixx.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules for the server — the Java counterpart to the client's eslint-plugin-boundaries.
 * These are static {@code @ArchTest} rules, so they run in the build and fail CI on any violation.
 *
 * <p>Tests are excluded from the import (per ArchUnit guidance) so a test class sharing a production
 * package doesn't appear as a production dependency; the generated OpenAPI code and the dev-only
 * tooling (testapp harness, offline bot-training mains) are excluded too, so the rules describe only
 * the hand-written code that runs in the served app.
 */
@AnalyzeClasses(
        packages = "nl.adg.qwixx",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.ExcludeGeneratedAndTooling.class})
class ArchitectureTest {

    private static final String[] DOMAIN = {
        "..qwixx.data..", "..qwixx.state..", "..qwixx.rules..", "..qwixx.game..", "..qwixx.bot..", "..qwixx.action.."
    };

    @ArchTest
    static final ArchRule domainDoesNotDependOnTheWebLayer = noClasses()
            .that()
            .resideInAnyPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..qwixx.web..")
            .because("the domain layer must not depend on the web layer");

    @ArchTest
    static final ArchRule theDomainStaysFrameworkAgnostic = noClasses()
            .that()
            .resideInAnyPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because("domain logic must not couple to Spring");

    @ArchTest
    static final ArchRule onlyTheWebLayerTouchesGeneratedCode = noClasses()
            .that()
            .resideOutsideOfPackages("..qwixx.web..", "..qwixx.generated..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..qwixx.generated..")
            .because("only the web layer may use the generated OpenAPI code");

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching("nl.adg.qwixx.(*)..").should().beFreeOfCycles();

    /**
     * Excludes from the analysis the generated OpenAPI code and the dev-only tooling — the testapp
     * harness and the offline bot-training mains (BotSimulator/BotTrainer). None of it runs in the
     * served app, and the training mains are the only thing that makes {@code bot} depend on
     * {@code game}, which would otherwise be a false cycle.
     */
    static class ExcludeGeneratedAndTooling implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/generated/")
                    && !location.contains("/testapp/")
                    && !location.contains("BotSimulator")
                    && !location.contains("BotTrainer");
        }
    }
}
