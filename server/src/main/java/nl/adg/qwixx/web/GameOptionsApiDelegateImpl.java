package nl.adg.qwixx.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.factory.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.options.GameOption;
import nl.adg.qwixx.game.options.GameOptionCatalog;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.generated.api.GameOptionsApiDelegate;
import nl.adg.qwixx.generated.model.GameOptionDto;
import nl.adg.qwixx.generated.model.SheetLayoutDto;
import nl.adg.qwixx.state.LongoVariantData;
import nl.adg.qwixx.state.SheetLayout;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GameOptionsApiDelegateImpl implements GameOptionsApiDelegate {

    @Override
    public ResponseEntity<List<GameOptionDto>> getGameOptions() {
        List<GameOptionDto> options = GameOptionCatalog.all().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(options);
    }

    @Override
    public ResponseEntity<SheetLayoutDto> previewLayout(Map<String, Object> requestBody) {
        GameSettings.Builder builder = GameSettings.builder();
        GameOptionCatalog.apply(builder, requestBody);
        GameSettings settings = builder.build();
        // Deterministic factory + a fixed player id so a given option set always previews the same
        // sheet — no reshuffling on each change, and reproducible option-preview images.
        ConfigurableGameStyleFactory factory = ConfigurableGameStyleFactory.deterministic(settings);
        UUID dummy = new UUID(0L, 0L);
        List<Row> rows = Objects.requireNonNull(factory.buildRows(List.of(dummy)).get(dummy));
        var layout = new SheetLayout(rows);
        SheetLayoutDto dto = GameStateMapper.toSheetLayoutDto(layout);
        // Longo also shows two bonus-number star chips; surface them so the preview can render them.
        if (factory.buildVariantData(List.of(dummy)) instanceof LongoVariantData longo) {
            dto.setBonusNumbers(longo.bonusNumbersPerPlayer().get(dummy));
        }
        return ResponseEntity.ok(dto);
    }

    private GameOptionDto toDto(GameOption o) {
        GameOptionDto.TypeEnum typeEnum = switch (o.type()) {
            case BOOLEAN -> GameOptionDto.TypeEnum.BOOLEAN;
            case INTEGER -> GameOptionDto.TypeEnum.INTEGER;
            case ENUM    -> GameOptionDto.TypeEnum.ENUM;
        };
        GameOptionDto dto = new GameOptionDto(
                o.key(),
                o.labelKey(),
                typeEnum,
                o.defaultValue());
        dto.setDescriptionKey(o.descriptionKey());
        dto.setCategory(GameOptionDto.CategoryEnum.fromValue(o.category().name()));
        if (!o.choices().isEmpty()) dto.setChoices(o.choices());
        if (o.minValue() != null) dto.setMinValue(o.minValue());
        if (o.maxValue() != null) dto.setMaxValue(o.maxValue());
        if (o.adminOnly()) dto.setAdminOnly(true);
        if (!o.incompatibleWith().isEmpty()) dto.setIncompatibleWith(o.incompatibleWith());
        return dto;
    }
}
