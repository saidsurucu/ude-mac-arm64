package macoslinespacing;

import java.io.File;
import java.io.FileWriter;

/** UDE_LSLOG=1 → ~/Library/Logs/ude-linespacing.txt (System.err yutulur). */
final class LsLog {

    private static final boolean ON = "1".equals(System.getenv("UDE_LSLOG"));

    private LsLog() {}

    static void log(String msg) {
        if (!ON) return;
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-linespacing.txt");
            FileWriter w = new FileWriter(f, true);
            try { w.write(System.currentTimeMillis() + " " + msg + "\n"); } finally { w.close(); }
        } catch (Throwable ignore) {
            // Log asla uygulamayı etkilememeli.
        }
    }
}
