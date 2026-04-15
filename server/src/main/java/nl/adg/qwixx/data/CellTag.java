package nl.adg.qwixx.data;

public sealed interface CellTag permits CellTag.AutoCross, CellTag.ExtraBucket, CellTag.BonusPoints, CellTag.DoubleCross {
  record AutoCross(String target) implements CellTag {}  // rule-time: auto-crosses target when this cell is crossed
  record ExtraBucket()            implements CellTag {}  // score-time: cell also contributes +1 to the EXTRA scoring bucket
  record BonusPoints(int amount)  implements CellTag {}  // score-time: crossing awards flat bonus points
  record DoubleCross()            implements CellTag {}  // score-time: cell counts twice in its primary color bucket
}