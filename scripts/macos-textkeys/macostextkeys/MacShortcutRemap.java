package macostextkeys;

/*
 * UYAP'a özgü (Windows kökenli) Ctrl kısayollarını standart macOS Cmd
 * kısayollarına bağlayan javaagent parçası.
 *
 * Sorun katmanı 1 — alışılmadık tuşlar: Editör Windows için tasarlandığından
 *   kalın = Ctrl+K, italik = Ctrl+T, altı çizili = Ctrl+Shift+A'dır. Mac kullanıcısı
 *   Cmd+B/I/U'ya basınca beklediği olmaz.
 *
 * Sorun katmanı 2 — macOS Emacs gölgelemesi: macOS, metin bileşenlerine yerleşik
 *   Emacs imleç bağlamaları ekler (Ctrl+A satır başı, Ctrl+B harf geri, Ctrl+F harf
 *   ileri, Ctrl+N/P satır alt/üst, Ctrl+O satır ekle, Ctrl+V sayfa aşağı…). Bunlar
 *   odaktaki bileşende, uygulamanın komutlarından ÖNCE çalışır. Yani sentetik bir
 *   Ctrl+A göndermek "tümünü seç" değil "satır başı" yapar.
 *
 * Çözüm: Uygulamanın menü öğeleri gerçek JMenuItem'dır (171 adet, Türkçe etiketli)
 *   ama hiçbirinin hızlandırıcısı (accelerator) yoktur — kısayollar global bir
 *   dinleyiciyle işlenir. Bu yüzden:
 *     • Menüde KARŞILIĞI OLAN komutlar (Yeni, Aç, Kaydet, Yazdır, Bul, Kopyala,
 *       Yapıştır, Kes, Tümünü Seç, Geri Al, Tekrarla): odaktaki pencerenin menü
 *       ağacında etiketle eşleşen etkin JMenuItem bulunur ve doClick() edilir. Bu,
 *       uygulamanın GERÇEK eylemini (zengin-metin yapıştırma vb.) çağırır ve odak
 *       bileşenini kullanmadığından Emacs gölgesini tamamen baypas eder.
 *     • Menüde OLMAYAN biçimlendirme (kalın/italik/altı çizili): uygulamanın kendi
 *       Ctrl tuşlarını ezdiği komutlar olduğundan, odaktaki bileşene sentetik Ctrl
 *       gönderilir (Emacs bunlara dokunmaz).
 *
 * Not: Eklenti yıkıcı değildir; mevcut Ctrl kısayolları aynen çalışır (yalnız Cmd
 *   olayları dinlenir). İzin listesi olduğundan Cmd+Q/W/H/M'ye dokunulmaz.
 */

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

public final class MacShortcutRemap {

    private static final int META  = InputEvent.META_DOWN_MASK;   // Cmd
    private static final int CTRL  = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int ALT   = InputEvent.ALT_DOWN_MASK;

    /** Eşleme sırasında dikkate alınan değiştirici maskeleri. */
    private static final int REL = META | CTRL | SHIFT | ALT;

    /** Menü öğesi bulunamazsa uygulanacak yedek davranış. */
    private enum Fb { SYNTHETIC, SELECT_ALL, COPY, PASTE, CUT, NONE }

    /** Bir Cmd kısayolunun ne yapacağını tanımlayan kayıt. */
    private static final class Map {
        final int srcKey, srcMods;
        final String label;        // null değilse: bu etiketli menü öğesini doClick et
        final int dstKey, dstMods; // SYNTHETIC yedeği için hedef Ctrl tuşu
        final Fb fb;               // menü öğesi yoksa
        Map(int srcKey, int srcMods, String label, int dstKey, int dstMods, Fb fb) {
            this.srcKey = srcKey; this.srcMods = srcMods; this.label = label;
            this.dstKey = dstKey; this.dstMods = dstMods; this.fb = fb;
        }
    }

