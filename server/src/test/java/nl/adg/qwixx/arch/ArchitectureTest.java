package nl.adg.qwixx.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * Architecture rules for the server — the Java counterpart to the client's eslint-plugin-boundaries.
 * These are static {@code @ArchTest} rules, so they run in the build and fail CI on any violation.
 *
 * <p>Tests are excluded from the import (per ArchUnit guidance) so a test class sharing a production
 * package doesn't appear as a production dependency; the generated OpenAPI code and the dev-only
 * {@code testapp} harness are excluded too, so the rules describe only hand-written application code.
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

    // The domain packages (game/state/bot/rules) are currently intertwined with 7 dependency cycles.
    // Untangling them is a real refactoring project, so this is frozen: the current cycles are an
    // accepted baseline (in archunit_store/) and the rule fails only when a NEW cycle is introduced.
    // TODO: break the existing cycles (e.g. move game.VariantData into state) and shrink the baseline.
    @ArchTest
    static final ArchRule packagesAreFreeOfNewCycles =
            FreezingArchRule.freeze(slices().matching("nl.adg.qwixx.(*)..").should().beFreeOfCycles());

    /** Excludes the generated OpenAPI code and the dev-only testapp harness from the analysis. */
    static class ExcludeGeneratedAndTooling implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/generated/") && !location.contains("/testapp/");
        }
    }
}
