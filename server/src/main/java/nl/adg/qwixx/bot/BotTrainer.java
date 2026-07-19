package nl.adg.qwixx.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import nl.adg.qwixx.game.GameSettings;

/**
 * Hill-climbing trainer for {@link BotProfile}.
 *
 * <p>Usage:
 * <pre>
 *   // Most-points strategy, 50 generations:
 *   BotProfile best = BotTrainer.train(BotProfile.DEFAULT, BotTrainer.Objective.MOST_POINTS,
 *                                      50, 10, 300, BotSimulator.baseSettings(2), new Random(),
 *                                      System.out::println);
 *   BotTrainer.save(best, Path.of("best-profile.json"));
 *   System.out.println(BotTrainer.toJavaSnippet(best));
 * </pre>
 *
 * <p>Or run {@link #main} directly to train both objectives against {@link BotProfile#DEFAULT}.
 */
public class BotTrainer {

    public enum Objective { MOST_POINTS, MOST_WINS }

    private BotTrainer() {}

    // ── Training ──────────────────────────────────────────────────────────────

    /**
     * Run with ./mvnw exec:java -Dexec.mainClass="nl.adg.qwixx.bot.BotTrainer"
     *
     * Hill-climbing with linearly decaying mutation magnitude ("cooling").
     *
     * <p>Each generation tries {@code mutationsPerGen} candidates; the best one that
     * improves fitness becomes the new current best. Magnitude shrinks from
     * {@code startMagnitude} to {@code startMagnitude / 10} over {@code generations} steps
     * so early generations explore broadly and later ones refine.
     *
     * @param start             starting profile (use {@link BotProfile#DEFAULT})
     * @param objective         what "better" means
     * @param generations       number of training iterations
     * @param mutationsPerGen   candidates evaluated per generation
     * @param gamesPerEval      games used to estimate each candidate's fitness
     * @param base              base game settings (use {@link BotSimulator#baseSettings})
     * @param rng               random source (seed for reproducibility)
     * @param log               receives a progress line per generation (e.g. {@code System.out::println})
     * @param reference         opponent profile used for {@link Objective#MOST_WINS} head-to-head (ignored for MOST_POINTS)
     * @param confirmRuns       evaluations used to compute stable averages for both the candidate and the current best;
     *                          the candidate is accepted only if its average strictly exceeds the current best's average,
     *                          guaranteeing that {@code bestFitness} is monotonically non-decreasing
     * @return the best profile found
     */
    // `candidate != best` below is a deliberate identity check — "was a different profile object
    // chosen this round" — not a value comparison. (PMD and Error Prone both flag it.)
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"})
    public static BotProfile train(BotProfile start,
                                   Objective objective,
                                   int generations,
                                   int mutationsPerGen,
                                   int gamesPerEval,
                                   GameSettings base,
                                   Random rng,
                                   java.util.function.Consumer<String> log,
                                   BotProfile reference,
                                   int confirmRuns) {

        Function<BotProfile, Double> fitness = fitnessFunction(objective, gamesPerEval, base, reference);

        BotProfile best        = start;
        double     bestFitness = average(fitness, best, confirmRuns);
        double     startMag    = 0.8;

        log.accept(String.format("Generation 0 (baseline): fitness=%.4f", bestFitness));

        for (int gen = 1; gen <= generations; gen++) {
            double magnitude = startMag * (1.0 - (double)(gen - 1) / generations) + startMag / 10.0;

            BotProfile candidate   = best;
            double     candFitness = bestFitness;
            for (int m = 0; m < mutationsPerGen; m++) {
                BotProfile c = best.mutate(magnitude, rng);
                double f = fitness.apply(c);
                if (f > candFitness) { candFitness = f; candidate = c; }
            }

            boolean improved = false;
            if (candidate != best) {
                double candidateAvg = average(fitness, candidate, confirmRuns);
                if (candidateAvg > bestFitness) {
                    best        = candidate;
                    bestFitness = candidateAvg;
                    improved    = true;
                }
            }

            log.accept(String.format("Generation %3d: fitness=%.4f  mag=%.3f  %s",
                    gen, bestFitness, magnitude, improved ? "↑ improved" : ""));
        }

        return best;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Saves the profile as a human-readable JSON file. */
    public static void save(BotProfile profile, Path file) throws IOException {
        Files.writeString(file, toJson(profile));
    }

    /**
     * Loads a profile previously saved by {@link #save}.
     * Falls back to {@link BotProfile#DEFAULT} if the file does not exist.
     */
    public static BotProfile load(Path file) throws IOException {
        if (!Files.exists(file)) return BotProfile.DEFAULT;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new ObjectMapper().readValue(file.toFile(), Map.class);
        return fromMap(map);
    }

    /** Loads a profile from an {@link InputStream} (e.g. a classpath resource). */
    public static BotProfile load(InputStream is) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new ObjectMapper().readValue(is, Map.class);
        return fromMap(map);
    }

