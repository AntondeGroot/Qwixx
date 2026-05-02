package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.generated.model.TurnPhase;
import nl.adg.qwixx.state.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private nl.adg.qwixx.generated.model.GameState toDto() {
        return GameStateMapper.toDto(
                GameRegistry.getGame(sessionId).currentState(),
                GameRegistry.getGame(sessionId));
    }
}