package nl.adg.qwixx.action;

import java.util.UUID;

/** Offline-only: remove a single previously crossed cell (an accidental cross). If the cell had
 *  closed/locked the row, the row reopens. Auto-crosses the original cross triggered are left as-is. */
public record UncrossCellAction(UUID playerId, int rowIndex, String cellId) implements GameAction {}
