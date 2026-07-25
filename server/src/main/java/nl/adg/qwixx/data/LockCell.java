package nl.adg.qwixx.data;

import java.util.List;

public class LockCell {
  private final String id;
  private final Color color;
  private final int minCrosses;
  private final List<String> closingCells;

  public LockCell(String id, Color color, int minCrosses, List<String> closingCells) {
    this.id = id;
    this.color = color;
    this.minCrosses = minCrosses;
    this.closingCells = List.copyOf(closingCells);
  }

  public String id()                 { return id; }
  public Color color()               { return color; }
  public int minCrosses()            { return minCrosses; }
  public List<String> closingCells() { return closingCells; }
}
