package macosbgimage;

import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.print.PrinterGraphics;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sayfa arka plan resmi (antet) çizimi — BGIMG=1.
 *
 * UDE sayfa zeminini wp.b.at.a(Graphics,Rectangle,int) içinde HER boyamada
 * drawImage(img,x,y,w,h) ile ölçekleyerek çizer. Eski yamamız bu çağrıya BICUBIC
 * ipucu koyuyordu; macOS'ta bicubic ölçekleme hızlandırılmaz (Java2D yazılım
 * döngüsüne düşer) → her tuş vuruşu/kaydırma ~100 ms (ölçüm: 4,5 ms → 116 ms).
 *
 * Çözüm: kaliteli ölçeklemeyi boyama başına DEĞİL, (kaynak, cihaz boyutu) başına
 * BİR KEZ yap; sonuç önbellekten 1:1 kopyalanır. Hedefe göre üç yol:
 *   - ekran yüzeyi: ölçekleme arka plan iş parçacığında; hazır olana dek stok (en
 *     yakın komşu) çizim, hazır olunca pencereler yeniden boyanır;
 *   - bellek-içi görüntü (Swing'in yazılım arka tamponu — hızlandırılmış yüzey
 *     yokken — ya da görüntüye dışa aktarım): ölçekleme çağıran iş parçacığında
 *     bir kez, sonra önbellek; ilk ve tek boyamada da kaliteli sonuç;
 *   - yazıcı: eski davranış, tek seferlik bicubic.
 *
 * Bu dosya ude-win-x64 deposundaki scripts/bgimage/macosbgimage/BgPaint.java ile
 * EŞ tutulur; tek fark günlük dosyasının yoludur.
 *
 * Teşhis: UDE_BGLOG=1 → ~/Library/Logs/ude-bgimage.txt
 */
public final class BgPaint {
    private BgPaint() {}

    /** Tek girdinin üst sınırı (piksel). Aşılırsa önbelleklenmez, stok çizim. */
    static final long MAX_ENTRY_PIXELS = 16_000_000L;
    /** Tüm önbelleğin üst sınırı (piksel; 4 bayt/piksel → ~96 MB). */
    static final long MAX_TOTAL_PIXELS = 24_000_000L;
    /** Bu süre içinde kullanılmış girdi tahliye edilmez (çok pencere ping-pong'u önlenir). */
    static final long RECENT_NANOS = 2_000_000_000L;

    static final class Entry {
        final WeakReference<BufferedImage> src;
        final int w, h;
        BufferedImage img;      // null = üretim bekliyor
        boolean building;       // bir iş parçacığı şu an ölçekliyor
        long lastUsed;
        Entry(BufferedImage s, int w, int h) {
            this.src = new WeakReference<BufferedImage>(s);
            this.w = w; this.h = h; this.lastUsed = System.nanoTime();
        }
        long pixels() { return (long) w * h; }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> CACHE = new ArrayList<Entry>();
    private static Entry latest;
    private static ExecutorService worker;
    private static boolean loggedError;

