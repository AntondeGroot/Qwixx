# E2E Tests for Qwixx

The Qwixx project uses Selenium-based end-to-end tests to verify browser automation and multiplayer game flow.

## Architecture

The E2E tests use:
- **JUnit 5** with a custom `SpringAppSuiteExtension` that manages the Spring Boot app lifecycle
- **Selenium WebDriver 4.43.0** with headless Chrome automation
- **WebDriverManager 6.3.4** for automatic ChromeDriver management
- **Maven Failsafe Plugin** for integration test phase execution

Test files:
- `src/test/java/nl/adg/qwixx/e2e/utils/SpringAppSuiteExtension.java` - JUnit 5 extension that starts/stops Spring app
- `src/test/java/nl/adg/qwixx/e2e/utils/SpringAppTestHelper.java` - Manages Spring app lifecycle programmatically
- `src/test/java/nl/adg/qwixx/e2e/utils/BaseIntegrationTest.java` - Base class for E2E tests with WebDriver setup
- `src/test/java/nl/adg/qwixx/e2e/GameBoardE2ETest.java` - Test scenarios for game board functionality
- `src/test/java/nl/adg/qwixx/e2e/helpers/BoardInteractionHelper.java` - Helpers for interacting with game UI elements

## Running E2E Tests

### Option 1: Automated (Recommended)

The E2E tests are integrated into Maven's build lifecycle. To run them:

```bash
# Build frontend first
cd client
npm install
npm run generate
npm run build

# Run E2E tests as part of verify
cd ../server
mvn verify
```

This will:
1. Run unit tests (Surefire) - excludes E2E tests
2. Build the project
3. Start the Spring Boot test app on port 4200
4. Run E2E tests (Failsafe) - tagged with @Tag("browser")
5. Stop the Spring app and report results

### Option 2: Manual (for Development)

If you want to run tests manually and debug:

```bash
# Terminal 1: Build and start the test app
cd server
mvn spring-boot:run -Dspring-boot.run.main-class=nl.adg.qwixx.testapp.QwixxTestApplication

# Terminal 2: Start the frontend dev server  
cd client
npm start

# Terminal 3: Run E2E tests
cd server
mvn test -Dtest=GameBoardE2ETest
```

## Test Game Setup

The `QwixxTestApplication` automatically creates a test game on startup with:
- 2 players (IDs: player0, player1)
- Both players have 5 crosses in the BLUE row
- Predetermined dice roll: 1 + 1 = 2
- Player 0 is active

URLs to access the game:
- Player 0: `http://localhost:4200/?sessionid=<sessionId>&playerid=<player0Id>`
- Player 1: `http://localhost:4200/?sessionid=<sessionId>&playerid=<player1Id>`

The session and player IDs are printed to stdout when the app starts.

## Test Scenarios

Current tests verify:
- Board loads correctly for both players
- Both players see the same initial state (5 crosses in BLUE)
- Lock buttons are visible but not clickable initially
- Cell clicking works (basic interaction)
- Game state is synchronized between players

## Configuration

Key Maven properties:
- `server.port=4200` (set by SpringAppTestHelper)
- `groups=browser` (selects only @Tag("browser") tests in failsafe)
- Chrome runs in headless mode with disabled GPU

## Debugging

To see test execution details:
```bash
# Run with verbose output
mvn verify -X

# Run a specific test
mvn verify -Dtest=GameBoardE2ETest#testGameBoardLoadsForBothPlayers
```

To debug a test, add breakpoints in your IDE and run:
```bash
mvn -Dmaven.surefire.debug test
```

## Known Limitations

1. Tests run sequentially (forkCount=1) to avoid port conflicts
2. Frontend must be built before E2E tests run
3. Tests assume `http://localhost:4200` is available
4. Chrome must be installed and in PATH
5. Currently tests a single game variant (normal Qwixx)

## Future Enhancements

- [ ] Test additional game variants (Longo, Bluffed)
- [ ] Test lock flow and row closure scenarios
- [ ] Add screenshot capture on test failure
- [ ] Parameterize tests for different row colors
- [ ] Add performance benchmarks
