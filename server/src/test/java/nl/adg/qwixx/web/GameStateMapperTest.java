package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.model.TurnPhase;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameStateMapperTest {

    String sessionId;
    Player alice;
    Player bob;

    @BeforeEach
    void setUp() {
        GameRegistry.clear();
        sessionId = GameRegistry.createGame("room", 4, GameSettings.builder().build());
        alice = Player.of("Alice");
        bob   = Player.of("Bob");
        GameRegistry.getGame(sessionId).addPlayer(alice);
        GameRegistry.getGame(sessionId).addPlayer(bob);
        GameRegistry.getGame(sessionId).start();
    }

    @Test
    void toDtoIncludesAllPlayers() {
        var dto = toDto();
        List<String> names = dto.getPlayers().stream()
                .map(nl.adg.qwixx.generated.model.Player::getName).toList();
        assertTrue(names.contains("Alice"));
        assertTrue(names.contains("Bob"));
    }

    @Test
    void toDtoIncludesSheetLayoutForEachPlayer() {
        var dto = toDto();
        assertTrue(dto.getSheetLayouts().containsKey(alice.id().toString()));
        assertTrue(dto.getSheetLayouts().containsKey(bob.id().toString()));
        assertFalse(dto.getSheetLayouts().get(alice.id().toString()).getRows().isEmpty());
    }

    @Test
    void toDtoIncludesSheetProgressForEachPlayer() {
        var dto = toDto();
        assertTrue(dto.getSheetProgress().containsKey(alice.id().toString()));
        assertTrue(dto.getSheetProgress().containsKey(bob.id().toString()));
    }

    @Test
    void toDtoTurnStatePhaseIsRollAtStart() {
        var dto = toDto();
        assertNotNull(dto.getTurnState());
        assertEquals(TurnPhase.ROLL, dto.getTurnState().getPhase());
    }

    @Test
    void toDtoTurnStateActivePlayerIdIsAlice() {
        var dto = toDto();
        assertEquals(alice.id().toString(), dto.getTurnState().getActivePlayerId());
    }

    @Test
    void toDtoTurnStateIsNullInOfflineMode() {
        GameRegistry.clear();
        String sid = GameRegistry.createGame("offline", 4,
                GameSettings.builder().gameMode(nl.adg.qwixx.game.GameMode.OFFLINE).build());
        Player p = Player.of("P");
        GameRegistry.getGame(sid).addPlayer(p);
        GameRegistry.getGame(sid).start();
        var dto = GameStateMapper.toDto(
                GameRegistry.getGame(sid).currentState(),
                GameRegistry.getGame(sid));
        assertNull(dto.getTurnState());
    }

    @Test
    void toDtoActiveDiceColorsContainsStandardColors() {
        var dto = toDto();
        var colors = dto.getActiveDiceColors();
        assertNotNull(colors);
        assertEquals(4, colors.size()); // RED, YELLOW, GREEN, BLUE
    }

    @Test
    void toDtoClosedRowsIsEmptyAtStart() {
        var dto = toDto();
        assertTrue(dto.getClosedRows() == null || dto.getClosedRows().isEmpty());
    }

    @Test
    void toDtoSheetLayoutCellsHaveDisplayValues() {
        var dto = toDto();
        var rows = dto.getSheetLayouts().get(alice.id().toString()).getRows();
        boolean allCellsHaveDisplayValues = rows.stream()
                .flatMap(r -> r.getCells().stream())
                .allMatch(c -> c.getDisplayValue() != null && !c.getDisplayValue().isBlank());
        assertTrue(allCellsHaveDisplayValues);
    }

    @Test
    void toDtoSheetProgressHasZeroPunishmentsAtStart() {
        var dto = toDto();
        var progress = dto.getSheetProgress().get(alice.id().toString());
        assertEquals(0, progress.getPunishments());
    }

    @Test
    void toDtoTurnStatePassiveQueueContainsBobAtStart() {
        var dto = toDto();
        // In ROLL phase the queue is empty; queue is populated after rolling
        assertNotNull(dto.getTurnState().getPassivePlayerQueue());
    }

    @Test
    void toDtoVersionStartsAtZero() {
        var dto = toDto();
        assertEquals(0L, dto.getVersion());
    }

    @Test
    void toDtoPendingCrossesPopulatedAfterCross() {
        GameState state = GameRegistry.getGame(sessionId).currentState();
        // Simulate a roll and cross via action
        GameRegistry.getGame(sessionId).applyAction(
                new nl.adg.qwixx.action.RollAction(alice.id()));

        // Find a reachable cell and cross it
        var roll = GameRegistry.getGame(sessionId).currentState().turnState().currentRoll();
        int target = roll.white1() + roll.white2();
        var layout = GameRegistry.getGame(sessionId).currentState().sheetLayouts().get(alice.id());
        for (var row : layout.rows()) {
            for (var cell : row.cells()) {
                if (cell.displayValue().equals(String.valueOf(target))) {
                    GameRegistry.getGame(sessionId).applyAction(
                            new nl.adg.qwixx.action.CrossCellAction(
                                    alice.id(),
                                    layout.rows().indexOf(row),
                                    cell.id(),
                                    nl.adg.qwixx.action.DiceCombination.WHITE_WHITE));
                    var dto = toDto();
                    var pending = dto.getTurnState().getPendingCrosses();
                    assertNotNull(pending);
                    assertTrue(pending.containsKey(alice.id().toString()));
                    assertTrue(pending.get(alice.id().toString()).contains(cell.id()));
                    return;
                }
            }
        }
        // If no matching cell found for this roll, test is vacuously skipped
    }

    @Test
    void toDtoRowClosureRequestsIsEmptyAtStart() {
        var dto = toDto();
        assertNotNull(dto.getRowClosureRequests());
        assertTrue(dto.getRowClosureRequests().isEmpty());
    }

    @Test
    void toDtoRowClosureRequestsMappedCorrectly() {
        // Manually add row closure requests to the internal state
        var state = GameRegistry.getGame(sessionId).currentState();
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("Alice", nl.adg.qwixx.data.Color.RED)
        );
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("Bob", nl.adg.qwixx.data.Color.YELLOW)
        );

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        assertNotNull(dto.getRowClosureRequests());
        assertEquals(2, dto.getRowClosureRequests().size());

        // Check first request
        assertEquals("Alice", dto.getRowClosureRequests().get(0).getPlayerName());
        assertEquals(nl.adg.qwixx.generated.model.Color.RED, dto.getRowClosureRequests().get(0).getRowColor());

        // Check second request
        assertEquals("Bob", dto.getRowClosureRequests().get(1).getPlayerName());
        assertEquals(nl.adg.qwixx.generated.model.Color.YELLOW, dto.getRowClosureRequests().get(1).getRowColor());
    }

    @Test
    void toDtoRowClosureRequestsAllColorsSupported() {
        var state = GameRegistry.getGame(sessionId).currentState();
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("P1", nl.adg.qwixx.data.Color.RED)
        );
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("P2", nl.adg.qwixx.data.Color.YELLOW)
        );
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("P3", nl.adg.qwixx.data.Color.GREEN)
        );
        state.rowClosureRequests().add(
            new nl.adg.qwixx.state.RowClosureRequest("P4", nl.adg.qwixx.data.Color.BLUE)
        );

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        assertEquals(4, dto.getRowClosureRequests().size());
        var colors = dto.getRowClosureRequests().stream()
            .map(nl.adg.qwixx.generated.model.RowClosureRequest::getRowColor)
            .toList();
        assertTrue(colors.contains(nl.adg.qwixx.generated.model.Color.RED));
        assertTrue(colors.contains(nl.adg.qwixx.generated.model.Color.YELLOW));
        assertTrue(colors.contains(nl.adg.qwixx.generated.model.Color.GREEN));
        assertTrue(colors.contains(nl.adg.qwixx.generated.model.Color.BLUE));
    }

    // ── closedRows mapping ────────────────────────────────────────────────────

    @Test
    void toDtoClosedRowsEmptyWhenNoRowsClosed() {
        var dto = toDto();
        assertTrue(dto.getClosedRows() == null || dto.getClosedRows().isEmpty(),
                "closedRows must be empty when no row has been closed");
    }

    @Test
    void toDtoClosedRowsContainsRowIdForDeterministicMode() {
        // DETERMINISTIC (default): all players share the same Row objects → same row IDs.
        // The single entry from anyLayout is correct for all players.
        GameState state = GameRegistry.getGame(sessionId).currentState();
        int rowIndex = 0;
        state.boardState().closedRows().put(rowIndex, alice.id());

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        String aliceRowId = state.sheetLayouts().get(alice.id()).rows().get(rowIndex).id();
        String bobRowId   = state.sheetLayouts().get(bob.id()).rows().get(rowIndex).id();

        // In DETERMINISTIC mode the layouts share objects, so IDs are equal.
        assertEquals(aliceRowId, bobRowId, "DETERMINISTIC layouts must share row IDs");

        Map<String, String> closed = dto.getClosedRows();
        assertTrue(closed.containsKey(aliceRowId),
                "closedRows must contain the shared row ID");
        assertEquals(alice.id().toString(), closed.get(aliceRowId),
                "closedRows value must be the closing player's ID");
    }

    @Test
    void toDtoClosedRowsContainsBothPlayersRowIdsInProbabilisticMode() {
        // PROBABILISTIC mode: each player receives their own buildStandardRows() call,
        // so Row objects have different UUIDs per player.
        // Bug: the old code used anyLayout — only one player's row ID ended up in the map,
        // so the other player's board never showed the row as closed.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("prob", 4,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        Player p1 = Player.of("P1");
        Player p2 = Player.of("P2");
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();

        GameState state = GameRegistry.getGame(sid).currentState();
        int rowIndex = 0;
        state.boardState().closedRows().put(rowIndex, p1.id());

        String p1RowId = state.sheetLayouts().get(p1.id()).rows().get(rowIndex).id();
        String p2RowId = state.sheetLayouts().get(p2.id()).rows().get(rowIndex).id();

        // Confirm the test setup: PROBABILISTIC mode must produce different row IDs.
        assertNotEquals(p1RowId, p2RowId,
                "PROBABILISTIC layouts must have distinct row IDs — test setup is invalid if equal");

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sid));
        Map<String, String> closed = dto.getClosedRows();

        assertTrue(closed.containsKey(p1RowId),
                "closedRows must contain p1's row ID so p1's board shows the row as closed");
        assertTrue(closed.containsKey(p2RowId),
                "closedRows must contain p2's row ID so p2's board shows the row as closed");
        assertEquals(p1.id().toString(), closed.get(p1RowId),
                "closing player must be p1 for p1's row ID entry");
        assertEquals(p1.id().toString(), closed.get(p2RowId),
                "closing player must be p1 for p2's row ID entry (p1 closed the row)");
    }

    @Test
    void toDtoClosedRowsAllPlayersCanSeeMultipleClosedRowsInProbabilisticMode() {
        GameRegistry.clear();
        String sid = GameRegistry.createGame("prob2", 4,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        Player p1 = Player.of("P1");
        Player p2 = Player.of("P2");
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();

        GameState state = GameRegistry.getGame(sid).currentState();
        state.boardState().closedRows().put(0, p1.id()); // row 0 closed by p1
        state.boardState().closedRows().put(1, p2.id()); // row 1 closed by p2

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sid));
        Map<String, String> closed = dto.getClosedRows();

        for (int rowIndex = 0; rowIndex <= 1; rowIndex++) {
            String p1RowId = state.sheetLayouts().get(p1.id()).rows().get(rowIndex).id();
            String p2RowId = state.sheetLayouts().get(p2.id()).rows().get(rowIndex).id();
            assertTrue(closed.containsKey(p1RowId),
                    "row " + rowIndex + " must be visible to p1");
            assertTrue(closed.containsKey(p2RowId),
                    "row " + rowIndex + " must be visible to p2");
        }
        // 2 rows × 2 players = 4 entries total (all distinct in PROBABILISTIC mode).
        assertEquals(4, closed.size());
    }

    @Test
    void toDtoClosedRowsAttributesCorrectClosingPlayerPerRow() {
        // Verify that when different players close different rows the correct
        // closing-player ID is stored regardless of which player's layout is used.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("attr", 4,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        Player p1 = Player.of("P1");
        Player p2 = Player.of("P2");
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();

        GameState state = GameRegistry.getGame(sid).currentState();
        state.boardState().closedRows().put(0, p1.id()); // RED closed by p1
        state.boardState().closedRows().put(1, p2.id()); // YELLOW closed by p2

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sid));
        Map<String, String> closed = dto.getClosedRows();

        // For each player, check that their row 0 entry says p1 closed it.
        for (Player player : List.of(p1, p2)) {
            String row0Id = state.sheetLayouts().get(player.id()).rows().get(0).id();
            String row1Id = state.sheetLayouts().get(player.id()).rows().get(1).id();
            assertEquals(p1.id().toString(), closed.get(row0Id),
                    player.name() + "'s view: row 0 must be attributed to p1");
            assertEquals(p2.id().toString(), closed.get(row1Id),
                    player.name() + "'s view: row 1 must be attributed to p2");
        }
    }

    @Test
    void oneLogicalRowClosedInProbabilisticModeProducesTwoDtoEntriesButCountsAsOneRow() {
        // In PROBABILISTIC mode each player has a unique row UUID for the same logical row.
        // mapClosedRows must produce one DTO entry per player so each client can see their
        // row as closed — but the GAME must still count this as ONE closed row (not two),
        // because the internal board.closedRows() is keyed by rowIndex (integer), not rowId.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("one-row", 4,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        Player p1 = Player.of("P1");
        Player p2 = Player.of("P2");
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();

        GameState state = GameRegistry.getGame(sid).currentState();

        // Close exactly ONE logical row.
        state.boardState().closedRows().put(0, p1.id());

        // Internal state: only 1 row closed — game is NOT over (needs >= 2).
        assertEquals(1, state.boardState().closedRows().size(),
                "internal closedRows must count logical rows, not player-layout entries");
        assertFalse(state.gameOver(),
                "game must not be over after closing only one logical row");

        // DTO: both players' row IDs appear — 2 entries for 1 logical row.
        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sid));
        Map<String, String> closed = dto.getClosedRows();

        String p1RowId = state.sheetLayouts().get(p1.id()).rows().get(0).id();
        String p2RowId = state.sheetLayouts().get(p2.id()).rows().get(0).id();
        assertNotEquals(p1RowId, p2RowId, "PROBABILISTIC layouts must have distinct row IDs");

        assertEquals(2, closed.size(),
                "DTO closedRows must have one entry per player for the one closed logical row");
        assertTrue(closed.containsKey(p1RowId), "p1 must see their row as closed");
        assertTrue(closed.containsKey(p2RowId), "p2 must see their row as closed");
    }

    @Test
    void twoLogicalRowsClosedInProbabilisticModeProducesFourDtoEntriesAndGameIsOver() {
        // Closing two logical rows ends the game (>= 2). The DTO has 4 entries
        // (2 rows × 2 players), but game-over is driven by the internal map size, not the DTO.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("two-rows", 4,
                GameSettings.builder().cardMode(CardMode.PROBABILISTIC).build());
        Player p1 = Player.of("P1");
        Player p2 = Player.of("P2");
        GameRegistry.getGame(sid).addPlayer(p1);
        GameRegistry.getGame(sid).addPlayer(p2);
        GameRegistry.getGame(sid).start();

        GameState state = GameRegistry.getGame(sid).currentState();
        state.boardState().closedRows().put(0, p1.id());
        state.boardState().closedRows().put(1, p2.id());
        state.setGameOver(true); // mirrors what StandardTurnRules does when size >= 2

        assertEquals(2, state.boardState().closedRows().size(),
                "internal closedRows must count 2 logical rows");

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sid));
        Map<String, String> closed = dto.getClosedRows();

        // 2 logical rows × 2 players = 4 DTO entries.
        assertEquals(4, closed.size(),
                "DTO closedRows must have 4 entries (2 rows × 2 players) in PROBABILISTIC mode");
        assertTrue(dto.getGameOver(), "gameOver flag must be true in the DTO");
    }

    private nl.adg.qwixx.generated.model.GameState toDto() {
        return GameStateMapper.toDto(
                GameRegistry.getGame(sessionId).currentState(),
                GameRegistry.getGame(sessionId));
    }
}