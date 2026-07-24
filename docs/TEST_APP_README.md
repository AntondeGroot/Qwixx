# Qwixx Test Application

Persistent test game application that automatically creates a test game session on startup.

## Test Game Setup

When the application starts, it automatically creates:
- **2 players**: `player0` and `player1`
- **Game state**: Both players have 5 crosses in the BLUE row
- **Dice rolled**: White dice are rolled (values random each run)
- **Active player**: `player0`

The sessionId and player IDs are printed on startup.

## How to Run

### Option 1: Using the Shell Script (Easiest)

From the repository root:

```bash
chmod +x run-test-game.sh
./run-test-game.sh
```

### Option 2: Maven Command

From the `server` directory:

```bash
mvn spring-boot:run -Dspring-boot.run.main-class=nl.adg.qwixx.testapp.QwixxTestApplication
```

### Option 3: Build and Run

From the `server` directory:

```bash
mvn clean package -q -DskipTests
java -cp "target/classes:target/dependency/*" nl.adg.qwixx.testapp.QwixxTestApplication
```

## Using the Test Game

Once the application starts, you'll see output like:

```
╔═══════════════════════════════════════════════════════════════╗
║           QWIXX TEST APPLICATION - CREATING TEST GAME           ║
╚═══════════════════════════════════════════════════════════════╝

✓ Test game created successfully!

TEST GAME DETAILS:
─────────────────────────────────────────
  Session ID:     8c339114-8d41-438c-8ce6-e299e54f995a
  Player 0 ID:    866fbb41-2000-4ad3-a58f-9cb610edeadb
  Player 1 ID:    40aab528-023f-4ec0-9b75-75d3eff68b35
  Active Player:  player0
  White Dice:     2 + 3 = 5
  Both players:   5 crosses in BLUE row
  Game Phase:     ACTIVE_MOVE
─────────────────────────────────────────

Server is running with the test game available:
  Player0: http://localhost:8080/?sessionid=8c339114-8d41-438c-8ce6-e299e54f995a&playerid=866fbb41-2000-4ad3-a58f-9cb610edeadb
  Player1: http://localhost:8080/?sessionid=8c339114-8d41-438c-8ce6-e299e54f995a&playerid=40aab528-023f-4ec0-9b75-75d3eff68b35
```

Copy one of the URLs into your browser to test the game.

## Default Server Settings

- **Server URL**: `http://localhost:8080`
- **Port**: 8080
- **Auto-created test game**: Yes, on startup

## Accessing API Directly

You can also access the API directly:

```bash
# Get game state
curl http://localhost:8080/gamestates/{sessionId}

# Make a move
curl -X POST http://localhost:8080/moves/{sessionId}/{playerId} \
  -H "Content-Type: application/json" \
  -d '{"moveType":"ROLL"}'
```

Replace `{sessionId}` and `{playerId}` with the actual values from the startup output.

## How It Works

The `QwixxTestApplication` is a Spring component that:

1. Runs the standard Qwixx application
2. On application startup, creates a new game session with 2 players
3. Sets both players to have 5 crosses in the BLUE row
4. Rolls the dice to start play
5. Prints all connection details to the console

Each time you restart the application, a new test game is created with a new sessionId.

## Implementation Details

- **Location**: `../server/src/main/java/nl/adg/qwixx/testapp/QwixxTestApplication.java`
- **Package**: `nl.adg.qwixx.testapp` (separate from main app to avoid Spring Boot conflicts)
- **Main method**: Runs `QwixxApplication` with this component active
- **Setup method**: `@EventListener` on `ContextRefreshedEvent` creates the test game
