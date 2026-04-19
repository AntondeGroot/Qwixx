package nl.adg.qwixx.data;

import java.util.List;

public class LockCell {
  private String id;
  private Color color;
  private int minCrosses;
  private List<String> requiredCells;

  public LockCell(String id, Color color, int minCrosses, List<String> requiredCells) {
    this.id = id;
    this.color = color;
    this.minCrosses = minCrosses;
    this.requiredCells = requiredCells;
  }

  public String id()                  { return id; }
  public Color color()                { return color; }
  public int minCrosses()             { return minCrosses; }
  public List<String> requiredCells() { return requiredCells; }
}
