package nl.adg.qwixx.bot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Named playing styles selectable in the game options UI.
 *
 * <ul>
 *   <li>{@link #UNTRAINED}   — hand-tuned defaults, loaded from {@code trained-untrained.json}
 *   <li>{@link #MOST_POINTS} — optimised to maximise average score, from {@code trained-most-points.json}
 *   <li>{@link #MOST_WINS}   — optimised to maximise win rate, from {@code trained-most-wins.json}
 *   <li>{@link #BALANCED}    — runtime average of the three profiles above; no separate file
 * </ul>
 *
 * Profiles for UNTRAINED / MOST_POINTS / MOST_WINS are loaded lazily from classpath resources
 * under {@code /profiles/} and bundled into the JAR at build time.
 * Run {@link BotTrainer} to improve the trained profiles, then rebuild to deploy them.
 */
public enum BotStrategy {

    UNTRAINED,
    MOST_POINTS,
    MOST_WINS,

    /** Computed at runtime as the element-wise average of the three concrete profiles. */
    BALANCED;

    private static final Logger log = Logger.getLogger(BotStrategy.class.getName());
    // Intentional lazy memoization of the trained profile; the mutable field is deliberate.
    @SuppressWarnings("ImmutableEnumChecker")
    private transient volatile BotProfile cachedProfile;

    public BotProfile profile() {
        if (cachedProfile == null) {
            synchronized (this) {
                if (cachedProfile == null) {
                    cachedProfile = (this == BALANCED)
                            ? BotProfile.average(UNTRAINED.profile(), MOST_POINTS.profile(), MOST_WINS.profile())
                            : loadFromClasspath();
                }
            }
        }
        return cachedProfile;
    }

    private BotProfile loadFromClasspath() {
        String resource = "/profiles/trained-" + name().toLowerCase(Locale.ROOT).replace('_', '-') + ".json";
        try (InputStream is = BotStrategy.class.getResourceAsStream(resource)) {
            if (is != null) return BotTrainer.load(is);
            log.warning("Profile resource not found: " + resource + " — using DEFAULT");
        } catch (IOException e) {
            log.warning("Failed to load profile " + resource + ": " + e.getMessage() + " — using DEFAULT");
        }
        return BotProfile.DEFAULT;
    }
}
