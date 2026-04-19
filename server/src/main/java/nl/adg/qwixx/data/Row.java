package nl.adg.qwixx.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Row {
  private String id;
  private List<Cell> cells = new ArrayList<>();
  private LockCell lock;

  public Row(){
    id = UUID.randomUUID().toString();
  }

  public void addCell(Cell cell){
    cells.add(cell);
  }

  public void addLock(LockCell lock){
    this.lock = lock;
  }

  public String id()          { return id; }
  public List<Cell> cells()   { return cells; }
  public LockCell lock()      { return lock; }
}