    /** wp.b.at içindeki drawImage(Image,x,y,w,h,observer) çağrısının yerine geçer. */
    public static boolean draw(Graphics g, Image img, int x, int y, int w, int h, ImageObserver obs) {
        try {
            if (g instanceof Graphics2D && img instanceof BufferedImage && w > 0 && h > 0) {
                Graphics2D g2 = (Graphics2D) g;
                BufferedImage src = (BufferedImage) img;
                int type = deviceType(g2);
                if (g2 instanceof PrinterGraphics || type == GraphicsDevice.TYPE_PRINTER) {
                    return drawHinted(g2, src, x, y, w, h, obs, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                }
                return drawCached(g2, src, x, y, w, h, obs, type == GraphicsDevice.TYPE_RASTER_SCREEN);
            }
        } catch (Throwable t) {
            logError(t);
        }
        return g.drawImage(img, x, y, w, h, obs);
    }

    /**
     * Önbellekli çizim. async=true (ekran): ölçekleme arka planda, hazır olana dek
     * stok çizim. async=false (bellek-içi hedef): ölçekleme çağıran iş parçacığında;
     * önbelleklenemiyorsa (döndürme, aşırı büyük hedef, bütçe dolu) tek seferlik bicubic.
     *
     * Cihaz ölçeği Graphics2D.getTransform()'dan DEĞİL FontRenderContext'ten okunur:
     * Retina'da pencere yüzeyine doğrudan çizimde getTransform() 2x'i GİZLER, Swing
     * arka tamponunda ise gösterir (canlı ölçüm: tx=2 + GraphicsConfiguration=2 →
     * iki kez sayılıp 4x önbellek üretildi). FRC dönüşümü iki durumda da gerçek
     * kullanıcı→cihaz ölçeğini verir.
     */
    static boolean drawCached(Graphics2D g2, BufferedImage src, int x, int y, int w, int h,
                              ImageObserver obs, boolean async) {
        int[] d = deviceSize(g2.getFontRenderContext().getTransform(), w, h);
        if (d == null) {
            return drawUncached(g2, src, x, y, w, h, obs, async);
        }
        if (d[0] == src.getWidth() && d[1] == src.getHeight()) {
            return drawHinted(g2, src, x, y, w, h, obs, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        BufferedImage scaled = lookup(src, d[0], d[1]);
        if (scaled == null && !async) {
            Entry e = request(src, d[0], d[1], false);
            if (e != null) { build(e, false); scaled = lookup(src, d[0], d[1]); }
        } else if (scaled == null) {
            if (LOG) log("istek hedef=" + w + "x" + h + " cihaz=" + d[0] + "x" + d[1]
                + " tx=" + g2.getTransform().getScaleX() + " frc=" + g2.getFontRenderContext().getTransform().getScaleX());
            request(src, d[0], d[1], true);
        }
        if (scaled != null) {
            return drawHinted(g2, scaled, x, y, w, h, obs, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        return drawUncached(g2, src, x, y, w, h, obs, async);
    }

    /** Önbellek yokken: ekranda stok (hızlı) çizim, bellek-içi hedefte tek seferlik bicubic. */
    private static boolean drawUncached(Graphics2D g2, BufferedImage src, int x, int y, int w, int h,
                                        ImageObserver obs, boolean screen) {
        if (screen) return g2.drawImage(src, x, y, w, h, obs);
        return drawHinted(g2, src, x, y, w, h, obs, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    /** GraphicsDevice.TYPE_* ya da bilinmiyorsa -1 (bellek-içi hedef gibi ele alınır). */
    static int deviceType(Graphics2D g2) {
        try {
            GraphicsConfiguration gc = g2.getDeviceConfiguration();
            GraphicsDevice dev = gc == null ? null : gc.getDevice();
            return dev == null ? -1 : dev.getType();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Hedef dikdörtgenin cihaz pikseli boyutu; önbelleğe uygun değilse null. */
    static int[] deviceSize(AffineTransform tx, int w, int h) {
        int simple = AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE
                   | AffineTransform.TYPE_GENERAL_SCALE;
        if ((tx.getType() & ~simple) != 0) return null;
        double sx = tx.getScaleX(), sy = tx.getScaleY();
        if (!(sx > 0) || !(sy > 0)) return null;
        double fw = w * sx, fh = h * sy;
        if (!(fw < MAX_ENTRY_PIXELS) || !(fh < MAX_ENTRY_PIXELS)) return null;
        long dw = Math.round(fw), dh = Math.round(fh);
        if (dw < 1 || dh < 1 || dw * dh > MAX_ENTRY_PIXELS) return null;
        return new int[] { (int) dw, (int) dh };
    }

    private static boolean drawHinted(Graphics2D g2, BufferedImage img, int x, int y, int w, int h,
                                      ImageObserver obs, Object interpolation) {
        Graphics2D c = (Graphics2D) g2.create();
        try {
            c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            return c.drawImage(img, x, y, w, h, obs);
        } finally {
            c.dispose();
        }
    }

    static BufferedImage lookup(BufferedImage src, int w, int h) {
        synchronized (LOCK) {
            for (Entry e : CACHE) {
                if (e.w == w && e.h == h && e.src.get() == src) {
                    e.lastUsed = System.nanoTime();
                    return e.img;
                }
            }
            return null;
        }
    }

    /** Girdi yoksa açar (ve async ise üretimi kuyruğa koyar). Yer yoksa null. */
    static Entry request(BufferedImage src, int w, int h, boolean async) {
        final Entry e;
        synchronized (LOCK) {
            long now = System.nanoTime();
            long total = 0;
            for (Iterator<Entry> it = CACHE.iterator(); it.hasNext();) {
                Entry c = it.next();
                if (c.src.get() == null) { it.remove(); continue; }
                if (c.w == w && c.h == h && c.src.get() == src) { c.lastUsed = now; latest = c; return c; }
                total += c.pixels();
            }
            long need = (long) w * h;
            while (total + need > MAX_TOTAL_PIXELS) {
                Entry old = null;
                for (Entry c : CACHE) {
                    if (now - c.lastUsed < RECENT_NANOS) continue;
                    if (old == null || c.lastUsed < old.lastUsed) old = c;
                }
                if (old == null) { log("yer yok, atlandi " + w + "x" + h); return null; }
                CACHE.remove(old);
                if (latest == old) latest = null;
                total -= old.pixels();
                log("tahliye " + old.w + "x" + old.h);
            }
            e = new Entry(src, w, h);
            CACHE.add(e);
            latest = e;
            if (async) {
                if (worker == null) {
                    worker = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ude-bgimage-scaler");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    });
                }
                worker.execute(() -> build(e, true));
            }
        }
        return e;
    }

    private static void build(Entry e, boolean async) {
        BufferedImage src;
        synchronized (LOCK) {
            // Hazır ya da başka iş parçacığında üretiliyor: dokunma (eşzamanlı ekran +
            // bellek-içi istekleri aynı girdiyi iki kez üretmesin, hazır olanı düşürmesin).
            if (e.img != null || e.building) return;
            src = e.src.get();
            // Sürekli zoom: kuyrukta bayatlamış istekleri üretmeden düşür.
            if (src == null || (async && e != latest) || !CACHE.contains(e)) {
                CACHE.remove(e);
                if (latest == e) latest = null;
                return;
            }
            e.building = true;
        }
        try {
            long t0 = System.nanoTime();
            BufferedImage out = scale(src, e.w, e.h);
            synchronized (LOCK) {
                e.building = false;
                if (!CACHE.contains(e)) return;
                e.img = out;
                e.lastUsed = System.nanoTime();
            }
            log("uretildi " + src.getWidth() + "x" + src.getHeight() + " -> " + e.w + "x" + e.h
                + " " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            if (async) {
                EventQueue.invokeLater(() -> {
                    for (Window w : Window.getWindows()) {
                        if (w.isShowing()) w.repaint();
                    }
                });
            }
        } catch (Throwable t) {
            synchronized (LOCK) { e.building = false; CACHE.remove(e); }
            logError(t);
        }
    }

    /** Kaliteli ölçekleme: 2 kattan fazla küçültmede kademeli yarılama, son adım bicubic. */
    static BufferedImage scale(BufferedImage src, int dw, int dh) {
        int type = src.getTransparency() == Transparency.OPAQUE
            ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw / 2 >= dw && ch / 2 >= dh) {
            cw /= 2; ch /= 2;
            cur = resample(cur, cw, ch, type, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return resample(cur, dw, dh, type, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage resample(BufferedImage in, int w, int h, int type, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(in, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static int cacheSize() { synchronized (LOCK) { return CACHE.size(); } }
    static void clear() { synchronized (LOCK) { CACHE.clear(); latest = null; } }

    // --- günlük ---

    private static final boolean LOG = "1".equals(System.getenv("UDE_BGLOG"))
        || Boolean.getBoolean("macosbgimage.debug");

    private static void logError(Throwable t) {
        synchronized (LOCK) {
            if (loggedError) return;
            loggedError = true;
        }
        write("HATA " + t, t);
    }

    private static void log(String s) {
        if (LOG) write(s, null);
    }

    private static synchronized void write(String s, Throwable t) {
        if (!LOG && t == null) return;
        try {
            File f = new File(System.getProperty("user.home"), "Library/Logs/ude-bgimage.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(f, true));
            try {
                pw.println(new Date() + " " + s);
                if (t != null && LOG) t.printStackTrace(pw);
            } finally {
                pw.close();
            }
        } catch (Throwable ignore) {
            // günlük yazılamıyorsa çizimi bozma
        }
    }
}