    /*
     * Etiketler çalışan uygulamanın menü ağacından (Türkçe, accelerator'sız) birebir
     * alınmıştır. Biçimlendirme menüde olmadığından sentetik Ctrl'e (label=null) düşer.
     */
    private static final Map[] MAPS = {
        // — Biçimlendirme: menüde yok → sentetik Ctrl (uygulama bu tuşları ezer) —
        new Map(KeyEvent.VK_B, META,         null,            KeyEvent.VK_K, CTRL,         Fb.SYNTHETIC), // Kalın       ← Ctrl+K
        new Map(KeyEvent.VK_I, META,         null,            KeyEvent.VK_T, CTRL,         Fb.SYNTHETIC), // İtalik      ← Ctrl+T
        new Map(KeyEvent.VK_U, META,         null,            KeyEvent.VK_A, CTRL | SHIFT, Fb.SYNTHETIC), // Altı çizili ← Ctrl+Shift+A

        // — Pano / seçim: menü eylemi (zengin-metin), yedek doğrudan metin API'si —
        new Map(KeyEvent.VK_A, META,         "Tümünü Seç",    0, 0, Fb.SELECT_ALL),
        new Map(KeyEvent.VK_C, META,         "Kopyala",       0, 0, Fb.COPY),
        new Map(KeyEvent.VK_V, META,         "Yapıştır",      0, 0, Fb.PASTE),
        new Map(KeyEvent.VK_X, META,         "Kes",           0, 0, Fb.CUT),

        // — Geri / İleri al —
        new Map(KeyEvent.VK_Z, META,         "Geri Al",       KeyEvent.VK_Z, CTRL, Fb.SYNTHETIC), // Ctrl+Z Emacs'te yok
        new Map(KeyEvent.VK_Z, META | SHIFT, "Tekrarla",      0, 0, Fb.NONE),
        new Map(KeyEvent.VK_Y, META,         "Tekrarla",      0, 0, Fb.NONE),

        // — Dosya —
        new Map(KeyEvent.VK_N, META,         "Yeni",          0, 0, Fb.NONE),
        new Map(KeyEvent.VK_O, META,         "Aç",            0, 0, Fb.NONE),
        new Map(KeyEvent.VK_S, META,         "Kaydet",        KeyEvent.VK_S, CTRL,         Fb.SYNTHETIC), // Ctrl+S Emacs'te yok
        new Map(KeyEvent.VK_S, META | SHIFT, "Farklı Kaydet", KeyEvent.VK_S, CTRL | SHIFT, Fb.SYNTHETIC),
        new Map(KeyEvent.VK_P, META,         "Yazdır",        0, 0, Fb.NONE),
        new Map(KeyEvent.VK_P, META | SHIFT, "Yazdırma Önizlemesi", 0, 0, Fb.NONE),

        // — Bul —
        new Map(KeyEvent.VK_F, META,         "Bul",           0, 0, Fb.NONE),

        // — Yazı tipi paneli (macOS'ta ⌘T kelime işlemcilerde fontu açar) —
        new Map(KeyEvent.VK_T, META,         "Yazı Özellikleri", 0, 0, Fb.NONE),
    };

    private MacShortcutRemap() {}

