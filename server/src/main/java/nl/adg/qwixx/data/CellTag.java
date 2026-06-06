package nl.adg.qwixx.data;

public sealed interface CellTag permits CellTag.AutoCross, CellTag.ExtraBucket, CellTag.BonusPoints, CellTag.DoubleCross, CellTag.SecondaryColor, CellTag.XChange, CellTag.LuckyNumber, CellTag.LuckyCross {
  record AutoCross(String target)    implements CellTag {}  // rule-time: auto-crosses target when this cell is crossed
  record ExtraBucket()               implements CellTag {}  // score-time: cell also contributes +1 to the EXTRA scoring bucket
  record BonusPoints(int amount)     implements CellTag {}  // score-time: crossing awards flat bonus points
  record DoubleCross()               implements CellTag {}  // score-time: cell counts twice in its primary color bucket
  record SecondaryColor(Color color) implements CellTag {}  // score-time: Big Points bonus cell also scores toward this second color
  record XChange(int a, int b)       implements CellTag {}  // rule-time: white+white sum matching either value allows crossing
  record LuckyNumber(int bonusPoints) implements CellTag {} // score-time: awards flat bonus points on cross (active-only; gate condition lives on Row.luckyTarget)
  record LuckyCross()                implements CellTag {}  // rule-time: can only be crossed when a 1+5 combo appears on any two dice
}
