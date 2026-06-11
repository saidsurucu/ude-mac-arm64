package macosskin;

import java.awt.Color;

/** IconDarken.lightenPixel için elle çalıştırılan test harness'ı
 *  (paket-içi erişim için macosskin paketinde).
 *  Derle+çalıştır: bkz. docs/superpowers/plans/2026-06-10-fluent-color-icons.md Task 2.
 *  Çıktı "OK" değilse FAIL. */
public class IconDarkenPixelTest {
    static int fails = 0;

    static void check(String name, boolean cond) {
        if (!cond) { System.err.println("FAIL: " + name); fails++; }
    }

    public static void main(String[] args) {
        // 1) Şeffaf piksel dokunulmaz
        check("transparent", IconDarken.lightenPixel(0x00000000) == 0x00000000);

        // 2) Nötr koyu gri (#444444) -> açık gri (eski kural korunuyor)
        int p = IconDarken.lightenPixel(0xFF444444);
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        check("neutral lightened", r == g && g == b && r > 0x90);
        check("neutral alpha kept", (p >>> 24) == 0xFF);

        // 3) Doygun-koyu yeşil (#107C41) -> ton korunur, parlaklık ~0.72
        p = IconDarken.lightenPixel(0xFF107C41);
        float[] in  = Color.RGBtoHSB(0x10, 0x7C, 0x41, null);
        float[] out = Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, null);
        check("green hue kept",  Math.abs(in[0] - out[0]) < 0.02f);
        check("green brightened", out[2] > 0.65f && out[2] < 0.80f);

        // 4) Doygun-orta kırmızı (#D13438 bri~0.82) dokunulmaz; koyusu aydınlatılır
        check("mid red untouched", IconDarken.lightenPixel(0xFFD13438) == 0xFFD13438);
        p = IconDarken.lightenPixel(0xFF7A1F22); // koyu kırmızı bri~0.48
        out = Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, null);
        check("dark red brightened", out[2] > 0.65f);

        // 5) Parlak doygun mavi (#4D9FE8) dokunulmaz
        check("bright blue untouched", IconDarken.lightenPixel(0xFF4D9FE8) == 0xFF4D9FE8);

        if (fails == 0) System.out.println("OK");
        else System.exit(1);
    }
}