    static void install() {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(MacShortcutRemap::dispatch);
        } catch (Throwable t) {
            System.err.println("[macos-shortcuts] kurulamadı: " + t);
        }
    }

    /**
     * true = "olayı ben işledim". Yalnız eşleşen Cmd olayları yutulur; geri kalan
     * (Cmd+Q dâhil) ve ürettiğimiz Ctrl olayları (META yok) dokunulmadan geçer.
     */
    static boolean dispatch(KeyEvent e) {
        try {
            int mods = e.getModifiersEx() & REL;
            if ((mods & META) == 0) return false;          // sadece Cmd kaynaklı + döngü guard
            int id = e.getID();
            if (id == KeyEvent.KEY_TYPED) return true;      // Cmd+harf metne yazılmasın
            if (id != KeyEvent.KEY_PRESSED && id != KeyEvent.KEY_RELEASED) return false;

            Map m = lookup(e.getKeyCode(), mods);
            if (m == null) return false;
            if (id == KeyEvent.KEY_PRESSED) perform(m, e);
            return true;                                    // özgün Cmd basma/bırakmayı yut
        } catch (Throwable t) {
            return false;
        }
    }

    static Map lookup(int keyCode, int mods) {
        for (Map m : MAPS) if (m.srcKey == keyCode && m.srcMods == mods) return m;
        return null;
    }

    private static void perform(Map m, KeyEvent e) {
        Component c = focusOwner(e);

        // 1) Menüde karşılığı varsa uygulamanın gerçek eylemini çağır.
        if (m.label != null) {
            JMenuItem mi = findMenuItem(m.label, c);
            if (mi != null) { mi.doClick(0); return; }
        }

        // 2) Yedek.
        switch (m.fb) {
            case SELECT_ALL: if (c instanceof JTextComponent) ((JTextComponent) c).selectAll(); break;
            case COPY:       if (c instanceof JTextComponent) ((JTextComponent) c).copy();      break;
            case PASTE:      if (c instanceof JTextComponent) ((JTextComponent) c).paste();     break;
            case CUT:        if (c instanceof JTextComponent) ((JTextComponent) c).cut();       break;
            case SYNTHETIC:  redispatch(c, e.getWhen(), m.dstKey, m.dstMods);                   break;
            case NONE:       /* Emacs gölgesi: yanlış hareket etmektense hiçbir şey yapma */    break;
        }
    }

    private static Component focusOwner(KeyEvent e) {
        Component c = e.getComponent();
        if (c == null) c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return c;
    }

    /** Hedef Ctrl kısayolunu sentetik olarak odaktaki bileşene gönder. */
    private static void redispatch(Component target, long when, int keyCode, int mods) {
        if (target == null) return;
        EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
        q.postEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED,  when,     mods, keyCode, KeyEvent.CHAR_UNDEFINED));
        q.postEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when + 1, mods, keyCode, KeyEvent.CHAR_UNDEFINED));
    }

    // ——— Menü ağacı tarama ———

    /**
     * Etiketi {@code label} olan ETKİN yaprak menü öğesini bul. Önce odaktaki
     * pencerenin menüsü taranır (çoklu belge), bulunamazsa tüm çerçeveler.
     */
    private static JMenuItem findMenuItem(String label, Component focus) {
        Window w = (focus != null) ? SwingUtilities.getWindowAncestor(focus) : null;
        if (w != null) {
            JMenuItem r = pick(collect(w), label);
            if (r != null) return r;
        }
        List<JMenuItem> all = new ArrayList<>();
        for (Frame f : Frame.getFrames()) scanWindow(f, all);
        return pick(all, label);
    }

    private static JMenuItem pick(List<JMenuItem> items, String label) {
        for (JMenuItem mi : items) {
            String t = mi.getText();
            if (t != null && label.equals(t.trim()) && mi.isEnabled()) return mi;
        }
        return null;
    }

    private static List<JMenuItem> collect(Window w) {
        List<JMenuItem> out = new ArrayList<>();
        scanWindow(w, out);
        return out;
    }

    private static void scanWindow(Window w, List<JMenuItem> out) {
        if (w instanceof JFrame) {
            JMenuBar mb = ((JFrame) w).getJMenuBar();
            if (mb != null) scan(mb, out);
        }
        scan(w, out);
    }

    private static void scan(Component c, List<JMenuItem> out) {
        if (c instanceof JMenuItem && !(c instanceof JMenu)) out.add((JMenuItem) c);
        if (c instanceof JMenu) {
            for (Component sub : ((JMenu) c).getMenuComponents()) scan(sub, out);
        } else if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) scan(child, out);
        }
    }
}
