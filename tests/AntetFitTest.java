package macosantet;

/*
 * Contain-fit matematiği testi.
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/antet-test \
 *     scripts/antet/macosantet/AntetLog.java \
 *     scripts/antet/macosantet/AntetStore.java \
 *     tests/AntetFitTest.java
 *   java -cp /tmp/antet-test macosantet.AntetFitTest
 */
public final class AntetFitTest {

    private static int fails = 0;

    private static void check(String name, int[] got, int w, int h) {
        if (got[0] != w || got[1] != h) {
            fails++;
            System.out.println("FAIL " + name + ": beklenen " + w + "x" + h
                + ", gelen " + got[0] + "x" + got[1]);
        }
    }

    public static void main(String[] args) {
        double pw = AntetStore.A4_W, ph = AntetStore.A4_H;
        double tw = pw * AntetStore.DPI_SCALE, th = ph * AntetStore.DPI_SCALE;

        check("A4 oranli orta kaynak 300dpi alti -> buyutulmez",
            AntetStore.computeFit(1240, 1754, pw, ph), 1240, 1754);
        check("devasa A4 kaynak 300dpi'a iner",
            AntetStore.computeFit(5000, 7071, pw, ph), 2479, 3506);
        check("genis bant 300dpi alti -> buyutulmez",
            AntetStore.computeFit(2000, 500, pw, ph), 2000, 500);
        check("devasa genis bant genislige gore iner",
            AntetStore.computeFit(8000, 1000, pw, ph), 2479, 310);
        check("kucuk resim ASLA buyutulmez",
            AntetStore.computeFit(100, 100, pw, ph), 100, 100);
        check("sayfa-pt boyutu buyutulmez",
            AntetStore.computeFit(595, 842, pw, ph), 595, 842);

        for (int iw = 1; iw < 9000; iw += 277) {
            for (int ih = 1; ih < 9000; ih += 311) {
                int[] d = AntetStore.computeFit(iw, ih, pw, ph);
                if (d[0] < 1 || d[1] < 1
                        || d[0] > Math.ceil(tw) || d[1] > Math.ceil(th)
                        || d[0] > iw || d[1] > ih) {
                    fails++;
                    System.out.println("FAIL sinir: " + iw + "x" + ih
                        + " -> " + d[0] + "x" + d[1]);
                }
            }
        }

        if (fails > 0) { System.out.println(fails + " hata"); System.exit(1); }
        System.out.println("OK AntetFitTest");
    }
}
