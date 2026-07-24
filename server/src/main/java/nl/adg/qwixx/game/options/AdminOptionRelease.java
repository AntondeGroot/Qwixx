package nl.adg.qwixx.game.options;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When the trial-only game variants (Big Points, X-Change, Lucky Number, Lucky Cross, Double A/B,
 * Bonus A/B) stop being admin-only and become selectable by every player.
 *
 * <p>The moment is evaluated per request, so the switch happens on its own — no deploy or restart is
 * needed on the day. The zone is pinned to Europe/Amsterdam so it lands at 04:00 Dutch time
 * regardless of the server's own timezone.
 */
public final class AdminOptionRelease {

    public static final ZonedDateTime RELEASE_MOMENT =
            ZonedDateTime.of(2026, 7, 24, 4, 0, 0, 0, ZoneId.of("Europe/Amsterdam"));

    private static volatile Clock clock = Clock.systemUTC();

    private AdminOptionRelease() {}

    /** True from the release moment onwards, i.e. the variant options are open to everyone. */
    public static boolean released() {
        return !Instant.now(clock).isBefore(RELEASE_MOMENT.toInstant());
    }

    /** Test seam: pin "now" so the release can be tested from both sides. */
    static void setClock(Clock fixed) {
        clock = fixed;
    }

    static void useSystemClock() {
        clock = Clock.systemUTC();
    }
}
