package nl.adg.qwixx.web;

import nl.adg.qwixx.game.OptionType;
import nl.adg.qwixx.game.QwixxGameOptions;
import nl.adg.qwixx.generated.api.GameOptionsApiDelegate;
import nl.adg.qwixx.generated.model.GameOption;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameOptionsApiDelegateImpl implements GameOptionsApiDelegate {

    @Override
    public ResponseEntity<List<GameOption>> getGameOptions() {
        List<GameOption> options = QwixxGameOptions.all().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(options);
    }

    private GameOption toDto(nl.adg.qwixx.game.GameOption o) {
        GameOption dto = new GameOption(
                o.key(),
                o.label(),
                o.description(),
                o.type() == OptionType.BOOLEAN
                        ? GameOption.TypeEnum.BOOLEAN
                        : GameOption.TypeEnum.ENUM,
                o.defaultValue());
        if (!o.choices().isEmpty()) dto.setChoices(o.choices());
        return dto;
    }
}