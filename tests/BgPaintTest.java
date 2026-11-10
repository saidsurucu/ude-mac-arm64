package macosbgimage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/*
 * Arka plan resmi önbellekli çizim testi (BGIMG).
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/bgimg-test \
 *     scripts/macos-bgimage/macosbgimage/BgPaint.java tests/BgPaintTest.java
 *   java -Djava.awt.headless=true -cp /tmp/bgimg-test macosbgimage.BgPaintTest
 */
public final class BgPaintTest {

    private static int fails = 0;

    private static void check(String name, boolean ok) {
        if (!ok) { fails++; System.out.println("FAIL " + name); }
    }

    /** Sol yarısı kırmızı, sağ yarısı mavi kaynak. */
    private static BufferedImage source(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);  g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE); g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    private static BufferedImage canvas(int w, int h) {
        BufferedImage c = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h); g.dispose();
        return c;
    }

    public static void main(String[] args) throws Exception {
        // --- cihaz boyutu hesabı ---
        int[] d = BgPaint.deviceSize(AffineTransform.getScaleInstance(2.576, 2.576), 595, 841);
        check("canli olcum: 595x841 @2.576 -> 1533x2166", d != null && d[0] == 1533 && d[1] == 2166);
        d = BgPaint.deviceSize(new AffineTransform(), 100, 50);
        check("birim donusum boyutu korur", d != null && d[0] == 100 && d[1] == 50);
        check("dondurme onbelleklenmez",
            BgPaint.deviceSize(AffineTransform.getRotateInstance(0.3), 100, 50) == null);
        check("ayna (negatif olcek) onbelleklenmez",
            BgPaint.deviceSize(AffineTransform.getScaleInstance(-1, 1), 100, 50) == null);
        check("asiri buyuk hedef onbelleklenmez",
            BgPaint.deviceSize(AffineTransform.getScaleInstance(10, 10), 595, 841) == null);

        // --- ölçekli çizim: önbellek dolar, sonuç doğru yere doğru renkle düşer ---
        BgPaint.clear();
        BufferedImage src = source(300, 400, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage out = canvas(400, 500);
        Graphics2D g = out.createGraphics();
        g.scale(2, 2);
        check("cizim true doner", BgPaint.drawCached(g, src, 10, 20, 100, 150, null, false));
        check("ilk cizim tek girdi uretir", BgPaint.cacheSize() == 1);
        check("girdi cihaz boyutunda", BgPaint.lookup(src, 200, 300) != null
            && BgPaint.lookup(src, 200, 300).getWidth() == 200
            && BgPaint.lookup(src, 200, 300).getHeight() == 300);
        check("sol yari kirmizi", out.getRGB(20 + 40, 40 + 150) == Color.RED.getRGB());
        check("sag yari mavi", out.getRGB(20 + 160, 40 + 150) == Color.BLUE.getRGB());
        check("hedef disi dokunulmaz (sol)", out.getRGB(10, 100) == Color.WHITE.getRGB());
        check("hedef disi dokunulmaz (alt)", out.getRGB(100, 40 + 300 + 5) == Color.WHITE.getRGB());

        BgPaint.drawCached(g, src, 10, 20, 100, 150, null, false);
        check("ikinci cizim onbellekten (yeni girdi yok)", BgPaint.cacheSize() == 1);

        // Çizim Graphics durumuna sızmaz (eski yama bicubic ipucunu açık bırakıyordu).
        check("interpolation ipucu sizmaz",
            g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION) == null
            || g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION)
               == java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // Zoom değişimi = yeni boyut = ayrı girdi; eski kaynak aynı kalır.
        g.scale(1.5, 1.5);
        BgPaint.drawCached(g, src, 0, 0, 100, 150, null, false);
        check("farkli zoom ayri girdi", BgPaint.cacheSize() == 2 && BgPaint.lookup(src, 300, 450) != null);
        g.dispose();

        // --- 1:1 hedef: ölçekleme gerekmez, önbellek açılmaz ---
        BgPaint.clear();
        out = canvas(400, 500);
        g = out.createGraphics();
        BgPaint.drawCached(g, src, 0, 0, 300, 400, null, false);
        check("1:1 cizim onbellek acmaz", BgPaint.cacheSize() == 0);
        check("1:1 cizim piksel dogru", out.getRGB(10, 10) == Color.RED.getRGB()
            && out.getRGB(290, 390) == Color.BLUE.getRGB());
        g.dispose();

        // --- saydam kaynak: alfa korunur (zemin görünür) ---
        BgPaint.clear();
        BufferedImage argb = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ag = argb.createGraphics();
        ag.setColor(Color.GREEN); ag.fillRect(0, 0, 50, 100); ag.dispose();
        out = canvas(200, 200);
        g = out.createGraphics();
        BgPaint.drawCached(g, argb, 0, 0, 200, 200, null, false);
        check("saydam kaynak: opak bolge", out.getRGB(40, 100) == Color.GREEN.getRGB());
        check("saydam kaynak: saydam bolgede zemin", out.getRGB(160, 100) == Color.WHITE.getRGB());
        g.dispose();

        // --- büyük küçültme: kademeli yarılama doğru boyut ve ortalama renk verir ---
        BufferedImage big = source(2480, 3508, BufferedImage.TYPE_INT_RGB);
        BufferedImage small = BgPaint.scale(big, 300, 424);
        check("kademeli kucultme boyutu", small.getWidth() == 300 && small.getHeight() == 424);
        check("kademeli kucultme renkleri", small.getRGB(30, 200) == Color.RED.getRGB()
            && small.getRGB(270, 200) == Color.BLUE.getRGB());

        // --- bütçe: yakın zamanda kullanılan girdiler tahliye edilmez, yenisi reddedilir ---
        BgPaint.clear();
        BufferedImage s1 = source(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage s2 = source(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage s3 = source(10, 10, BufferedImage.TYPE_INT_RGB);
        check("butce: 1. girdi", BgPaint.request(s1, 4000, 3000, false) != null);
        check("butce: 2. girdi", BgPaint.request(s2, 4000, 3000, false) != null);
        check("butce: dolunca taze girdiler korunur, yenisi reddedilir",
            BgPaint.request(s3, 4000, 3000, false) == null && BgPaint.cacheSize() == 2);
        check("butce: ayni anahtar yeniden istenince ayni girdi",
            BgPaint.request(s1, 4000, 3000, false) != null && BgPaint.cacheSize() == 2);

        // --- eşzamansız yol: ilk çizim stok düşer, girdi arka planda hazırlanır ---
        BgPaint.clear();
        out = canvas(400, 500);
        g = out.createGraphics();
        g.scale(2, 2);
        check("async ilk cizim true doner", BgPaint.drawCached(g, src, 0, 0, 100, 150, null, true));
        check("async ilk cizim de ekrana bir sey basar", out.getRGB(40, 150) == Color.RED.getRGB());
        long until = System.currentTimeMillis() + 10_000;
        while (BgPaint.lookup(src, 200, 300) == null && System.currentTimeMillis() < until) Thread.sleep(20);
        check("async girdi arka planda hazirlandi", BgPaint.lookup(src, 200, 300) != null);
        g.dispose();

        // --- sürekli zoom: kuyrukta bayatlayan istek üretilmeden düşer, sonuncusu üretilir ---
        BgPaint.clear();
        BufferedImage heavy = source(2000, 2000, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 6; i++) BgPaint.request(heavy, 1500 + i, 1500 + i, true);
        until = System.currentTimeMillis() + 20_000;
        while (BgPaint.lookup(heavy, 1505, 1505) == null && System.currentTimeMillis() < until) Thread.sleep(20);
        check("surekli zoom: son istek uretildi", BgPaint.lookup(heavy, 1505, 1505) != null);
        int built = 0;
        for (int i = 0; i < 6; i++) if (BgPaint.lookup(heavy, 1500 + i, 1500 + i) != null) built++;
        check("surekli zoom: ara istekler atlandi (uretilen=" + built + ")", built <= 3);

        if (fails == 0) System.out.println("OK — tum BgPaint testleri gecti");
        else { System.out.println(fails + " test basarisiz"); System.exit(1); }
        System.exit(0);
    }
}
