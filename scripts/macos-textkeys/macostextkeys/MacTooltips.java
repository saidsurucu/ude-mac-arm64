package macostextkeys;

/*
 * Ribbon butonlarının zengin-tooltip (RichTooltip) başlıklarındaki Windows kısayollarını
 * Mac kısayollarıyla değiştirir. UDE Windows kökenli olduğundan tooltip'ler "Kaydet
 * (Shift+Ctrl+K)" gibi Windows kombolarını gösterir; oysa Mac'te kullanıcı [[MacShortcutRemap]]
 * sayesinde ⌘S basar. Bu sınıf gösterimi gerçek Mac kısayoluna çevirir.
 *
 * İki strateji:
 *   1) Başlıkta BİLİNEN bir Windows kısayolu (ör. "(Ctrl+K)") varsa onu Mac karşılığıyla
 *      ("(⌘B)") değiştir. Eşleme Windows kombosuna göredir çünkü Mac tuşu farklıdır
 *      (Kalın: Win Ctrl+K → Mac ⌘B; Yazı Özellikleri: Win Ctrl+I → Mac ⌘T; vb.).
 *   2) Hizalama butonlarında tooltip'te kısayol yoktur → buton METNİNDEN bulup Mac
 *      kısayolunu başlığa ekle ("Sola Yasla" → "Sola Yasla (⌘L)").
 *
 * Mekanizma: Her belge kendi JFrame'i + kendi Ribbon'ıdır. İlk FOCUS_GAINED'de o
 *   pencerenin tüm Flamingo AbstractCommandButton'ları gezilir (pencere başına bir kez;
 *   buton bulunana dek tekrar denenir). Agent jar Flamingo'suz derlendiğinden tüm erişim
 *   reflection'ladır. Yıkıcı değildir: yalnız gösterilen başlık metni değişir; eşleşmeyen
 *   buton dokunulmadan kalır (idempotent — Mac sembolü zaten varsa atlanır).
 */

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.SwingUtilities;

public final class MacTooltips {

    private static final String ACB = "org.pushingpixels.flamingo.api.common.AbstractCommandButton";
    private static final String CMD = "⌘";  // ⌘
    private static final String SH  = "⇧";  // ⇧

    /** Başlıktaki Windows kısayol metni → Mac gösterimi (gerçek remap'e göre). */
    private static final Map<String, String> WIN2MAC = new LinkedHashMap<>();
    /** Kısayol göstermeyen buton metni → eklenecek Mac gösterimi (hizalama). */
    private static final Map<String, String> TEXT2MAC = new LinkedHashMap<>();
    static {
        // Dosya / pano / düzenleme — menüye bağlı remap'ler
        WIN2MAC.put("(Ctrl+N)",       CMD + "N");          // Yeni
        WIN2MAC.put("(Ctrl+O)",       CMD + "O");          // Aç
        WIN2MAC.put("(Shift+Ctrl+K)", CMD + "S");          // Kaydet
        WIN2MAC.put("(Shift+Ctrl+S)", CMD + SH + "S");     // Farklı Kaydet
        WIN2MAC.put("(Ctrl+P)",       CMD + "P");          // Yazdır
        WIN2MAC.put("(Shift+Ctrl+P)", CMD + SH + "P");     // Yazdırma Önizlemesi
        WIN2MAC.put("(Ctrl+F)",       CMD + "F");          // Bul
        WIN2MAC.put("(Ctrl+C)",       CMD + "C");          // Kopyala
        WIN2MAC.put("(Ctrl+V)",       CMD + "V");          // Yapıştır
        WIN2MAC.put("(Ctrl+X)",       CMD + "X");          // Kes
        WIN2MAC.put("(Ctrl+A)",       CMD + "A");          // Tümünü Seç
        WIN2MAC.put("(Ctrl+Z)",       CMD + "Z");          // Geri Al
        WIN2MAC.put("(Ctrl+Y)",       CMD + SH + "Z");     // Tekrarla (Mac redo = ⌘⇧Z)
        // Biçimlendirme — Mac tuşu Windows'tan FARKLI
        WIN2MAC.put("(Ctrl+K)",       CMD + "B");          // Kalın        (Win Ctrl+K → ⌘B)
        WIN2MAC.put("(Ctrl+T)",       CMD + "I");          // İtalik       (Win Ctrl+T → ⌘I)
        WIN2MAC.put("(Shift+Ctrl+A)", CMD + "U");          // Altı Çizili  (Win Shift+Ctrl+A → ⌘U)
        WIN2MAC.put("(Ctrl+I)",       CMD + "T");          // Yazı Özellikleri (Win Ctrl+I → ⌘T)
        WIN2MAC.put("(Ctrl+UP)",      CMD + SH + "A");     // BÜYÜK HARF   (Win Ctrl+Up → ⌘⇧A)
        WIN2MAC.put("(Ctrl+Up)",      CMD + SH + "A");
        // Font boyutu — Win Shift+Ctrl+↑/↓ → Mac ⌘⇧. / ⌘⇧,
        WIN2MAC.put("(Shift+Ctrl+UP)",   CMD + SH + ".");  // Font Büyüt
        WIN2MAC.put("(Shift+Ctrl+DOWN)", CMD + SH + ",");  // Font Küçült
        // UDE'ye özel komutlar (Ctrl→⌘)
        WIN2MAC.put("(Ctrl+G)",       CMD + "G");          // Paragraf Özellikleri
        WIN2MAC.put("(Shift+Ctrl+R)", CMD + SH + "R");     // Stil Özellikleri
        WIN2MAC.put("(Shift+Ctrl+J)", CMD + SH + "J");     // Türünü Değiştir
        WIN2MAC.put("(Shift+Ctrl+G)", CMD + SH + "G");     // İmzalar
        WIN2MAC.put("(Shift+Ctrl+D)", CMD + SH + "D");     // Sayfa Düzenle
        WIN2MAC.put("(Shift+Ctrl+B)", CMD + SH + "B");     // Biçim Kopyala

        // Kısayol göstermeyen butonlar — metinden eşle
        TEXT2MAC.put("Sola Yasla",     CMD + "L");
        TEXT2MAC.put("Ortala",         CMD + "E");
        TEXT2MAC.put("Sağa Yasla",     CMD + "R");
        TEXT2MAC.put("İki Yana Yasla", CMD + "J");
        TEXT2MAC.put("Köprü Ekle",     CMD + "K");
    }

