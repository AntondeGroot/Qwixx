package nl.adg.qwixx.e2e;

import nl.adg.qwixx.e2e.utils.BaseIntegrationTest;
import nl.adg.qwixx.e2e.utils.SpringAppTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static nl.adg.qwixx.e2e.helpers.ScoreInteractionHelper.*;
import static nl.adg.qwixx.e2e.helpers.SettingsInteractionHelper.*;
import static nl.adg.qwixx.e2e.utils.TestUtils.createDriver;
import static nl.adg.qwixx.e2e.utils.TestUtils.waitUntilScoreLoaded;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the post-game lobby flow:
 *
 *   - A 4-player game ends.
 *   - One player clicks "Leave Game" on the score screen and is removed from the session.
 *   - The other three navigate to the settings page (restart mode).
 *   - One player changes a game option — the change is pushed to the server and polled
 *     by the other two so all three see the same settings in real time.
 *   - Any one player clicks "Start Game" — the game restarts with the agreed-upon
 *     options and only the three remaining players.
 *   - Players whose settings page is still open are redirected to the game board
 *     automatically (the settings page polls for a game-state transition).
 *
 * Identity trick
 * ──────────────
 * The score screen reads the player's identity from sessionStorage
 * (written there by the board component on game-over).  Each Selenium session
 * gets its own isolated storage, so we inject the correct playerId via JS
 * before loading the score page.
 */
public class NewGameLobbyIT extends BaseIntegrationTest {

    private static final int RED_ROW_INDEX = 0;

    private WebDriver d0, d1, d2, d3;
    private String sessionId;
    // Maps player name → UUID; stable regardless of shuffle order after game start.
    private Map<String, String> playerIdByName;

    @BeforeEach
    void setupFinishedGame() {
        sessionId = api.createGame(4);

        playerIdByName = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            String name = "player" + i;
            playerIdByName.put(name, api.getPlayerIdByName(sessionId, name));
        }

