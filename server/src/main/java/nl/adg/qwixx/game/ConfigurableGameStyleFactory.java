package nl.adg.qwixx.game;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.StandardTurnRules;
import nl.adg.qwixx.rules.StandardScoringEngine;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.CardMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConfigurableGameStyleFactory implements GameStyleFactory {

    private final GameSettings settings;

    public ConfigurableGameStyleFactory(GameSettings settings) {
        this.settings = settings;
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
        return new StandardTurnRules();
    }

    @Override
    public ScoringEngine buildScoringEngine() {
        return new StandardScoringEngine();
    }

    @Override
    public VariantData buildVariantData() {
        return null;
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

    // Builds a row with displayValues 2..maxDisplayValue (left to right)
    private Row buildAscendingRow(Color color) {
        int max = maxDisplayValue();
        Row row = new Row();
        Cell lastCell = null;
        for (int i = 0; i <= max - 2; i++) {
            Cell cell = new Cell(i);
            cell.setColor(color);
            cell.setDisplayValue(String.valueOf(i + 2));
            cell.setTags(List.of());
            row.addCell(cell);
            lastCell = cell;
        }
        lastCell.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(lastCell.id())));
        return row;
    }

    // Builds a row with displayValues maxDisplayValue..2 (left to right)
    private Row buildDescendingRow(Color color) {
        int max = maxDisplayValue();
        Row row = new Row();
        Cell lastCell = null;
        for (int i = 0; i <= max - 2; i++) {
            Cell cell = new Cell(i);
            cell.setColor(color);
            cell.setDisplayValue(String.valueOf(max - i));
            cell.setTags(List.of());
            row.addCell(cell);
            lastCell = cell;
        }
        lastCell.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(lastCell.id())));
        return row;
    }
}