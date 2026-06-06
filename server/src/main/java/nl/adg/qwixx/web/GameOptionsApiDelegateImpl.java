package nl.adg.qwixx.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.QwixxGameOptions;
import nl.adg.qwixx.generated.api.GameOptionsApiDelegate;
import nl.adg.qwixx.generated.model.GameOption;
import nl.adg.qwixx.generated.model.SheetLayout;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GameOptionsApiDelegateImpl implements GameOptionsApiDelegate {

    @Override
    public ResponseEntity<List<GameOption>> getGameOptions() {
        List<GameOption> options = QwixxGameOptions.all().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(options);
    }

    @Override
    public ResponseEntity<SheetLayout> previewLayout(Map<String, Object> requestBody) {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, requestBody);
        GameSettings settings = builder.build();
        ConfigurableGameStyleFactory factory = new ConfigurableGameStyleFactory(settings);
        UUID dummy = UUID.randomUUID();
        List<Row> rows = factory.buildRows(List.of(dummy)).get(dummy);
        var layout = new nl.adg.qwixx.state.SheetLayout(rows);
        return ResponseEntity.ok(GameStateMapper.toSheetLayoutDto(layout));
    }

    private GameOption toDto(nl.adg.qwixx.game.GameOption o) {
        GameOption.TypeEnum typeEnum = switch (o.type()) {
            case BOOLEAN -> GameOption.TypeEnum.BOOLEAN;
            case INTEGER -> GameOption.TypeEnum.INTEGER;
            case ENUM    -> GameOption.TypeEnum.ENUM;
        };
        GameOption dto = new GameOption(
                o.key(),
                o.labelKey(),
                typeEnum,
                o.defaultValue());
        dto.setDescriptionKey(o.descriptionKey());
        if (!o.choices().isEmpty()) dto.setChoices(o.choices());
        if (o.minValue() != null) dto.setMinValue(o.minValue());
        if (o.maxValue() != null) dto.setMaxValue(o.maxValue());
        if (o.adminOnly()) dto.setAdminOnly(true);
        if (!o.incompatibleWith().isEmpty()) dto.setIncompatibleWith(o.incompatibleWith());
        return dto;
    }
}
