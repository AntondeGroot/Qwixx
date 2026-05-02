package nl.adg.qwixx.e2e.utils;

import nl.adg.qwixx.QwixxApplication;
import nl.adg.qwixx.game.GameRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class SpringAppTestHelper {

  private static ConfigurableApplicationContext context;
  private static String sessionId;

  public static synchronized void startTestApp() {
    if (context != null) {
      return;
    }

    System.setProperty("server.port", "4200");
    SpringApplication app = new SpringApplication(QwixxApplication.class);
    context = app.run();

    // Get the test session ID created by QwixxTestApplication
    var games = GameRegistry.getAllGames();
    if (!games.isEmpty()) {
      sessionId = games.get(0).sessionId();
    }
  }

  public static synchronized void stopApp() {
    if (context != null) {
      context.close();
      context = null;
      sessionId = null;
    }
  }

  public static String getSessionId() {
    if (sessionId == null) {
      var games = GameRegistry.getAllGames();
      if (!games.isEmpty()) {
        sessionId = games.get(0).sessionId();
      }
    }
    return sessionId;
  }
}
