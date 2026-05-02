package nl.adg.qwixx.e2e.utils;

public class Log {
    private static final boolean CI = "true".equalsIgnoreCase(System.getenv("CI"));

    public static void info(String message) {
        if (!CI) {
            System.out.println(message);
        }
    }
}
