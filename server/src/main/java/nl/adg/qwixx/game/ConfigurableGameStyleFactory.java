package nl.adg.qwixx.game;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.LongoTurnRules;
import nl.adg.qwixx.rules.OfflineTurnRules;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.StandardScoringEngine;
import nl.adg.qwixx.rules.StandardTurnRules;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.CardMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

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
        // extraRow is always per-player (each player gets an independently drawn bounce offset)
        boolean perPlayer = settings.cardMode() == CardMode.PROBABILISTIC || settings.extraRow();
        if (!perPlayer) {
            List<Row> shared = buildStandardRows();
            if (settings.randomOrder()) shuffleDisplayValues(shared);
            if (settings.connectedCells()) applyConnectedCells(shared);
            for (UUID player : players) result.put(player, shared);
        } else {
            for (UUID player : players) {
                List<Row> playerRows = buildStandardRows();
                if (settings.randomOrder()) shuffleDisplayValues(playerRows);
                if (settings.extraRow()) applyExtraRow(playerRows);
                if (settings.connectedCells()) applyConnectedCells(playerRows);
                result.put(player, playerRows);
            }
        }
        return result;
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
            List<String> values = new ArrayList<>(row.cells().stream().map(Cell::displayValue).toList());
            Collections.shuffle(values, random);
            List<Cell> cells = row.cells();
            for (int i = 0; i < cells.size(); i++) {
                cells.get(i).setDisplayValue(values.get(i));
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
        return new StandardScoringEngine();
    }

    @Override
    public VariantData buildVariantData(List<UUID> playerIds) {
        return switch (settings.base()) {
            case STANDARD -> null;
            case LONGO -> {
                int[] pool = {5, 6, 7, 11, 12, 13};
                Map<UUID, List<Integer>> perPlayer = new HashMap<>();
                for (UUID id : playerIds) {
                    int first;
                    int second;
                    do {
                        first  = pool[random.nextInt(pool.length)];
                        second = pool[random.nextInt(pool.length)];
                    } while ((first == 7 && second == 11) || (first == 11 && second == 7) || first == second);

                    if (first > second) {
                        int tmp = first;
                        first = second;
                        second = tmp;
                    }

                    perPlayer.put(id, List.of(first, second));
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
            case STANDARD -> 5;
            case LONGO    -> 6;
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

        int[] prevPair = null;
        for (int pair = 0; pair < rows.size() - 1; pair++) {
            List<Integer> shuffled = new ArrayList<>(validPositions);
            Collections.shuffle(shuffled, random);
            int[] chosen = pickPairPositions(shuffled, prevPair);

            Row rowA = rows.get(pair);
            Row rowB = rows.get(pair + 1);
            for (int pos : chosen) {
                Cell cellA = rowA.cells().get(pos);
                Cell cellB = rowB.cells().get(pos);
                addAutoTag(cellA, cellB.id());
                addAutoTag(cellB, cellA.id());
            }
            prevPair = chosen;
        }
    }

    // Picks 2 positions satisfying: intra-pair diff >= 3 and each >= 2 from every prevPair position.
    private int[] pickPairPositions(List<Integer> shuffled, int[] prevPair) {
        for (int i = 0; i < shuffled.size(); i++) {
            for (int j = i + 1; j < shuffled.size(); j++) {
                int p1 = shuffled.get(i);
                int p2 = shuffled.get(j);
                if (Math.abs(p1 - p2) < 3) continue;
                if (prevPair != null) {
                    boolean ok = true;
                    for (int prev : prevPair) {
                        if (Math.abs(p1 - prev) < 2 || Math.abs(p2 - prev) < 2) { ok = false; break; }
                    }
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