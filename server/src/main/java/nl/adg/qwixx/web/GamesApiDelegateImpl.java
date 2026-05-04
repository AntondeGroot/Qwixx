package nl.adg.qwixx.web;

import nl.adg.qwixx.game.GameAlreadyStartedException;
import nl.adg.qwixx.game.GameNotFinishedException;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.game.QwixxGameOptions;
import nl.adg.qwixx.game.SessionNotFoundException;
import nl.adg.qwixx.generated.api.GamesApiDelegate;
import nl.adg.qwixx.generated.model.AddPlayerToGame201Response;
import nl.adg.qwixx.generated.model.CreateNewGame201Response;
import nl.adg.qwixx.generated.model.GameInfo;
import nl.adg.qwixx.generated.model.GameStatus;
import nl.adg.qwixx.generated.model.NewGameRequest;
import nl.adg.qwixx.generated.model.NewPlayerRequest;
import nl.adg.qwixx.generated.model.RestartGameRequest;
import nl.adg.qwixx.generated.model.ScoreCard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.UUID;

@Service
public class GamesApiDelegateImpl implements GamesApiDelegate {

    @Override
    public ResponseEntity<CreateNewGame201Response> createNewGame(NewGameRequest req) {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, req.getGameOptions());
        String id = GameRegistry.createGame(req.getRoomName(), req.getMaxPlayers(), builder.build());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateNewGame201Response().sessionId(id));
    }

    @Override
    public ResponseEntity<List<GameInfo>> getAllGames() {
        List<GameInfo> result = GameRegistry.getAllGames().stream()
                .map(this::toGameInfo)
                .toList();
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<GameInfo> getGame(String sessionId) {
        return ResponseEntity.ok(toGameInfo(require(sessionId)));
    }

    @Override
    public ResponseEntity<Void> startGame(String sessionId) {
        GameSession session = require(sessionId);
        try {
            session.start();
        } catch (IllegalStateException ex) {
            throw new GameAlreadyStartedException(sessionId);
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> restartGame(String sessionId, RestartGameRequest req) {
        GameSession session = require(sessionId);
        Map<String, Object> gameOptions = (req != null && req.getGameOptions() != null)
                ? new HashMap<>(req.getGameOptions())
                : session.proposedOptions();
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, gameOptions);
        try {
            session.restart(builder.build());
        } catch (IllegalStateException ex) {
            throw new GameNotFinishedException(sessionId);
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> stopGame(String sessionId) {
        require(sessionId);
        GameRegistry.removeGame(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AddPlayerToGame201Response> addPlayerToGame(String sessionId,
            NewPlayerRequest req) {
        GameSession session = require(sessionId);
        Player player = req.getId() != null && !req.getId().isBlank()
                ? new Player(UUID.fromString(req.getId()), req.getName())
                : Player.of(req.getName());
        try {
            session.addPlayer(player);
        } catch (IllegalStateException ex) {
            throw new GameAlreadyStartedException(sessionId);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AddPlayerToGame201Response().playerId(player.id().toString()));
    }

    @Override
    public ResponseEntity<List<nl.adg.qwixx.generated.model.Player>> getAllPlayersInGame(
            String sessionId) {
        GameSession session = require(sessionId);
        List<nl.adg.qwixx.generated.model.Player> players = session.players().stream()
                .map(p -> new nl.adg.qwixx.generated.model.Player(p.id().toString(), p.name()))
                .toList();
        return ResponseEntity.ok(players);
    }

    @Override
    public ResponseEntity<Void> leaveGame(String sessionId, String playerId) {
        GameSession session = require(sessionId);
        try {
            session.removePlayer(UUID.fromString(playerId));
        } catch (IllegalArgumentException ex) {
            throw new SessionNotFoundException(playerId);
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, ScoreCard>> getScores(String sessionId) {
        GameSession session = require(sessionId);
        if (session.status() != nl.adg.qwixx.game.SessionStatus.FINISHED)
            throw new GameNotFinishedException(sessionId);

        // Iterate the game-state player list (all who participated) rather than
        // session.players() which may be smaller if players left after the game ended.
        Map<String, ScoreCard> result = new HashMap<>();
        for (UUID playerId : session.currentState().players()) {
            nl.adg.qwixx.rules.ScoreCard sc = session.getScore(playerId);
            result.put(playerId.toString(), toDto(sc));
        }
        return ResponseEntity.ok(result);
    }

    private static ScoreCard toDto(nl.adg.qwixx.rules.ScoreCard sc) {
        Map<String, Integer> crosses = new HashMap<>();
        sc.crossesPerColor().forEach((c, v) -> crosses.put(c.name(), v));
        Map<String, Integer> points = new HashMap<>();
        sc.pointsPerColor().forEach((c, v) -> points.put(c.name(), v));
        return new ScoreCard(crosses, points, sc.extraCrosses(), sc.extraPoints(),
                sc.bonusPoints(), sc.punishmentPoints(), sc.total());
    }

    private GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }

    private GameInfo toGameInfo(GameSession session) {
        return new GameInfo(
                session.sessionId(),
                session.roomName(),
                session.players().size(),
                session.maxPlayers(),
                toGameStatus(session));
    }

    private GameStatus toGameStatus(GameSession session) {
        return switch (session.status()) {
            case WAITING     -> GameStatus.WAITING;
            case IN_PROGRESS -> GameStatus.IN_PROGRESS;
            case FINISHED    -> GameStatus.FINISHED;
        };
    }
}