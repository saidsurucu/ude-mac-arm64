package macostextkeys;

import java.io.File;
import java.io.FileWriter;

/**
 * UDE_TRLOG=1 ile ~/Library/Logs/ude-textreplace.txt'ye yazar
 * (System.err uygulama tarafından yutuluyor — UDE_DICTLOG deseni).
 */
final class TrLog {

    private static final boolean ON = "1".equals(System.getenv("UDE_TRLOG"));

    private TrLog() {}

    static void log(String msg) {
        if (!ON) return;
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-textreplace.txt");
            FileWriter w = new FileWriter(f, true);
            try { w.write(System.currentTimeMillis() + " " + msg + "\n"); } finally { w.close(); }
        } catch (Throwable ignore) {
            // Log asla uygulamayı etkilememeli.
        }
    }
}
