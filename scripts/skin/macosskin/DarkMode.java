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

    private DarkMode() {}

    public static synchronized boolean isDark() {
        if (dark == null) {
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

    /** Editör masaüstü (kanvas) rengi: açıkta nötr gri, koyuda koyu gri. */
    public static Color canvasColor() {
        return isDark() ? new Color(30, 30, 30) : new Color(228, 231, 235);
    }

    /** Teşhis izi (System.err uygulama tarafından yutulur; dosyaya yaz). */
    public static void trace(String m) {
        try (java.io.FileWriter w = new java.io.FileWriter("/tmp/skinpatch-trace.log", true)) {
            w.write(System.currentTimeMillis() + " " + m + "\n");
        } catch (Throwable ignore) {
        }
    }
}
