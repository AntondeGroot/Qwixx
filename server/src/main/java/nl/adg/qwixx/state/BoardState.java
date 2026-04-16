package nl.adg.qwixx.state;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.adg.qwixx.data.Die;

public class BoardState {
  Map<UUID, SheetProgress> sheetProgress;  // crossing progress per player
  List<Die> activeDice;     // shrinks as rows are locked (color die removed)
  Map<Integer, UUID>         closedRows;     // rowId → player who closed it
}
