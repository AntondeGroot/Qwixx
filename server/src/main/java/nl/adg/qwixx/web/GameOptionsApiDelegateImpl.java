package nl.adg.qwixx.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.factory.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.game.options.QwixxGameOptions;
import nl.adg.qwixx.generated.api.GameOptionsApiDelegate;
import nl.adg.qwixx.generated.model.GameOption;
import nl.adg.qwixx.generated.model.SheetLayout;
import nl.adg.qwixx.state.LongoVariantData;
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
        // Deterministic factory + a fixed player id so a given option set always previews the same
        // sheet — no reshuffling on each change, and reproducible option-preview images.
        ConfigurableGameStyleFactory factory = ConfigurableGameStyleFactory.deterministic(settings);
        UUID dummy = new UUID(0L, 0L);
        List<Row> rows = Objects.requireNonNull(factory.buildRows(List.of(dummy)).get(dummy));
        var layout = new nl.adg.qwixx.state.SheetLayout(rows);
        SheetLayout dto = GameStateMapper.toSheetLayoutDto(layout);
        // Longo also shows two bonus-number star chips; surface them so the preview can render them.
        if (factory.buildVariantData(List.of(dummy)) instanceof LongoVariantData longo) {
            dto.setBonusNumbers(longo.bonusNumbersPerPlayer().get(dummy));
        }
        return ResponseEntity.ok(dto);
    }

    private GameOption toDto(nl.adg.qwixx.game.options.GameOption o) {
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
        dto.setCategory(GameOption.CategoryEnum.fromValue(o.category().name()));
        if (!o.choices().isEmpty()) dto.setChoices(o.choices());
        if (o.minValue() != null) dto.setMinValue(o.minValue());
        if (o.maxValue() != null) dto.setMaxValue(o.maxValue());
        if (o.adminOnly()) dto.setAdminOnly(true);
        if (!o.incompatibleWith().isEmpty()) dto.setIncompatibleWith(o.incompatibleWith());
        return dto;
    }
}
