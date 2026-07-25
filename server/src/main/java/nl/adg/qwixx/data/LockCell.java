package nl.adg.qwixx.data;

import java.util.List;

public record LockCell(String id, Color color, int minCrosses, List<String> closingCells) {

  public LockCell(String id, Color color, int minCrosses, List<String> closingCells) {
    this.id = id;
    this.color = color;
    this.minCrosses = minCrosses;
    this.closingCells = List.copyOf(closingCells);
  }
}
