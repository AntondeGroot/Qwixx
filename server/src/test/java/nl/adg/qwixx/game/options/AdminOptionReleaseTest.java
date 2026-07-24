package nl.adg.qwixx.game.options;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The trial variants open up to everyone at a fixed moment. These pin the clock to both sides of it,
 * so the switch is proven now rather than discovered on the day.
 */
class AdminOptionReleaseTest {

    /** Every option that is admin-only during the trial. */
    private static final Set<String> TRIAL_OPTIONS = Set.of(
            "bigPoints", "xChange", "luckyNumber", "luckyCross", "doubleA", "doubleB", "bonusA", "bonusB",
            "connectedDiagonal", "mixedColors");

    private static final Instant RELEASE = AdminOptionRelease.RELEASE_MOMENT.toInstant();

    @AfterEach
    void restoreClock() {
        AdminOptionRelease.useSystemClock();
    }

    private static void at(Instant moment) {
        AdminOptionRelease.setClock(Clock.fixed(moment, ZoneOffset.UTC));
    }

    private static Set<String> adminOnlyKeys() {
        return QwixxGameOptions.all().stream()
                .filter(GameOption::adminOnly)
                .map(GameOption::key)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void beforeTheReleaseTheTrialVariantsAreAdminOnly() {
        at(RELEASE.minusSeconds(1));

        assertFalse(AdminOptionRelease.released());
        assertEquals(TRIAL_OPTIONS, adminOnlyKeys(), "exactly the trial variants are admin-only until release");
    }

    @Test
    void fromTheReleaseMomentNothingIsAdminOnly() {
        at(RELEASE);

        assertTrue(AdminOptionRelease.released(), "the moment itself counts as released");
        assertEquals(Set.of(), adminOnlyKeys(), "every option is selectable by every player");
    }

    @Test
    void afterTheReleaseNothingIsAdminOnly() {
        at(RELEASE.plusSeconds(1));

        assertEquals(Set.of(), adminOnlyKeys());
    }

    @Test
    void releasingChangesOnlyWhoMayPickTheVariants() {
        at(RELEASE.minusSeconds(1));
        List<GameOption> before = QwixxGameOptions.all();
        at(RELEASE.plusSeconds(1));
        List<GameOption> after = QwixxGameOptions.all();

        assertEquals(before.size(), after.size(), "no option appears or disappears");
        for (int i = 0; i < before.size(); i++) {
            GameOption b = before.get(i);
            GameOption a = after.get(i);
            assertEquals(b.key(), a.key(), "the options keep their order");
            assertEquals(b.defaultValue(), a.defaultValue(), b.key() + " keeps its default, so no game changes");
            assertEquals(b.type(), a.type(), b.key() + " keeps its type");
            assertEquals(b.labelKey(), a.labelKey(), b.key() + " keeps its label");
            assertEquals(b.descriptionKey(), a.descriptionKey(), b.key() + " keeps its description");
            assertEquals(b.incompatibleWith(), a.incompatibleWith(), b.key() + " keeps its incompatibilities");
        }
    }

    @Test
    void everyTrialVariantStaysOffByDefaultAfterRelease() {
        at(RELEASE.plusSeconds(1));

        for (GameOption o : QwixxGameOptions.all()) {
            if (TRIAL_OPTIONS.contains(o.key())) {
                assertEquals("false", o.defaultValue(), o.key() + " must not switch itself on for everyone");
            }
        }
    }

    @Test
    void theReleaseMomentIsFourAmDutchTimeOnTheTwentyFourth() {
        // 04:00 Europe/Amsterdam in July (CEST, UTC+2) == 02:00 UTC.
        assertEquals(Instant.parse("2026-07-24T02:00:00Z"), RELEASE);
    }
}
