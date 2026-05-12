package nl.adg.qwixx.data;

import java.util.List;

public class LockCell {
  private String id;
  private Color color;
  private int minCrosses;
  private List<String> closingCells;

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
