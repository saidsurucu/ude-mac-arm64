package macosskin;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * macOS Açık/Koyu görünüm algılaması (SKIN=1).
 * "defaults read -g AppleInterfaceStyle" yalnız koyu modda "Dark" döner;
 * açık modda anahtar yoktur (exit != 0). Hata/timeout = açık mod (güvenli).
 * Substance'a bağımlılık YOK: wp.p clinit'i skin kurulumundan önce çalışabilir.
 */
public final class DarkMode {
    private static Boolean dark;

    private static final java.util.prefs.Preferences PREFS =
        java.util.prefs.Preferences.userRoot().node("ude-mac-arm");
    private static final String MODE_KEY = "colorMode";

    private DarkMode() {}

    /** Kalıcı renk modu tercihi: "light" | "dark" | "system" (varsayılan).
     *  Görünüm sekmesindeki "Renk modu" combo'su yazar (MacLook agent →
     *  ModeSwitch.apply): canlı geçiş resetCache() + skin/delegate yeniden
     *  kurulumuyla yapılır; ikonlar ModeAwareImage sayesinde paint anında
     *  moda uyar. */
    public static String getMode() {
        try {
            return PREFS.get(MODE_KEY, "system");
        } catch (Throwable t) {
            return "system";
        }
    }

    public static void setMode(String mode) {
        try {
            PREFS.put(MODE_KEY, mode);
            PREFS.flush();
        } catch (Throwable t) {
            trace("setMode HATA: " + t);
        }
    }

    /** Canlı mod geçişi için: bir sonraki isDark() tercihi yeniden çözer. */
    public static synchronized void resetCache() {
        dark = null;
    }

    public static synchronized boolean isDark() {
        if (dark == null) {
            String mode = getMode();
            if ("dark".equals(mode)) {
                dark = Boolean.TRUE;
                return true;
            }
            if ("light".equals(mode)) {
                dark = Boolean.FALSE;
                return false;
            }
            boolean d = false;
            try {
                Process p = Runtime.getRuntime().exec(
                    new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"});
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                        String line = r.readLine();
                        d = line != null && line.trim().equals("Dark");
                    }
                } else {
                    p.destroyForcibly();
                }
            } catch (Throwable t) {
                d = false;
            }
            dark = Boolean.valueOf(d);
        }
        return dark.booleanValue();
    }

    /** Editör masaüstü (kanvas) rengi: açıkta Word-Mac açık kanvası
     *  (#ECECEC piksel ölçümü), koyuda Word-Mac koyu yüzeyiyle aynı
     *  (#282828 — Word şerit=kanvas tek ton kullanır). */
    public static Color canvasColor() {
        return isDark() ? new Color(40, 40, 40) : new Color(236, 236, 236);
    }

    /** -Dmacosskin.debug=1 ile teşhis izi (System.err uygulama tarafından yutulur; dosyaya yaz). */
    private static final boolean DEBUG = "1".equals(System.getProperty("macosskin.debug"));

    public static void trace(String m) {
        if (!DEBUG) return;
        try (java.io.FileWriter w = new java.io.FileWriter("/tmp/skinpatch-trace.log", true)) {
            w.write(System.currentTimeMillis() + " " + m + "\n");
        } catch (Throwable ignore) {
        }
    }
}
