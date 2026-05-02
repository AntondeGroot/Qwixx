package nl.adg.qwixx.testapp;

import nl.adg.qwixx.QwixxApplication;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.state.RowState;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test application that creates a persistent test game session on startup.
 *
 * Game Setup:
 * - 2 players (player0 and player1)
 * - Both players have 5 crosses in BLUE row
 * - Dice rolled: white dice predetermined to 1+1
 * - player0 is active
 *
 * Run with: mvn spring-boot:run -Dspring-boot.run.main-class=nl.adg.qwixx.testapp.QwixxTestApplication
 *
 * The sessionId and player IDs will be printed on startup.
 */
@Component
@Profile("!e2e")
public class QwixxTestApplication {

	public static void main(String[] args) {
		// Run the main Qwixx application with this component active
		SpringApplication.run(QwixxApplication.class, args);
	}

	@EventListener
	public void onApplicationEvent(ContextRefreshedEvent event) {
		// Only initialize test game once
		if (GameRegistry.getAllGames().size() > 0) {
			return;
		}

		System.out.println("\n╔═══════════════════════════════════════════════════════════════╗");
		System.out.println("║           QWIXX TEST APPLICATION - CREATING TEST GAME           ║");
		System.out.println("╚═══════════════════════════════════════════════════════════════╝\n");

		// Create game with 2 players
		String sessionId = GameRegistry.createGame("test-room", 2, GameSettings.builder().build());
		var game = GameRegistry.getGame(sessionId);

		Player player0 = Player.of("player0");
		Player player1 = Player.of("player1");
		game.addPlayer(player0);
		game.addPlayer(player1);

		// Start the game - this sets status to IN_PROGRESS
		game.start();

		// Verify status is IN_PROGRESS
		if (game.status() != nl.adg.qwixx.game.SessionStatus.IN_PROGRESS) {
			throw new RuntimeException("Game status should be IN_PROGRESS but is " + game.status());
		}

		// Refresh state after starting
		var state = game.currentState();

		// Set up: both players have 5 crosses in BLUE row (rowIndex 3)
		for (Player p : new Player[]{player0, player1}) {
			var layout = state.sheetLayouts().get(p.id());
			var progress = state.boardState().sheetProgress().get(p.id());
			var blueRow = layout.rows().get(3);  // BLUE is rowIndex 3

			// Add 5 crosses in BLUE row (cells at positions 0-4)
			Set<String> blueCrosses = new HashSet<>();
			for (int i = 0; i < 5; i++) {
				blueCrosses.add(blueRow.cells().get(i).id());
			}
			progress.updateRowState(3, new RowState(blueCrosses, false));
		}

		// Roll the dice to make the game active
		var rollAction = new RollAction(state.turnState().activePlayerId());
		game.applyAction(rollAction);

		// Refresh to get the latest state
		state = game.currentState();

		// Override dice roll to be predetermined 1+1
		var afterRoll = game.currentState();
		var originalRoll = afterRoll.turnState().currentRoll();
		var predetermined = new RollResult(1, 1, originalRoll.coloredDice());
		afterRoll.turnState().setCurrentRoll(predetermined);

		System.out.println("✓ Test game created successfully!\n");
		System.out.println("TEST GAME DETAILS:");
		System.out.println("─────────────────────────────────────────");
		System.out.println("  Session ID:     " + sessionId);
		System.out.println("  Player 0 ID:    " + player0.id());
		System.out.println("  Player 1 ID:    " + player1.id());
		System.out.println("  Active Player:  player0");
		System.out.println("  White Dice:     1 + 1 = 2 (predetermined)");
		System.out.println("  Both players:   5 crosses in BLUE row");
		System.out.println("  Game Phase:     " + afterRoll.turnState().phase());
		System.out.println("─────────────────────────────────────────\n");

		System.out.println("Server is running with the test game available:");
		System.out.println("  Player0: http://localhost:4200/?sessionid=" + sessionId + "&playerid=" + player0.id());
		System.out.println("  Player1: http://localhost:4200/?sessionid=" + sessionId + "&playerid=" + player1.id() + "\n");
	}
}
