package nl.adg.qwixx.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.BigPointsScoringEngine;
import nl.adg.qwixx.rules.LongoTurnRules;
import nl.adg.qwixx.rules.OfflineTurnRules;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.StandardScoringEngine;
import nl.adg.qwixx.rules.StandardTurnRules;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.CardMode;

public class ConfigurableGameStyleFactory implements GameStyleFactory {

    private final GameSettings settings;
    private final Random       random;

    public ConfigurableGameStyleFactory(GameSettings settings) {
        this(settings, new Random());
    }

    ConfigurableGameStyleFactory(GameSettings settings, Random random) {
        this.settings = settings;
        this.random   = random;
    }

    @Override
    public GameSettings settings() {
        return settings;
    }

    @Override
    public Map<UUID, List<Row>> buildRows(List<UUID> players) {
        Map<UUID, List<Row>> result = new HashMap<>();

        if (settings.bigPoints()) {
            // Big Points: two bonus rows interleaved between RED-YELLOW and GREEN-BLUE.
            // Bonus rows are shared (no per-player randomisation needed for them).
            // The four coloured rows follow the normal per-player/shared logic.
            boolean perPlayer = settings.cardMode() == CardMode.PROBABILISTIC || settings.extraRow();
            for (UUID player : players) {
                List<Row> colored = perPlayer ? buildStandardRows() : null;
                if (colored == null && result.isEmpty()) colored = buildStandardRows();
                if (colored == null) colored = buildStandardRows(); // each player gets own copy
                if (settings.randomOrder()) shuffleDisplayValues(colored);
                if (settings.extraRow()) applyExtraRow(colored);
                if (settings.connectedCells()) applyConnectedCells(colored);
                result.put(player, interleaveWithBonusRows(colored));
            }
        } else {
            // extraRow is always per-player (each player gets an independently drawn bounce offset)
            boolean perPlayer = settings.cardMode() == CardMode.PROBABILISTIC || settings.extraRow();
            if (!perPlayer) {
                List<Row> shared = settings.luckyCross()
                        ? buildStandardRowsWithLuckyCross(0)
                        : buildStandardRows();
                if (settings.randomOrder()) shuffleDisplayValues(shared);
                if (settings.connectedCells()) applyConnectedCells(shared);
                applyDoubleVariants(shared);
                if (settings.xChange()) shared.add(buildXChangeRow());
                if (settings.luckyNumber()) shared.add(buildLuckyRow());
                for (UUID player : players) result.put(player, shared);
                return result;
            } else {
                int[] gaps = luckyCrossGaps();
                for (UUID player : players) {
                    List<Row> playerRows = settings.luckyCross()
                            ? buildStandardRowsWithLuckyCross(random.nextInt(gaps.length))
                            : buildStandardRows();
                    if (settings.randomOrder()) shuffleDisplayValues(playerRows);
                    if (settings.extraRow()) applyExtraRow(playerRows);
                    if (settings.connectedCells()) applyConnectedCells(playerRows);
                    applyDoubleVariants(playerRows);
                    result.put(player, playerRows);
                }
            }
        }

        if (settings.xChange()) {
            // X-Change row is appended after all per-player rows are built.
            Row xChangeRow = buildXChangeRow();
            for (UUID player : players) result.get(player).add(xChangeRow);
        }

        if (settings.luckyNumber()) {
            // Lucky Number row is shared and appended last.
            Row luckyRow = buildLuckyRow();
            for (UUID player : players) result.get(player).add(luckyRow);
        }

        return result;
    }

    /**
     * Takes the 4 standard coloured rows [RED, YELLOW, GREEN, BLUE] and returns a 6-row
     * list with bonus rows inserted: [RED(0), BONUS-RY(1), YELLOW(2), GREEN(3), BONUS-GB(4), BLUE(5)].
     */
    private List<Row> interleaveWithBonusRows(List<Row> colored) {
        Row red    = colored.get(0);
        Row yellow = colored.get(1);
        Row green  = colored.get(2);
        Row blue   = colored.get(3);

        Row bonusRY = buildBonusRow(Color.RED,   Color.YELLOW, true);   // ascending  2..max
        Row bonusGB = buildBonusRow(Color.GREEN,  Color.BLUE,  false);  // descending max..2

        List<Row> rows = new ArrayList<>();
        rows.add(red);      // index 0
        rows.add(bonusRY);  // index 1
        rows.add(yellow);   // index 2
        rows.add(green);    // index 3
        rows.add(bonusGB);  // index 4
        rows.add(blue);     // index 5

        bonusRY.setBonusRow(0, 2);  // neighbours: red(0) and yellow(2)
        bonusGB.setBonusRow(3, 5);  // neighbours: green(3) and blue(5)

        return rows;
    }

