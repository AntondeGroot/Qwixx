package nl.adg.qwixx.e2e.utils;

import nl.adg.qwixx.QwixxApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.nio.file.Path;

public class SpringAppTestHelper {

    private static ConfigurableApplicationContext context;
    private static int port = -1;

    public static synchronized void startApp() {
        if (context != null) return;

        Path clientRoot = Path.of(System.getProperty("user.dir")).resolve("../client").normalize().toAbsolutePath();
        buildAngularDevBundle(clientRoot.toFile());

        String clientDist = clientRoot.resolve("dist/client/browser").toString();
        SpringApplication app = new SpringApplication(QwixxApplication.class);
        // "e2e" profile: enables TestController, disables demo-game auto-setup
        app.setAdditionalProfiles("e2e");
        // port=0 lets the OS pick a free port, avoiding conflicts with any running servers
        context = app.run(
                "--server.port=0",
                "--spring.web.resources.static-locations=file:" + clientDist + "/"
        );
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    public static synchronized int getPort() {
        if (port < 0) throw new IllegalStateException("App not started yet");
        return port;
    }

    public static synchronized void stopApp() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Builds the Angular app with the development configuration (apiBaseUrl='').
     * This ensures API calls go to /gamestates/... instead of /qwixx/gamestates/...
     * so no path-prefix filter is needed on the Spring side.
     */
    private static void buildAngularDevBundle(File clientDir) {
        try {
            System.out.println("[E2E] Building Angular dev bundle…");
            String[] cmd = isWindows()
                    ? new String[]{"cmd", "/c", "npm", "run", "build", "--", "--configuration=development"}
                    : new String[]{"npm", "run", "build", "--", "--configuration=development"};

            int exit = new ProcessBuilder(cmd)
                    .directory(clientDir)
                    .inheritIO()
                    .start()
                    .waitFor();

            if (exit != 0) throw new IllegalStateException("Angular dev build failed (exit " + exit + ")");
            System.out.println("[E2E] Angular dev bundle ready.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Angular build interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Angular build failed: " + e.getMessage(), e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
