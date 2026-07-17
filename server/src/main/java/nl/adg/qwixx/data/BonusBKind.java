package nl.adg.qwixx.data;

/**
 * The five Bonus B bonus types. Each appears as a matched pair of boxes in the coloured rows;
 * crossing both boxes of a kind triggers its effect and marks the kind's cell in the bonus strip.
 */
public enum BonusBKind {
    FEWEST_TWO,    // immediate: cross the next 2 boxes in your fewest-crossed row
    ONE_EACH,      // immediate: cross the next box in every coloured row
    DOUBLE_FEWEST, // score-time: double the points of your fewest-crossed row
    PLUS_13,       // score-time: +13 flat points
    NO_PENALTY     // score-time: mis-rolls no longer subtract points
}
