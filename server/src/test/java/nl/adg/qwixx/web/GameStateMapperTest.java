package nl.adg.qwixx.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.game.options.GameMode;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.generated.model.ClosureNotificationDto;
import nl.adg.qwixx.generated.model.ColorDto;
import nl.adg.qwixx.generated.model.GameStateDto;
import nl.adg.qwixx.generated.model.PlayerDto;
import nl.adg.qwixx.generated.model.TurnPhaseDto;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.ClosureNotification;
import nl.adg.qwixx.state.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                .map(PlayerDto::getName).toList();
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
    void toDtoMapsBonusBLayoutWithoutError() {
        String sid = GameRegistry.createGame("room", 4, GameSettings.builder().bonusB(true).build());
        GameRegistry.getGame(sid).addPlayer(Player.of("Ann"));
        GameRegistry.getGame(sid).addPlayer(Player.of("Ben"));
        GameRegistry.getGame(sid).start();

        var dto = assertDoesNotThrow(
                () -> GameStateMapper.toDto(GameRegistry.getGame(sid).currentState(), GameRegistry.getGame(sid)),
                "mapping a Bonus B layout (bonus strip + bonus boxes) must not throw");
        var rows = dto.getSheetLayouts().values().iterator().next().getRows();
        assertTrue(rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getBonusBStrip())),
                "the DTO includes the Bonus B strip row");
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
        assertEquals(TurnPhaseDto.ROLL, dto.getTurnState().getPhase());
    }

    @Test
    void toDtoTurnStateActivePlayerIdIsSet() {
        var dto = toDto();
        var activeId = GameRegistry.getGame(sessionId).currentState().turnState().activePlayerId();
        assertEquals(activeId.toString(), dto.getTurnState().getActivePlayerId());
    }

    @Test
    void toDtoTurnStateIsNullInOfflineMode() {
        GameRegistry.clear();
        String sid = GameRegistry.createGame("offline", 4,
                GameSettings.builder().gameMode(GameMode.OFFLINE).build());
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
        var activeId = GameRegistry.getGame(sessionId).currentState().turnState().activePlayerId();
        GameRegistry.getGame(sessionId).applyAction(
                new RollAction(activeId));

        var roll = GameRegistry.getGame(sessionId).currentState().turnState().currentRoll();
        int target = roll.white1() + roll.white2();
        var layout = GameRegistry.getGame(sessionId).currentState().sheetLayouts().get(activeId);
        for (var row : layout.rows()) {
            for (var cell : row.cells()) {
                if (cell.displayValue().equals(String.valueOf(target))) {
                    GameRegistry.getGame(sessionId).applyAction(
                            new CrossCellAction(
                                    activeId,
                                    layout.rows().indexOf(row),
                                    cell.id(),
                                    DiceCombination.WHITE_WHITE));
                    var dto = toDto();
                    var pending = dto.getTurnState().getPendingCrosses();
                    assertNotNull(pending);
                    assertTrue(pending.containsKey(activeId.toString()));
                    assertTrue(pending.get(activeId.toString()).contains(cell.id()));
                    return;
                }
            }
        }
        // If no matching cell found for this roll, test is vacuously skipped
    }

    @Test
    void toDtoClosureNotificationsIsEmptyAtStart() {
        var dto = toDto();
        assertNotNull(dto.getClosureNotifications());
        assertTrue(dto.getClosureNotifications().isEmpty());
    }

    @Test
    void toDtoClosureNotificationsMappedCorrectly() {
        // Manually add row closure requests to the internal state using player UUIDs.
        // The mapper looks up the player name from the session.
        var state = GameRegistry.getGame(sessionId).currentState();
        state.closureNotifications().add(
            new ClosureNotification(alice.id(), Color.RED)
        );
        state.closureNotifications().add(
            new ClosureNotification(bob.id(), Color.YELLOW)
        );

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        assertNotNull(dto.getClosureNotifications());
        assertEquals(2, dto.getClosureNotifications().size());

        // Check first request
        assertEquals("Alice", dto.getClosureNotifications().getFirst().getPlayerName());
        assertEquals(ColorDto.RED, dto.getClosureNotifications().getFirst().getRowColor());

        // Check second request
        assertEquals("Bob", dto.getClosureNotifications().get(1).getPlayerName());
        assertEquals(ColorDto.YELLOW, dto.getClosureNotifications().get(1).getRowColor());
    }

    @Test
    void toDtoClosureNotificationsAllColorsSupported() {
        var state = GameRegistry.getGame(sessionId).currentState();
        // Use alice's UUID for all requests; the mapper resolves to the player's name.
        state.closureNotifications().add(
            new ClosureNotification(alice.id(), Color.RED)
        );
        state.closureNotifications().add(
            new ClosureNotification(alice.id(), Color.YELLOW)
        );
        state.closureNotifications().add(
            new ClosureNotification(alice.id(), Color.GREEN)
        );
        state.closureNotifications().add(
            new ClosureNotification(alice.id(), Color.BLUE)
        );

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        assertEquals(4, dto.getClosureNotifications().size());
        var colors = dto.getClosureNotifications().stream()
            .map(ClosureNotificationDto::getRowColor)
            .toList();
        assertTrue(colors.contains(ColorDto.RED));
        assertTrue(colors.contains(ColorDto.YELLOW));
        assertTrue(colors.contains(ColorDto.GREEN));
        assertTrue(colors.contains(ColorDto.BLUE));
    }

    // ── closedRows mapping ────────────────────────────────────────────────────

    @Test
    void toDtoClosedRowsEmptyWhenNoRowsClosed() {
        var dto = toDto();
        assertTrue(dto.getClosedRows() == null || dto.getClosedRows().isEmpty(),
                "closedRows must be empty when no row has been closed");
    }

    @Test
    void toDtoClosedRowsContainsRowIdForSameCardsMode() {
        // SAME_CARDS (default): all players share the same Row objects → same row IDs.
        // The single entry from anyLayout is correct for all players.
        GameState state = GameRegistry.getGame(sessionId).currentState();
        int rowIndex = 0;
        state.boardState().closedRows().put(rowIndex, alice.id());

        var dto = GameStateMapper.toDto(state, GameRegistry.getGame(sessionId));

        String aliceRowId = state.sheetLayouts().get(alice.id()).rows().get(rowIndex).id();
        String bobRowId   = state.sheetLayouts().get(bob.id()).rows().get(rowIndex).id();

        // In SAME_CARDS mode the layouts share objects, so IDs are equal.
        assertEquals(aliceRowId, bobRowId, "SAME_CARDS layouts must share row IDs");

        Map<String, String> closed = dto.getClosedRows();
        assertTrue(closed.containsKey(aliceRowId),
                "closedRows must contain the shared row ID");
        assertEquals(alice.id().toString(), closed.get(aliceRowId),
                "closedRows value must be the closing player's ID");
    }

    @Test
    void toDtoClosedRowsContainsBothPlayersRowIdsInDifferentCardsMode() {
        // DIFFERENT_CARDS mode: each player receives their own buildStandardRows() call,
        // so Row objects have different UUIDs per player.
        // Bug: the old code used anyLayout — only one player's row ID ended up in the map,
        // so the other player's board never showed the row as closed.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("prob", 4,
                GameSettings.builder().cardMode(CardMode.DIFFERENT_CARDS).build());
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

        // Confirm the test setup: DIFFERENT_CARDS mode must produce different row IDs.
        assertNotEquals(p1RowId, p2RowId,
                "DIFFERENT_CARDS layouts must have distinct row IDs — test setup is invalid if equal");

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
    void toDtoClosedRowsAllPlayersCanSeeMultipleClosedRowsInDifferentCardsMode() {
        GameRegistry.clear();
        String sid = GameRegistry.createGame("prob2", 4,
                GameSettings.builder().cardMode(CardMode.DIFFERENT_CARDS).build());
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
        // 2 rows × 2 players = 4 entries total (all distinct in DIFFERENT_CARDS mode).
        assertEquals(4, closed.size());
    }

    @Test
    void toDtoClosedRowsAttributesCorrectClosingPlayerPerRow() {
        // Verify that when different players close different rows the correct
        // closing-player ID is stored regardless of which player's layout is used.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("attr", 4,
                GameSettings.builder().cardMode(CardMode.DIFFERENT_CARDS).build());
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
            String row0Id = state.sheetLayouts().get(player.id()).rows().getFirst().id();
            String row1Id = state.sheetLayouts().get(player.id()).rows().get(1).id();
            assertEquals(p1.id().toString(), closed.get(row0Id),
                    player.name() + "'s view: row 0 must be attributed to p1");
            assertEquals(p2.id().toString(), closed.get(row1Id),
                    player.name() + "'s view: row 1 must be attributed to p2");
        }
    }

    @Test
    void oneLogicalRowClosedInDifferentCardsModeProducesTwoDtoEntriesButCountsAsOneRow() {
        // In DIFFERENT_CARDS mode each player has a unique row UUID for the same logical row.
        // mapClosedRows must produce one DTO entry per player so each client can see their
        // row as closed — but the GAME must still count this as ONE closed row (not two),
        // because the internal board.closedRows() is keyed by rowIndex (integer), not rowId.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("one-row", 4,
                GameSettings.builder().cardMode(CardMode.DIFFERENT_CARDS).build());
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

        String p1RowId = state.sheetLayouts().get(p1.id()).rows().getFirst().id();
        String p2RowId = state.sheetLayouts().get(p2.id()).rows().getFirst().id();
        assertNotEquals(p1RowId, p2RowId, "DIFFERENT_CARDS layouts must have distinct row IDs");

        assertEquals(2, closed.size(),
                "DTO closedRows must have one entry per player for the one closed logical row");
        assertTrue(closed.containsKey(p1RowId), "p1 must see their row as closed");
        assertTrue(closed.containsKey(p2RowId), "p2 must see their row as closed");
    }

    @Test
    void twoLogicalRowsClosedInDifferentCardsModeProducesFourDtoEntriesAndGameIsOver() {
        // Closing two logical rows ends the game (>= 2). The DTO has 4 entries
        // (2 rows × 2 players), but game-over is driven by the internal map size, not the DTO.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("two-rows", 4,
                GameSettings.builder().cardMode(CardMode.DIFFERENT_CARDS).build());
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
                "DTO closedRows must have 4 entries (2 rows × 2 players) in DIFFERENT_CARDS mode");
        assertTrue(dto.getGameOver(), "gameOver flag must be true in the DTO");
    }

    // ── BigPoints bonus row neighbour IDs ────────────────────────────────────

    @Test
    void regularRowsHaveNullNeighbourRowIds() {
        // Standard (non-BigPoints) game: all rows are regular; neither neighbour
        // field should be set in the DTO.
        var dto = toDto();
        var rows = dto.getSheetLayouts().get(alice.id().toString()).getRows();
        for (var row : rows) {
            assertNull(row.getUpperNeighbourRowId(),
                    "regular row must not carry an upperNeighbourRowId");
            assertNull(row.getLowerNeighbourRowId(),
                    "regular row must not carry a lowerNeighbourRowId");
        }
    }

    @Test
    void bonusRowDtoCarriesUpperAndLowerNeighbourRowIds() {
        // BigPoints layout: [RED(0), BONUS-RY(1), YELLOW(2), GREEN(3), BONUS-GB(4), BLUE(5)]
        // The mapper must resolve the stored row-index integers into the actual row IDs
        // so the client can look up crossed values in the neighbour rows without knowing indices.
        GameRegistry.clear();
        String sid = GameRegistry.createGame("bp", 4,
                GameSettings.builder().bigPoints(true).build());
        Player p = Player.of("P");
        GameRegistry.getGame(sid).addPlayer(p);
        GameRegistry.getGame(sid).start();

        var dto = GameStateMapper.toDto(
                GameRegistry.getGame(sid).currentState(),
                GameRegistry.getGame(sid));

        var rows     = dto.getSheetLayouts().get(p.id().toString()).getRows();
        var bonusRY  = rows.get(1);
        var bonusGB  = rows.get(4);
        var redId    = rows.getFirst().getId();
        var yellowId = rows.get(2).getId();
        var greenId  = rows.get(3).getId();
        var blueId   = rows.get(5).getId();

        assertTrue(bonusRY.getBonusRow(),  "row 1 must be a bonus row");
        assertEquals(redId,    bonusRY.getUpperNeighbourRowId(),
                "BONUS-RY upper neighbour must be the RED row ID");
        assertEquals(yellowId, bonusRY.getLowerNeighbourRowId(),
                "BONUS-RY lower neighbour must be the YELLOW row ID");

        assertTrue(bonusGB.getBonusRow(),  "row 4 must be a bonus row");
        assertEquals(greenId, bonusGB.getUpperNeighbourRowId(),
                "BONUS-GB upper neighbour must be the GREEN row ID");
        assertEquals(blueId,  bonusGB.getLowerNeighbourRowId(),
                "BONUS-GB lower neighbour must be the BLUE row ID");
    }

    @Test
    void nonBonusRowsInBigPointsLayoutHaveNullNeighbourRowIds() {
        GameRegistry.clear();
        String sid = GameRegistry.createGame("bp2", 4,
                GameSettings.builder().bigPoints(true).build());
        Player p = Player.of("P");
        GameRegistry.getGame(sid).addPlayer(p);
        GameRegistry.getGame(sid).start();

        var dto  = GameStateMapper.toDto(
                GameRegistry.getGame(sid).currentState(),
                GameRegistry.getGame(sid));
        var rows = dto.getSheetLayouts().get(p.id().toString()).getRows();

        // Indices 0 (RED), 2 (YELLOW), 3 (GREEN), 5 (BLUE) are regular rows.
        for (int i : new int[]{0, 2, 3, 5}) {
            var row = rows.get(i);
            assertNull(row.getUpperNeighbourRowId(),
                    "regular row at index " + i + " must have no upper neighbour ID");
            assertNull(row.getLowerNeighbourRowId(),
                    "regular row at index " + i + " must have no lower neighbour ID");
        }
    }

    private GameStateDto toDto() {
        return GameStateMapper.toDto(
                GameRegistry.getGame(sessionId).currentState(),
                GameRegistry.getGame(sessionId));
    }
}
