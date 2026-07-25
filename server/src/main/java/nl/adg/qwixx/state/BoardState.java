package nl.adg.qwixx.state;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.adg.qwixx.data.Die;

/**
 * The board state for a single game: each player's crossing progress, the dice still in play, and
 * which rows have been closed.
 *
 * @param sheetProgress crossing progress per player
 * @param activeDice    shrinks as rows are locked (color die removed)
 * @param closedRows    rowIndex → player who closed it
 */
public record BoardState(Map<UUID, SheetProgress> sheetProgress, List<Die> activeDice,
                         Map<Integer, UUID> closedRows) {

}
