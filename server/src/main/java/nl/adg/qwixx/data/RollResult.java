package nl.adg.qwixx.data;

import java.util.Map;

public class RollResult {
  private int white1;
  private int white2;
  private Map<Color, Integer> coloredDice;

  public RollResult(int white1, int white2, Map<Color, Integer> coloredDice){
    this.white1 = white1;
    this.white2 = white2;
    this.coloredDice = coloredDice;
  }
}
