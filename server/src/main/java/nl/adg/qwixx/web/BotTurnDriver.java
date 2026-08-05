package nl.adg.qwixx.web;

import jakarta.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.state.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives a session's paced bot turns asynchronously, off the request thread, so a human's move
 * returns immediately and is never blocked behind bot pacing (the pacing sleeps inside
 * {@link GameSession#driveBotTurns} hold no lock, so a human move interleaves between bot actions).
 *
 * <p>Single-flight per session: overlapping kicks never spawn duplicate loops, and a bot that
 * becomes pending while a loop is finishing is picked up by a re-check after the flag is released.
 */
@Component
public class BotTurnDriver {

    private static final Logger logger = LoggerFactory.getLogger(BotTurnDriver.class);

    /**
     * Caps the chain of re-drives that follow one kick. Normal play needs at most a couple (a human
     * move that queued a bot while the previous loop was finishing). A session whose bot can never
     * finish its turn — a rules bug, say — would otherwise re-drive forever: an unpaced hot loop that
     * spins a core and, if it also throws, writes a stack trace per iteration until the disk is full.
     * Hitting the cap parks the session; the next player action kicks it again.
     */
    private static final int MAX_REDRIVES = 8;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * Ensures pending bot turns for this session are being driven, then returns immediately.
     *
     * @param emit    receives each intermediate state to broadcast (e.g. over SSE)
     * @param onIdle  run once no bot is pending — e.g. a finished-game notification
     * @param preStep optional work to run before driving (e.g. wait for an SSE subscriber so a
     *                first-acting bot's dice animation isn't missed); {@code null} to skip
     */
    public void ensureDriving(String sessionId, GameSession session, Consumer<GameState> emit,
            Runnable onIdle, @Nullable Runnable preStep) {
        ensureDriving(sessionId, session, emit, onIdle, preStep, MAX_REDRIVES);
    }

    private void ensureDriving(String sessionId, GameSession session, Consumer<GameState> emit,
            Runnable onIdle, @Nullable Runnable preStep, int redrivesLeft) {
        if (!session.isBotToAct()) {
            onIdle.run();
            return;
        }
        if (!running.add(sessionId)) return; // a loop is already driving this session
        executor.execute(() -> runLoop(sessionId, session, emit, onIdle, preStep, redrivesLeft));
    }

    private void runLoop(String sessionId, GameSession session, Consumer<GameState> emit,
            Runnable onIdle, @Nullable Runnable preStep, int redrivesLeft) {
        boolean drivenCleanly = drive(sessionId, session, emit, preStep);
        // A human move may have queued a bot while we were finishing (and skipped kicking because we
        // still held the flag). Re-check after releasing it, and re-drive if so — otherwise signal idle.
        // A failed drive is not retried here: whatever broke would break again immediately, so the
        // session is parked until the next player action rather than spun (and logged) in a loop.
        if (drivenCleanly && redrivesLeft > 0) {
            ensureDriving(sessionId, session, emit, onIdle, null, redrivesLeft - 1);
            return;
        }
        if (drivenCleanly && session.isBotToAct()) {
            logger.warn("Bot turns for session {} still pending after {} re-drives; parking the loop"
                    + " until the next player action", sessionId, MAX_REDRIVES);
        }
        onIdle.run();
    }

    /**
     * Drives the session's pending bot turns and releases the single-flight flag. Returns {@code false}
     * when the drive threw — logged here, once per kick, so the caller can decide not to retry.
     */
    private boolean drive(String sessionId, GameSession session, Consumer<GameState> emit,
            @Nullable Runnable preStep) {
        try {
            if (preStep != null) preStep.run();
            session.driveBotTurns(emit);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Bot turns failed for session {}; parking the loop until the next player"
                    + " action", sessionId, e);
            return false;
        } finally {
            running.remove(sessionId);
        }
    }
}
