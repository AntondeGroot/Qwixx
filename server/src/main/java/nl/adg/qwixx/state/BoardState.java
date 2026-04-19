package nl.adg.qwixx.state;

import nl.adg.qwixx.data.Die;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BoardState {
    Map<UUID, SheetProgress> sheetProgress;  // crossing progress per player
    List<Die>                activeDice;     // shrinks as rows are locked (color die removed)
    Map<Integer, UUID>       closedRows;     // rowIndex → player who closed it

    public BoardState(Map<UUID, SheetProgress> sheetProgress, List<Die> activeDice, Map<Integer, UUID> closedRows) {
        this.sheetProgress = sheetProgress;
        this.activeDice    = activeDice;
        this.closedRows    = closedRows;
    }

    public Map<UUID, SheetProgress> sheetProgress() { return sheetProgress; }
    public List<Die> activeDice()                    { return activeDice; }
    public Map<Integer, UUID> closedRows()           { return closedRows; }
}