    /** Builds a bonus row with split-color cells (primary = upperColor, secondary = lowerColor). */
    private Row buildBonusRow(Color upperColor, Color lowerColor, boolean ascending) {
        int max = maxDisplayValue();
        Row row = new Row();
        for (int i = 0; i <= max - 2; i++) {
            Cell cell = new Cell(i);
            cell.setColor(upperColor);
            cell.setDisplayValue(ascending ? String.valueOf(i + 2) : String.valueOf(max - i));
            cell.setTags(List.of(new CellTag.SecondaryColor(lowerColor)));
            cell.setClosingEligible(false);
            row.addCell(cell);
        }
        // No lock — bonus rows cannot be closed.
        return row;
    }

    private static final int[] BOUNCE = {0, 1, 2, 3, 2, 1};

    private void applyExtraRow(List<Row> rows) {
        int startOffset = random.nextInt(BOUNCE.length);
        int numCols = rows.get(0).cells().size();
        for (int col = 0; col < numCols; col++) {
            int rowIndex = BOUNCE[(startOffset + col) % BOUNCE.length];
            Cell cell = rows.get(rowIndex).cells().get(col);
            List<CellTag> tags = new ArrayList<>(cell.tags());
            tags.add(new CellTag.ExtraBucket());
            cell.setTags(tags);
        }
    }

    private void shuffleDisplayValues(List<Row> rows) {
        for (Row row : rows) {
            List<Cell> normalCells = row.cells().stream()
                    .filter(c -> c.tags().stream().noneMatch(t -> t instanceof CellTag.LuckyCross))
                    .toList();
            List<String> values = new ArrayList<>(normalCells.stream().map(Cell::displayValue).toList());
            Collections.shuffle(values, random);
            for (int i = 0; i < normalCells.size(); i++) {
                normalCells.get(i).setDisplayValue(values.get(i));
            }
        }
    }

    @Override
    public List<Die> buildDice() {
        int faces = diceFaces();
        return new ArrayList<>(List.of(
            new Die(Color.WHITE,  faces),
            new Die(Color.WHITE,  faces),
            new Die(Color.RED,    faces),
            new Die(Color.YELLOW, faces),
            new Die(Color.GREEN,  faces),
            new Die(Color.BLUE,   faces)
        ));
    }

    @Override
    public TurnRules buildTurnRules() {
        if (settings.gameMode() == GameMode.OFFLINE) return new OfflineTurnRules();
        return switch (settings.base()) {
            case STANDARD -> new StandardTurnRules();
            case LONGO    -> new LongoTurnRules();
        };
    }

    @Override
    public ScoringEngine buildScoringEngine() {
        if (settings.bigPoints()) return new BigPointsScoringEngine(bigPointsScoreCap());
        return new StandardScoringEngine();
    }

    // Standard: 11 cells + 4 bonus = 15  → triangular(15) = 120 (matches the official rule)
    // Longo:    15 cells + 4 bonus = 19  → triangular(19) = 190
    private int bigPointsScoreCap() {
        return switch (settings.base()) {
            case STANDARD -> 15;
            case LONGO    -> 19;
        };
    }

    @Override
    public VariantData buildVariantData(List<UUID> playerIds) {
        return switch (settings.base()) {
            case STANDARD -> null;
            case LONGO -> {
                int[] pool = {5, 6, 7, 11, 12, 13};
                Map<UUID, List<Integer>> perPlayer = new HashMap<>();
                Set<List<Integer>> used = new HashSet<>();
                for (UUID id : playerIds) {
                    int first, second;
                    do {
                        first  = pool[random.nextInt(pool.length)];
                        second = pool[random.nextInt(pool.length)];
                        if (first > second) { int tmp = first; first = second; second = tmp; }
                    } while (first == second
                            || (first == 7 && second == 11)
                            || used.contains(List.of(first, second)));

                    List<Integer> pair = List.of(first, second);
                    used.add(pair);
                    perPlayer.put(id, pair);
                }
                yield new LongoVariantData(perPlayer);
            }
        };
    }

    private int maxDisplayValue() {
        return switch (settings.base()) {
            case STANDARD -> 12;
            case LONGO    -> 16;
        };
    }

    private int diceFaces() {
        return switch (settings.base()) {
            case STANDARD -> 6;
            case LONGO    -> 8;
        };
    }

