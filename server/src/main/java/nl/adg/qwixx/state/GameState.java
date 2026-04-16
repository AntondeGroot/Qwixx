package nl.adg.qwixx.state;

import java.util.List;
import java.util.Map;
import java.util.UUID;

//  Top-level envelope. `SheetLayout` lives here as it is static; `BoardState` holds only what changes.
public class GameState {
    CardMode                    cardMode;
    List<UUID> players;        // ordered; defines turn order
//    VariantData                 variantData;    // opaque, variant-specific data
    Map<UUID, SheetLayout> sheetLayouts;   // static layout per player
    BoardState                  boardState;
    TurnState                   turnState;
    boolean                     gameOver;
    long                        version;        // increments on every applyAction
}