    private static final WeakHashMap<Window, Boolean> PATCHED = new WeakHashMap<>();

    private MacTooltips() {}

    static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (!(src instanceof Component)) return;
                    Window w = SwingUtilities.getWindowAncestor((Component) src);
                    if (w == null || Boolean.TRUE.equals(PATCHED.get(w))) return;
                    // Ribbon kurulumunun tamamlanması için EDT kuyruğunun sonuna bırak.
                    SwingUtilities.invokeLater(() -> patchWindow(w));
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
        } catch (Throwable t) {
            System.err.println("[macos-tooltips] kurulamadı: " + t);
        }
    }

    private static void patchWindow(Window w) {
        try {
            if (Boolean.TRUE.equals(PATCHED.get(w))) return;
            int[] n = {0};
            walk(w, n);
            if (n[0] > 0) PATCHED.put(w, Boolean.TRUE);  // bulunduysa kalıcı işaretle
        } catch (Throwable ignore) {
            // Tooltip yaması asla EDT'yi düşürmemeli.
        }
    }

    private static void walk(Component c, int[] n) {
        if (isCommandButton(c)) patchButton(c, n);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) walk(child, n);
        }
        if (c instanceof Window) {
            for (Window ow : ((Window) c).getOwnedWindows()) walk(ow, n);
        }
    }

    /** Bir command-button'ın actionRichTooltip başlığını gerekiyorsa yeniden yazar. */
    private static void patchButton(Component b, int[] n) {
        Object rt = richTooltip(b);
        if (rt == null) return;
        String title = call(rt, "getTitle");
        if (title == null || title.isEmpty() || title.contains(CMD)) return;  // zaten Mac

        // Strateji 1: bilinen Windows kısayolunu Mac ile değiştir.
        for (Map.Entry<String, String> en : WIN2MAC.entrySet()) {
            if (title.contains(en.getKey())) {
                setTitle(rt, title.replace(en.getKey(), "(" + en.getValue() + ")"));
                n[0]++;
                return;
            }
        }
        // Strateji 2: kısayolsuz hizalama butonu → metinden ekle.
        String text = call(b, "getText");
        if (text != null) {
            String mac = TEXT2MAC.get(text.trim());
            if (mac != null) {
                setTitle(rt, title + " (" + mac + ")");
                n[0]++;
            }
        }
    }

    // ——— reflection yardımcıları ———

    private static boolean isCommandButton(Object o) {
        if (o == null) return false;
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            if (ACB.equals(k.getName())) return true;
        }
        return false;
    }

    private static Object richTooltip(Object o) {
        try {
            Field f = findField(o.getClass(), "actionRichTooltip");
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setTitle(Object richTooltip, String title) {
        try {
            Method m = richTooltip.getClass().getMethod("setTitle", String.class);
            m.invoke(richTooltip, title);
        } catch (Throwable ignore) {}
    }

    private static String call(Object o, String method) {
        try {
            Object r = o.getClass().getMethod(method).invoke(o);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> k, String name) {
        for (; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignore) {}
        }
        return null;
    }
}