    private List<Row> buildStandardRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(buildAscendingRow(Color.RED));
        rows.add(buildAscendingRow(Color.YELLOW));
        rows.add(buildDescendingRow(Color.GREEN));
        rows.add(buildDescendingRow(Color.BLUE));
        return rows;
    }

    private int lockMinCrosses() {
        return switch (settings.base()) {
            case STANDARD -> 6;
            case LONGO    -> 7;
        };
    }

    // Builds a row with displayValues 2..maxDisplayValue (left to right)
    private Row buildAscendingRow(Color color) {
        int max = maxDisplayValue();
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i <= max - 2; i++) {
            Cell cell = new Cell(i);
            cell.setColor(color);
            cell.setDisplayValue(String.valueOf(i + 2));
            cell.setTags(List.of());
            row.addCell(cell);
            cells.add(cell);
        }
        row.addLock(buildLock(color, cells));
        return row;
    }

    // Builds a row with displayValues maxDisplayValue..2 (left to right)
    private Row buildDescendingRow(Color color) {
        int max = maxDisplayValue();
        Row row = new Row();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i <= max - 2; i++) {
            Cell cell = new Cell(i);
            cell.setColor(color);
            cell.setDisplayValue(String.valueOf(max - i));
            cell.setTags(List.of());
            row.addCell(cell);
            cells.add(cell);
        }
        row.addLock(buildLock(color, cells));
        return row;
    }

    // Standard: lock values 2 and 12 are excluded → pairs use 3–11
    private static final int[][] X_CHANGE_PAIRS_STANDARD = {
        {8, 5}, {9, 7}, {11, 3}, {7, 4}, {10, 3}, {8, 6}, {10, 5}, {11, 9}, {6, 4}
    };

    // Longo: lock values 2, 3 (low) and 15, 16 (high) are excluded → pairs use 4–14
    private static final int[][] X_CHANGE_PAIRS_LONGO = {
        {11, 6}, {12, 9}, {14, 4}, {10, 7}, {9, 5}, {13, 4}, {11, 7}, {13, 6}, {12, 8}, {14, 12}, {7, 5}
    };

    private Row buildXChangeRow() {
        int[][] pairs = switch (settings.base()) {
            case STANDARD -> X_CHANGE_PAIRS_STANDARD;
            case LONGO    -> X_CHANGE_PAIRS_LONGO;
        };
        Row row = new Row();
        for (int i = 0; i < pairs.length; i++) {
            Cell cell = new Cell(i);
            cell.setColor(Color.BLUE);
            cell.setDisplayValue("");
            cell.setTags(List.of(new CellTag.XChange(pairs[i][0], pairs[i][1])));
            cell.setClosingEligible(false);
            row.addCell(cell);
        }
        // No lock — x-change rows cannot be closed.
        return row;
    }

    // ── Lucky Cross ───────────────────────────────────────────────────────────

    // Gap pattern: number of normal cells before each lucky cross field, then after the last one.
    // Standard [2,3,4,2] → 11 normals + 3 crosses; Longo [2,3,4,4,2] → 15 normals + 4 crosses.
    private static final int[] LUCKY_CROSS_GAPS_STANDARD = {2, 3, 4, 2};
    private static final int[] LUCKY_CROSS_GAPS_LONGO    = {2, 3, 4, 4, 2};

    private int[] luckyCrossGaps() {
        return settings.base() == BaseVariant.LONGO ? LUCKY_CROSS_GAPS_LONGO : LUCKY_CROSS_GAPS_STANDARD;
    }

    /**
     * Builds all four coloured rows with lucky cross fields interleaved.
     * Row i gets a cyclic shift of (baseShift + i) positions in the gap pattern so each
     * row has its cross fields at different positions. baseShift = 0 for deterministic mode,
     * a random value for probabilistic mode.
     */
    private List<Row> buildStandardRowsWithLuckyCross(int baseShift) {
        int[] gaps = luckyCrossGaps();
        List<Row> rows = new ArrayList<>();
        rows.add(buildAscendingRowWithLuckyCross(Color.RED,    shiftedGaps(gaps, baseShift)));
        rows.add(buildAscendingRowWithLuckyCross(Color.YELLOW, shiftedGaps(gaps, baseShift + 1)));
        rows.add(buildDescendingRowWithLuckyCross(Color.GREEN, shiftedGaps(gaps, baseShift + 2)));
        rows.add(buildDescendingRowWithLuckyCross(Color.BLUE,  shiftedGaps(gaps, baseShift + 3)));
        return rows;
    }

    private static int[] shiftedGaps(int[] base, int shift) {
        int n = base.length;
        int s = ((shift % n) + n) % n;
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = base[(i + s) % n];
        return result;
    }

    // Builds ascending row (values 2..max) with lucky cross fields at gap-defined positions.
    private Row buildAscendingRowWithLuckyCross(Color color, int[] gaps) {
        Row row = new Row();
        List<Cell> normalCells = new ArrayList<>();
        int pos = 0, displayVal = 2;
        for (int g = 0; g < gaps.length; g++) {
            for (int n = 0; n < gaps[g]; n++) {
                Cell cell = new Cell(pos);
                pos++;
                cell.setColor(color);
                cell.setDisplayValue(String.valueOf(displayVal));
                displayVal++;
                cell.setTags(List.of());
                row.addCell(cell);
                normalCells.add(cell);
            }
            if (g < gaps.length - 1) {
                row.addCell(buildLuckyCrossCell(color, pos));
                pos++;
            }
        }
        row.addLock(buildLock(color, normalCells));
        return row;
    }

    // Builds descending row (values max..2) with lucky cross fields at gap-defined positions.
    private Row buildDescendingRowWithLuckyCross(Color color, int[] gaps) {
        int max = maxDisplayValue();
        Row row = new Row();
        List<Cell> normalCells = new ArrayList<>();
        int pos = 0, displayVal = max;
        for (int g = 0; g < gaps.length; g++) {
            for (int n = 0; n < gaps[g]; n++) {
                Cell cell = new Cell(pos);
                pos++;
                cell.setColor(color);
                cell.setDisplayValue(String.valueOf(displayVal));
                displayVal--;
                cell.setTags(List.of());
                row.addCell(cell);
                normalCells.add(cell);
            }
            if (g < gaps.length - 1) {
                row.addCell(buildLuckyCrossCell(color, pos));
                pos++;
            }
        }
        row.addLock(buildLock(color, normalCells));
        return row;
    }

    private static Cell buildLuckyCrossCell(Color color, int position) {
        Cell cell = new Cell(position);
        cell.setColor(color);
        cell.setDisplayValue("");
        cell.setTags(List.of(new CellTag.LuckyCross()));
        cell.setClosingEligible(false);
        return cell;
    }

    /** Lucky row: 4 orange diamond cells worth 5 / 6 / 7 / 8 points each (max 26 total). */
    private static final int[] LUCKY_BONUS_POINTS = {5, 6, 7, 8};

    /** Lucky-move target sum: 15 for Standard, 20 for Longo. */
    private static final int LUCKY_TARGET_STANDARD = 13;
    private static final int LUCKY_TARGET_LONGO    = 18;

    private Row buildLuckyRow() {
        int target = settings.base() == BaseVariant.LONGO ? LUCKY_TARGET_LONGO : LUCKY_TARGET_STANDARD;
        Row row = new Row();
        for (int i = 0; i < LUCKY_BONUS_POINTS.length; i++) {
            int pts = LUCKY_BONUS_POINTS[i];
            Cell cell = new Cell(i);
            cell.setColor(Color.BLUE);
            cell.setDisplayValue(String.valueOf(pts));
            cell.setTags(List.of(new CellTag.LuckyNumber(pts)));
            cell.setClosingEligible(false);
            row.addCell(cell);
        }
        row.setLuckyRow(target);
        return row;
    }

    private void applyConnectedCells(List<Row> rows) {
        Set<Integer> forbidden = new HashSet<>();
        forbidden.add(0);
        for (Row row : rows) {
            for (Cell cell : row.cells()) {
                if (cell.isClosingEligible()) forbidden.add(cell.position());
            }
        }

        List<Integer> validPositions = new ArrayList<>();
        for (Cell cell : rows.get(0).cells()) {
            if (!forbidden.contains(cell.position())) validPositions.add(cell.position());
        }

        List<int[]> allPairs;
        do {
            allPairs = new ArrayList<>();
            int[] prevPair = null;
            for (int pair = 0; pair < rows.size() - 1; pair++) {
                List<Integer> shuffled = new ArrayList<>(validPositions);
                Collections.shuffle(shuffled, random);
                int[] chosen = pickPairPositions(shuffled, prevPair);
                allPairs.add(chosen);
                prevPair = chosen;
            }
        } while (!hasNoChainedConnections(allPairs));

        for (int pair = 0; pair < rows.size() - 1; pair++) {
            Row rowA = rows.get(pair);
            Row rowB = rows.get(pair + 1);
            for (int pos : allPairs.get(pair)) {
                Cell cellA = rowA.cells().get(pos);
                Cell cellB = rowB.cells().get(pos);
                addAutoTag(cellA, cellB.id());
                addAutoTag(cellB, cellA.id());
            }
        }
    }

    private boolean hasNoChainedConnections(List<int[]> allPairs) {
        for (int i = 0; i < allPairs.size() - 1; i++) {
            Set<Integer> current = new HashSet<>();
            for (int p : allPairs.get(i)) current.add(p);
            for (int p : allPairs.get(i + 1)) {
                if (current.contains(p)) return false;
            }
        }
        return true;
    }

    // Picks 2 positions satisfying: intra-pair diff >= 3 and each >= 2 from every prevPair position.
    private int[] pickPairPositions(List<Integer> shuffled, int[] prevPair) {
        for (int i = 0; i < shuffled.size(); i++) {
            for (int j = i + 1; j < shuffled.size(); j++) {
                int p1 = shuffled.get(i);
                int p2 = shuffled.get(j);
                if (Math.abs(p1 - p2) < 3) continue;
                if (prevPair != null) {
                    boolean ok = Arrays.stream(prevPair)
                            .noneMatch(prev -> Math.abs(p1 - prev) < 2 || Math.abs(p2 - prev) < 2);
                    if (!ok) continue;
                }
                return new int[]{p1, p2};
            }
        }
        // Fallback: relax inter-pair constraint, keep only intra-pair >= 3
        for (int i = 0; i < shuffled.size(); i++) {
            for (int j = i + 1; j < shuffled.size(); j++) {
                if (Math.abs(shuffled.get(i) - shuffled.get(j)) >= 3) {
                    return new int[]{shuffled.get(i), shuffled.get(j)};
                }
            }
        }
        throw new IllegalStateException("Cannot place connected cell pair from positions: " + shuffled);
    }

    private void addAutoTag(Cell cell, String targetId) {
        List<CellTag> tags = new ArrayList<>(cell.tags());
        tags.add(new CellTag.AutoCross(targetId));
        cell.setTags(tags);
    }

    /**
     * Double A / Double B: attach a "twin" mark to selected coloured cells. Double A twins every
     * non-closing cell; Double B twins a fixed subset (see {@link #doubleBPositions}). Each twin
     * shares its primary's colour and value, lives in the same {@link Row}, and is crossable only
     * after its primary is crossed and nothing to the primary's right is crossed yet (enforced in
     * {@code CellCrosser} via the {@link CellTag.DoubleTwin} tag). Twins share the primary's
     * position so a crossed twin never advances the row's left-to-right progression.
     */
    private void applyDoubleVariants(List<Row> rows) {
        if (!settings.doubleA() && !settings.doubleB()) return;
        Set<Integer> bPositions = doubleBPositions();
        for (Row row : rows) {
            // Snapshot the primaries first — we append twins to the same cell list below.
            List<Cell> primaries = new ArrayList<>(row.cells());
            for (Cell primary : primaries) {
                if (primary.isClosingEligible()) continue; // the closing cell's twin is unreachable
                if (settings.doubleB() && !bPositions.contains(primary.position())) continue;
                row.addCell(buildTwin(primary));
            }
        }
    }

    private Cell buildTwin(Cell primary) {
        Cell twin = new Cell(primary.position());
        twin.setColor(primary.color());
        twin.setDisplayValue(primary.displayValue());
        twin.setClosingEligible(false);
        twin.setTags(List.of(new CellTag.DoubleTwin(primary.id())));
        return twin;
    }

    // Fixed columns that gain a twin under Double B (avoid position 0 and the closing cells).
    private Set<Integer> doubleBPositions() {
        if (!settings.doubleB()) return Set.of();
        return switch (settings.base()) {
            case STANDARD -> Set.of(2, 5, 8);
            case LONGO    -> Set.of(3, 7, 11);
        };
    }

    private LockCell buildLock(Color color, List<Cell> cells) {
        int minCrosses = lockMinCrosses();
        if (settings.base() == BaseVariant.LONGO) {
            Cell second = cells.get(cells.size() - 2);
            Cell last   = cells.get(cells.size() - 1);
            second.setClosingEligible(true);
            last.setClosingEligible(true);
            return new LockCell(UUID.randomUUID().toString(), color, minCrosses,
                    List.of(second.id(), last.id()));
        }
        Cell last = cells.get(cells.size() - 1);
        last.setClosingEligible(true);
        return new LockCell(UUID.randomUUID().toString(), color, minCrosses, List.of(last.id()));
    }
}
