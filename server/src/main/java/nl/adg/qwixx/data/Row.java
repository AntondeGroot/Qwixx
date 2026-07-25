package nl.adg.qwixx.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Fields are populated via setters after construction (see the game-style factory), not in the
// constructor, so NullAway can't prove initialization — they are set before any read.
@SuppressWarnings("NullAway.Init")
public class Row {
  private String id;
  private List<Cell> cells = new ArrayList<>();
  private LockCell lock;
  private boolean bonusRow;
  private boolean bonusBar;   // Bonus A: the row of coloured bonus-bar cells below the four colour rows
  private boolean bonusBStrip; // Bonus B: the row of 5 bonus-indicator cells below the four colour rows
  private int upperRowIndex   = -1;
  private int lowerRowIndex   = -1;
  private int luckyTarget;   // 0 = not a lucky row; >0 = target sum for the lucky move

  public Row(){
    id = UUID.randomUUID().toString();
  }

  public void addCell(Cell cell){
    cells.add(cell);
  }

  public void addLock(LockCell lock){
    this.lock = lock;
  }

  public void setBonusRow(int upperRowIndex, int lowerRowIndex) {
    this.bonusRow       = true;
    this.upperRowIndex  = upperRowIndex;
    this.lowerRowIndex  = lowerRowIndex;
  }

  public void setLuckyRow(int target) { this.luckyTarget = target; }

  public void setBonusBar() { this.bonusBar = true; }

  public void setBonusBStrip() { this.bonusBStrip = true; }

  public String id()              { return id; }
  public List<Cell> cells()       { return cells; }
  public LockCell lock()          { return lock; }
  /** True for the normal coloured number rows (the ones that can be locked/closed); false for the
   *  special rows without a lock — bonus rows, the bonus bar, x-change and lucky-number rows. */
  public boolean hasLock()        { return lock != null; }
  public boolean isBonusRow()     { return bonusRow; }
  public boolean isBonusBar()     { return bonusBar; }
  public boolean isBonusBStrip()  { return bonusBStrip; }
  public int upperRowIndex()      { return upperRowIndex; }
  public int lowerRowIndex()      { return lowerRowIndex; }
  public boolean isLuckyRow()     { return luckyTarget > 0; }
  public int luckyTarget()        { return luckyTarget; }
}
