package nl.adg.qwixx.state;

import java.util.Map;

public class SheetProgress {
  Map<Integer, RowState> rowStates;
  int punishments;

  public SheetProgress(Map<Integer, RowState> rowStates, int punishments) {
    this.rowStates = rowStates;
    this.punishments = punishments;
  }

  public Map<Integer, RowState> rowStates()  { return rowStates; }
  public int punishments()                   { return punishments; }

  public void updateRowState(int rowIndex, RowState state) { rowStates.put(rowIndex, state); }
  public void addPunishment()                              { punishments++; }
}
