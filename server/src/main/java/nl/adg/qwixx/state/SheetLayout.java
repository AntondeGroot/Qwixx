package nl.adg.qwixx.state;

import java.util.List;
import nl.adg.qwixx.data.Row;

public record SheetLayout(List<Row> rows) {
    public SheetLayout {
        rows = List.copyOf(rows);
    }
}