    private static BotProfile fromMap(Map<String, Object> map) {
        return new BotProfile(
                num(map, "skipPenalty"),
                num(map, "rarityBonus"),
                num(map, "lockBonus"),
                num(map, "punishmentLoss"),
                num(map, "passiveThreshold"),
                (int) Math.round(num(map, "maxPunishments")),
                (int) Math.round(num(map, "nearLockRemaining"))
        );
    }

    /**
     * Returns a Java one-liner you can paste into {@link BotProfile#DEFAULT} to preserve
     * the trained values permanently.
     */
    public static String toJavaSnippet(BotProfile p) {
        return String.format(
                "new BotProfile(%.4f, %.4f, %.4f, %.4f, %.4f, %d, %d)",
                p.skipPenalty(), p.rarityBonus(), p.lockBonus(),
                p.punishmentLoss(), p.passiveThreshold(),
                p.maxPunishments(), p.nearLockRemaining());
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Trains both objectives and saves results to {@code trained-most-points.json} and
     * {@code trained-most-wins.json} in the working directory.
     *
     * <p>Adjust the constants at the top of this method to control training time.
     */
    @SuppressWarnings("PMD.SystemPrintln")
    public static void main(String[] args) throws IOException {
        int    generations     = 60;
        int    mutationsPerGen = 12;
        int    gamesPerEval    = 400;
        int    confirmRuns     = 5;
        long   seed            = 42L;
        GameSettings base      = BotSimulator.baseSettings(2);

        // Save into the resources directory so profiles are bundled into the next JAR build.
        Path profilesDir = Path.of("src/main/resources/profiles");
        Path pointsFile  = profilesDir.resolve("trained-most-points.json");
        Path winsFile    = profilesDir.resolve("trained-most-wins.json");

        BotProfile pointsStart = load(pointsFile);
        System.out.println("=== Training: MOST_POINTS (starting from " +
                (Files.exists(pointsFile) ? pointsFile : "DEFAULT") + ") ===");
        BotProfile pointsProfile = train(
                pointsStart, Objective.MOST_POINTS,
                generations, mutationsPerGen, gamesPerEval,
                base, new Random(seed), System.out::println, pointsStart, confirmRuns);
        save(pointsProfile, pointsFile);
        System.out.println("\nBest (most points): " + toJavaSnippet(pointsProfile));

        BotProfile winsStart = load(winsFile);
        System.out.println("\n=== Training: MOST_WINS (starting from " +
                (Files.exists(winsFile) ? winsFile : "DEFAULT") + ") ===");
        BotProfile winsProfile = train(
                winsStart, Objective.MOST_WINS,
                generations, mutationsPerGen, gamesPerEval,
                base, new Random(seed), System.out::println, winsStart, confirmRuns);
        save(winsProfile, winsFile);
        System.out.println("\nBest (most wins):   " + toJavaSnippet(winsProfile));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Function<BotProfile, Double> fitnessFunction(
            Objective objective, int games, GameSettings base, BotProfile reference) {
        return switch (objective) {
            case MOST_POINTS -> p -> BotSimulator.evaluate(p, 2, games, base).avgScore();
            case MOST_WINS   -> p -> BotSimulator.headToHead(p, reference, games, base).p1WinRate();
        };
    }

    private static String toJson(BotProfile p) {
        return String.format(java.util.Locale.US,
                "{%n" +
                "  \"skipPenalty\":       %.6f,%n" +
                "  \"rarityBonus\":       %.6f,%n" +
                "  \"lockBonus\":         %.6f,%n" +
                "  \"punishmentLoss\":    %.6f,%n" +
                "  \"passiveThreshold\":  %.6f,%n" +
                "  \"maxPunishments\":    %d,%n" +
                "  \"nearLockRemaining\": %d%n" +
                "}%n",
                p.skipPenalty(), p.rarityBonus(), p.lockBonus(),
                p.punishmentLoss(), p.passiveThreshold(),
                p.maxPunishments(), p.nearLockRemaining());
    }

    private static double average(Function<BotProfile, Double> fitness, BotProfile p, int runs) {
        double sum = 0;
        for (int i = 0; i < runs; i++) sum += fitness.apply(p);
        return sum / runs;
    }

    private static double num(Map<String, Object> map, String key) {
        return ((Number) Objects.requireNonNull(map.get(key))).doubleValue();
    }
}