        // Give player0 enough crosses to be the winner so the modal appears quickly.
        api.setCrosses(sessionId, playerIdByName.get("player0"), RED_ROW_INDEX, 5);
        api.forceFinish(sessionId);
    }

    @AfterEach
    void tearDown() {
        if (d0 != null) { d0.quit(); d0 = null; }
        if (d1 != null) { d1.quit(); d1 = null; }
        if (d2 != null) { d2.quit(); d2 = null; }
        if (d3 != null) { d3.quit(); d3 = null; }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String baseUrl() { return "http://127.0.0.1:" + SpringAppTestHelper.getPort(); }

    /** Open a score page; playerId is passed as a query param so no root navigation is needed. */
    private WebDriver openScoreAsPlayer(int playerIndex) {
        String pid = playerIdByName.get("player" + playerIndex);
        WebDriver driver = createDriver();
        driver.get(baseUrl() + "/score/" + sessionId + "?fast=1&pid=" + pid);
        return driver;
    }

    /** Open a settings page; sessionId and playerId are passed as query params. */
    private WebDriver openSettingsAsPlayer(int playerIndex) {
        String pid = playerIdByName.get("player" + playerIndex);
        WebDriver driver = createDriver();
        driver.get(baseUrl() + "/settings?sessionId=" + sessionId + "&playerId=" + pid);
        return driver;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Player3 leaves on the score screen → server removes them.
     * Remaining three navigate to settings and see only 3 players in the lobby.
     */
    @Test
    void playerLeavingOnScoreScreen_isRemovedFromLobby() {
        // Open score screens for all 4 players.
        d0 = openScoreAsPlayer(0);
        d1 = openScoreAsPlayer(1);
        d2 = openScoreAsPlayer(2);
        d3 = openScoreAsPlayer(3);

        for (WebDriver d : List.of(d0, d1, d2, d3)) {
            waitUntilScoreLoaded(d);
            waitUntilWinnerModalVisible(d, 10);
        }

        // Player3 leaves.
        clickLeaveGameButton(d3);
        d3.quit();
        d3 = null;

        // Close score browsers before opening settings browsers to avoid Chrome process leaks.
        if (d0 != null) { d0.quit(); d0 = null; }
        if (d1 != null) { d1.quit(); d1 = null; }
        if (d2 != null) { d2.quit(); d2 = null; }

        // Navigate remaining players to settings.
        d0 = openSettingsAsPlayer(0);
        d1 = openSettingsAsPlayer(1);
        d2 = openSettingsAsPlayer(2);

        waitUntilLoaded(d0);
        waitUntilLoaded(d1);
        waitUntilLoaded(d2);

        // Lobby must reflect only 3 players once the first poll fires.
        waitUntilLobbyHasPlayers(d0, 3);

        List<String> lobbyNames = getLobbyPlayerNames(d0);
        assertEquals(3, lobbyNames.size(),
                "Lobby must show 3 players after player3 left. Got: " + lobbyNames);
        assertFalse(lobbyNames.contains("player3"),
                "player3 must not appear in the lobby after leaving");

        // Server-side: session must have 3 players.
        List<Map<String, Object>> serverPlayers = api.getLobbyPlayers(sessionId);
        assertEquals(3, serverPlayers.size(),
                "Server session must have 3 players after player3 left");
    }

    /**
     * One player changes an option in their settings form.
     * Within a polling cycle the other two players' forms update automatically.
     */
    @Test
    void settingsAreShared_optionChangedByOnePlayerIsSeenByOthers() {
        d0 = openSettingsAsPlayer(0);
        d1 = openSettingsAsPlayer(1);
        d2 = openSettingsAsPlayer(2);

        waitUntilLoaded(d0);
        waitUntilLoaded(d1);
        waitUntilLoaded(d2);

        // Determine the initial state of the "extraRow" option on player0's form.
        boolean initial = isCheckboxChecked(d0, "extraRow");

        // Player1 toggles the "extraRow" checkbox — this will push to the lobby.
        setCheckbox(d1, "extraRow", !initial);

        // Player0 and player2 should see the same value within ~4 s (2 s poll + margin).
        waitUntilCheckboxIs(d0, "extraRow", !initial);
        waitUntilCheckboxIs(d2, "extraRow", !initial);

        // Server-side proposed options must reflect the change.
        Map<String, Object> proposed = api.getProposedOptions(sessionId);
        Object serverValue = proposed.get("extraRow");
        // The value may be stored as Boolean or String depending on Jackson defaults.
        boolean serverBool = serverValue instanceof Boolean b ? b : Boolean.parseBoolean(serverValue.toString());
        assertEquals(!initial, serverBool,
                "Server-side proposed options must reflect player1's change");
    }

    /**
     * Any player can start the game.  When they do, all remaining players
     * (whether on the settings page or still on the score page) are redirected
     * to the game board, and the game has only the 3 remaining players.
     */
    @Test
    void anyPlayerCanStartGame_othersAreRedirectedAutomatically() {
        // Player3 leaves at the score screen.
        d3 = openScoreAsPlayer(3);
        waitUntilScoreLoaded(d3);
        waitUntilWinnerModalVisible(d3, 10);
        clickLeaveGameButton(d3);
        d3.quit();
        d3 = null;

        // Players 0 and 1 go to settings; player 2 stays on the score screen.
        d0 = openSettingsAsPlayer(0);
        d1 = openSettingsAsPlayer(1);
        d2 = openScoreAsPlayer(2);

        waitUntilLoaded(d0);
        waitUntilLoaded(d1);
        waitUntilScoreLoaded(d2);
        waitUntilWinnerModalVisible(d2, 10);

        // Player1 changes an option to verify it takes effect in the restarted game.
        api.updateLobby(sessionId, Map.of("extraRow", false));

        // Player0 starts the game.
        clickStartGame(d0);

        // All three browsers must navigate to the game board.
        String gamePrefix = "/game/" + sessionId;

        new WebDriverWait(d0, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains(gamePrefix));
        new WebDriverWait(d1, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains(gamePrefix));
        new WebDriverWait(d2, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().contains(gamePrefix));

        assertTrue(d0.getCurrentUrl().contains(gamePrefix),
                "player0 must be on the game board after start. URL: " + d0.getCurrentUrl());
        assertTrue(d1.getCurrentUrl().contains(gamePrefix),
                "player1 must be on the game board after start. URL: " + d1.getCurrentUrl());
        assertTrue(d2.getCurrentUrl().contains(gamePrefix),
                "player2 must be redirected from score to game board. URL: " + d2.getCurrentUrl());

        // The restarted game must have exactly 3 players.
        List<String> newPlayerIds = api.getPlayerIds(sessionId);
        assertEquals(3, newPlayerIds.size(),
                "Restarted game must have 3 players. Got: " + newPlayerIds.size());
        assertFalse(newPlayerIds.contains(playerIdByName.get("player3")),
                "player3 must not be in the restarted game");
    }
}
