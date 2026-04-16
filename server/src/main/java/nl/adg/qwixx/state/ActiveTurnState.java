package nl.adg.qwixx.state;

public class ActiveTurnState {
  boolean whiteWhiteUsed;
  boolean colorDieUsed;

  public void setWhiteWhiteUsed(){
    whiteWhiteUsed = true;
  }

  public void setColorDieUsed(){
    colorDieUsed = true;
  }

  public void reset(){
    whiteWhiteUsed = false;
    colorDieUsed = false;
  }

}
