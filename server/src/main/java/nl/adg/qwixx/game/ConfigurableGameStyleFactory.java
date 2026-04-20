package nl.adg.qwixx.game;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.LongoTurnRules;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.StandardScoringEngine;
import nl.adg.qwixx.rules.StandardTurnRules;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.CardMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
        if (settings.cardMode() == CardMode.DETERMINISTIC) {
            List<Row> shared = buildStandardRows();
            for (UUID player : players) {
                result.put(player, shared);
            }
        } else {
            for (UUID player : players) {
                result.put(player, buildStandardRows());
            }
        }
        return result;
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
                    int first, second;
                    do {
                        first  = pool[random.nextInt(pool.length)];
                        second = pool[random.nextInt(pool.length)];
                    } while ((first == 7 && second == 11) || (first == 11 && second == 7));
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