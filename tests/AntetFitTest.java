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

        check("A4 oranli buyuk tarama (150dpi A4)",
            AntetStore.computeFit(1240, 1754, pw, ph), 595, 842);
        check("genis bant antet",
            AntetStore.computeFit(2000, 500, pw, ph), 595, 149);
        check("uzun dar serit",
            AntetStore.computeFit(500, 2000, pw, ph), 211, 842);
        check("kucuk resim buyutulur",
            AntetStore.computeFit(100, 100, pw, ph), 595, 595);
        check("tam sayfa boyutu degismez",
            AntetStore.computeFit(595, 842, pw, ph), 595, 842);

        for (int iw = 1; iw < 4000; iw += 173) {
            for (int ih = 1; ih < 4000; ih += 211) {
                int[] d = AntetStore.computeFit(iw, ih, pw, ph);
                if (d[0] < 1 || d[1] < 1 || d[0] > Math.ceil(pw) || d[1] > Math.ceil(ph)) {
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
