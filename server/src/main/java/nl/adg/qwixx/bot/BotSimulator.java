package nl.adg.qwixx.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import nl.adg.qwixx.game.GameMode;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.rules.ScoreCard;

/**
 * Runs many fully-automated games between bots to evaluate or compare {@link BotProfile}s.
 *
 * <p>Two objectives are supported:
 * <ul>
 *   <li><b>Most points</b> — use {@link #evaluate} and compare {@link EvalResult#avgScore()}.
 *   <li><b>Most wins</b> — use {@link #headToHead} and compare {@link HeadToHeadResult#p1WinRate()}.
 * </ul>
 *
 * Games run in parallel via a parallel stream.  Each game is independent; the call is thread-safe.
 */
public class BotSimulator {

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Outcome of evaluating a single profile against copies of itself.
     * Optimise {@link #avgScore()} for the "most points" strategy.
     */
    public record EvalResult(double avgScore, double minScore, double maxScore, int games) {}

    /**
     * Outcome of a head-to-head tournament between two profiles.
     * Optimise {@link #p1WinRate()} for the "most wins" strategy.
     *
     * @param avgScoreDiff positive means p1 scores more on average
     */
    public record HeadToHeadResult(int p1Wins, int p2Wins, int ties, int games, double avgScoreDiff) {
        public double p1WinRate() { return (double) p1Wins / games; }
    }

    private BotSimulator() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluates a profile by running {@code numGames} games where all {@code numBots}
     * seats use the same profile. Returns average / min / max score across all player-games.
     *
     * <p>Use {@link #baseSettings(int)} for a plain standard-mode game, or supply your own.
     */
    public static EvalResult evaluate(BotProfile profile, int numBots,
                                      int numGames, GameSettings base) {
        List<BotProfile> profiles = repeat(profile, numBots);
        double[] scores = IntStream.range(0, numGames)
                .parallel()
                .mapToObj(i -> playGame(profiles, base))
                .flatMapToDouble(r -> r.values().stream().mapToDouble(ScoreCard::total))
                .toArray();

        double sum = 0, min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (double s : scores) { sum += s; if (s < min) min = s; if (s > max) max = s; }
        return new EvalResult(sum / scores.length, min, max, numGames);
    }

    /**
     * Runs {@code numGames} between p1 (seat 0) and {@code (numBots-1)} copies of p2.
     * Seat assignments alternate each game so turn-order bias averages out.
     */
    public static HeadToHeadResult headToHead(BotProfile p1, BotProfile p2,
                                              int numBots, int numGames, GameSettings base) {
        record Outcome(boolean p1Won, boolean p2Won, int scoreDiff) {}

        List<Outcome> outcomes = IntStream.range(0, numGames)
                .parallel()
                .mapToObj(i -> {
                    boolean swap = (i % 2 != 0); // alternate who sits in seat 0
                    List<BotProfile> profiles = new ArrayList<>();
                    profiles.add(swap ? p2 : p1);
                    for (int j = 1; j < numBots; j++) profiles.add(swap ? p1 : p2);

                    Map<UUID, ScoreCard> result = playGame(profiles, base);
                    List<UUID> ids = result.keySet().stream().toList();

                    // seat 0 = "first" player; the rest use the second profile
                    UUID firstId = ids.get(0);
                    int firstScore = Objects.requireNonNull(result.get(firstId)).total();
                    int othersMax  = ids.stream().skip(1)
                            .mapToInt(id -> Objects.requireNonNull(result.get(id)).total()).max().orElse(firstScore);

                    int p1Score = swap ? othersMax : firstScore;
                    int p2Score = swap ? firstScore : othersMax;
                    return new Outcome(p1Score > p2Score, p2Score > p1Score, p1Score - p2Score);
                })
                .toList();

        int p1Wins = (int) outcomes.stream().filter(Outcome::p1Won).count();
        int p2Wins = (int) outcomes.stream().filter(Outcome::p2Won).count();
        int ties   = numGames - p1Wins - p2Wins;
        double avgDiff = outcomes.stream().mapToInt(Outcome::scoreDiff).average().orElse(0);
        return new HeadToHeadResult(p1Wins, p2Wins, ties, numGames, avgDiff);
    }

    /** Shorthand for a 2-player head-to-head. */
    public static HeadToHeadResult headToHead(BotProfile p1, BotProfile p2,
                                              int numGames, GameSettings base) {
        return headToHead(p1, p2, 2, numGames, base);
    }

    /** Returns a standard 2-player online-mode game settings with no special options. */
    public static GameSettings baseSettings(int numBots) {
        return GameSettings.builder()
                .botCount(numBots)
                .gameMode(GameMode.ONLINE)
                .build();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static Map<UUID, ScoreCard> playGame(List<BotProfile> profiles, GameSettings base) {
        GameSettings settings = GameSettings.builder()
                .base(base.base())
                .randomOrder(base.randomOrder())
                .connectedCells(base.connectedCells())
                .extraRow(base.extraRow())
                .mixedColors(base.mixedColors())
                .cardMode(base.cardMode())
                .gameMode(base.gameMode())
                .botCount(profiles.size())
                .botProfiles(profiles)
                .build();

        GameSession session = new GameSession(
                UUID.randomUUID().toString(), "sim", profiles.size(), settings);
        session.start();

        List<UUID> playerIds = session.currentState().players();
        var scores = new java.util.LinkedHashMap<UUID, ScoreCard>();
        for (UUID id : playerIds) scores.put(id, session.getScore(id));
        return scores;
    }

    private static <T> List<T> repeat(T value, int times) {
        List<T> list = new ArrayList<>(times);
        for (int i = 0; i < times; i++) list.add(value);
        return list;
    }
}
