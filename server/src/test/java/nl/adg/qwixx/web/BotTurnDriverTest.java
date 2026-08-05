package nl.adg.qwixx.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.state.GameState;
import org.junit.jupiter.api.Test;

class BotTurnDriverTest {

    @Test
    void doesNotRetryWhenDrivingThrowsWhileABotStaysPending() throws InterruptedException {
        AlwaysFailingSession session = new AlwaysFailingSession();
        BotTurnDriver driver = new BotTurnDriver();
        CountDownLatch idle = new CountDownLatch(1);

        driver.ensureDriving("session-1", session, state -> { }, idle::countDown, null);

        assertTrue(idle.await(5, TimeUnit.SECONDS),
                "drive loop should stop and signal idle instead of re-driving forever");
        assertEquals(1, session.driveAttempts.get(),
                "a failed drive should not be retried: retrying spins a core and writes a stack "
                        + "trace per iteration until the disk is full");
    }

    /** A session that always has a bot to act and always fails to drive it — the runaway-loop shape. */
    private static final class AlwaysFailingSession extends GameSession {

        private final AtomicInteger driveAttempts = new AtomicInteger();

        private AlwaysFailingSession() {
            super("session-1", "room", 2, GameSettings.builder().build());
        }

        @Override
        public boolean isBotToAct() {
            return true;
        }

        @Override
        public void driveBotTurns(Consumer<GameState> emit) {
            driveAttempts.incrementAndGet();
            throw new IllegalStateException("bot cannot act");
        }
    }
}
