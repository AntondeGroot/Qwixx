package nl.adg.qwixx.data;

import java.util.List;
import java.util.UUID;

// Fields are populated by the game-style factory via setters after construction, not in the
// constructor, so NullAway can't prove they're initialized — they are before any read.
@SuppressWarnings("NullAway.Init")
public class Cell {
  String        id;
  int           position;          // ordinal index in the row (engine uses this, not displayValue)
  String        displayValue;      // shown to the player ("2".."12", "2".."16", etc.)
  Color         color;             // the die color that can target this cell; also its primary scoring bucket
  List<CellTag> tags;              // zero or more behavioral/scoring modifiers
  boolean       isClosingEligible; // can this cell satisfy the lock's requiredCells? (e.g. 12, or 15/16 in longo)

  public Cell(int position){
    this.id = UUID.randomUUID().toString();
    this.position = position;
  }

  public void setColor(Color color){
    this.color = color;
  }

  public void setDisplayValue(String displayValue){
    this.displayValue = displayValue;
  }

  public void setClosingEligible(boolean isClosingEligible){
    this.isClosingEligible = isClosingEligible;
  }

  public void setTags(List<CellTag> tags){
    this.tags = tags;
  }

  public String id()           { return id; }
  public int position()        { return position; }
  public String displayValue() { return displayValue; }
  public Color color()         { return color; }
  public List<CellTag> tags()  { return tags; }
  public boolean isClosingEligible() { return isClosingEligible; }
}
