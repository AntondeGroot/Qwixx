package nl.adg.qwixx.state;

public class ActiveTurnState {
  boolean whiteWhiteUsed;
  boolean colorDieUsed;
  boolean luckyNumberUsed;

  public void setWhiteWhiteUsed()   { whiteWhiteUsed = true; }
  public void setColorDieUsed()     { colorDieUsed = true; }
  public void setLuckyNumberUsed()  { luckyNumberUsed = true; }

  public void reset()               { whiteWhiteUsed = false; colorDieUsed = false; luckyNumberUsed = false; }

  public boolean whiteWhiteUsed()   { return whiteWhiteUsed; }
  public boolean colorDieUsed()     { return colorDieUsed; }
  public boolean luckyNumberUsed()  { return luckyNumberUsed; }

  public boolean hasActed()         { return whiteWhiteUsed || colorDieUsed || luckyNumberUsed; }
}