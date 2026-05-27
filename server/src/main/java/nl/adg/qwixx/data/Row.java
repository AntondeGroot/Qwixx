package nl.adg.qwixx.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Row {
  private String id;
  private List<Cell> cells = new ArrayList<>();
  private LockCell lock;
  private boolean bonusRow    = false;
  private int upperRowIndex   = -1;
  private int lowerRowIndex   = -1;
  private int luckyTarget     = 0;   // 0 = not a lucky row; >0 = target sum for the lucky move

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

  public String id()              { return id; }
  public List<Cell> cells()       { return cells; }
  public LockCell lock()          { return lock; }
  public boolean isBonusRow()     { return bonusRow; }
  public int upperRowIndex()      { return upperRowIndex; }
  public int lowerRowIndex()      { return lowerRowIndex; }
  public boolean isLuckyRow()     { return luckyTarget > 0; }
  public int luckyTarget()        { return luckyTarget; }
}