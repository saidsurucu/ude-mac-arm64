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
 *       İSTİSNA: odak editör DIŞI bir metin alanındaysa (arama kutusu, e-imza
 *       formu…) menü eylemi yanlış hedefe (editöre) işler → pano/seçim komutu
 *       doğrudan odaktaki alana uygulanır (performLocal/isEditor).
 *     • Menüde OLMAYAN biçimlendirme (kalın/italik/altı çizili): uygulamanın kendi
 *       Ctrl tuşlarını ezdiği komutlar olduğundan, odaktaki bileşene sentetik Ctrl
 *       gönderilir (Emacs bunlara dokunmaz).
 *
 * Not: Eklenti yıkıcı değildir; mevcut Ctrl kısayolları aynen çalışır (yalnız Cmd
 *   olayları dinlenir). İzin listesi olduğundan Cmd+Q/H (çık/gizle) macOS'a bırakılır;
 *   bunları sistem zaten doğru işler. Cmd+W ise Swing'de varsayılan olarak hiçbir şeye
 *   bağlı değildir → odaktaki pencereye WINDOW_CLOSING gönderilir (UDE'de "Kapat" menü
 *   öğesi yoktur; her belge kendi penceresidir). Cmd+M (küçült) de macOS'a bırakıldığında
 *   tetiklenmiyordu (uygulamanın kendi global tuş dinleyicisi yutuyor) → olay yakalanıp
 *   odaktaki Frame doğrudan ICONIFIED yapılır.
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
import java.awt.event.WindowEvent;
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
    private enum Fb { SYNTHETIC, SELECT_ALL, COPY, PASTE, PLAIN_PASTE, CUT, CLOSE_WINDOW, MINIMIZE, NONE }

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

        // — Büyük harf: ⌘⇧A BİLEREK BAĞLANMADI. macOS'ta ⌘⇧A, Terminal'in "Search man
        //   Page Index in Terminal" servisinin (searchManPages) varsayılan key-equivalent'i.
        //   Servis çağrıları AppKit'in performKeyEquivalent: katmanında, tuş Java'nın
        //   KeyboardFocusManager'ına ULAŞMADAN önce çözülür → dispatcher'da true döndürmek
        //   servisi durduramaz. Servis seçimi alıp `x-man-page://<seçim>;type=a` kurar →
        //   Terminal `man "<seçim>;type=a"` açar (kullanıcı şikâyeti: seçili harfi büyütünce
        //   sarı Terminal penceresi). Bu yüzden ⌘⇧A'yı YENİDEN EKLEME. UDE'nin "Harf Modu
        //   Değiştir"i Shift+F3'tür (Cmd içermez → Mac'te yerel çalışır, küçük/Baş Harf/BÜYÜK
        //   döngüsü) ve büyük harf ihtiyacını zaten karşılar. —

        // — Pano / seçim: menü eylemi (zengin-metin), yedek doğrudan metin API'si —
        new Map(KeyEvent.VK_A, META,         "Tümünü Seç",    0, 0, Fb.SELECT_ALL),
        new Map(KeyEvent.VK_C, META,         "Kopyala",       0, 0, Fb.COPY),
        new Map(KeyEvent.VK_V, META,         "Yapıştır",      0, 0, Fb.PASTE),
        // Formatsız Yapıştır (Word ⌘⇧V): menüde yok → fb. Editörde reflection ile
        // macospasterich.PlainPaste; editör-dışı alanlarda normal yapıştırma.
        new Map(KeyEvent.VK_V, META | SHIFT, null,            0, 0, Fb.PLAIN_PASTE),
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

        // — Pencere kapat (macOS Cmd+W). UDE'de her belge kendi Frame'idir; "Kapat"
        //   menü öğesi YOKTUR (yalnız "Çıkış" = tüm uygulama). Bu yüzden odaktaki
        //   pencereye WINDOW_CLOSING gönderilir = kırmızı kapat düğmesiyle birebir aynı
        //   (kaydedilmemişse uygulamanın kendi "kaydet?" akışı çalışır). —
        new Map(KeyEvent.VK_W, META,         null,            0, 0, Fb.CLOSE_WINDOW),

        // — Pencereyi küçült (macOS ⌘M). Sistem'e bırakıldığında uygulamanın global
        //   tuş dinleyicisi olayı yutuyor → odaktaki Frame doğrudan ICONIFIED yapılır. —
        new Map(KeyEvent.VK_M, META,         null,            0, 0, Fb.MINIMIZE),

        // — Yazı tipi paneli (macOS'ta ⌘T kelime işlemcilerde fontu açar) —
        new Map(KeyEvent.VK_T, META,         "Yazı Özellikleri", 0, 0, Fb.NONE),
    };

    /**
     * Klavye kısayolu OLMAYAN (yalnız Ribbon araç çubuğunda butonu bulunan) komutlar.
     * UDE'de bu işlevlerin klavye kısayolu ve menü öğesi yok; yalnız Flamingo
     * JCommandButton/JCommandToggleButton var. Bu yüzden butonu görünen metninden bulup
     * doActionClick() ederiz. Butonlar genelde "Giriş" sekmesindedir ama Flamingo tüm
     * sekme bantlarını bellekte tuttuğundan sekme etkin olmasa da bileşen ağacında bulunur.
     *
     * Tuş seçimleri (Mac Word + Türkçe Apple klavye uyumu):
     *   - Hizalama ⌘L/E/R/J: Mac Word standardı.
     *   - Font büyüt/küçült ⌘⇧./⌘⇧,: Word'de ⌘⇧>/< nokta/virgül tuşundadır; Türkçe
     *     klavyede [ ] = Option+8/9 olduğundan parantez seti kullanışsız → nokta/virgül.
     *   - Köprü ⌘K: Mac Word standardı (Insert Hyperlink).
     */
    private static final class CmdMap {
        final int srcKey;       // keyCode ile eşleşme (char tabanlıysa -1)
        final char srcChar;     // keyChar ile eşleşme (keyCode tabanlıysa 0)
        final int srcMods;
        final String buttonText;
        CmdMap(int srcKey, int srcMods, String buttonText) {
            this.srcKey = srcKey; this.srcChar = 0; this.srcMods = srcMods; this.buttonText = buttonText;
        }
        CmdMap(char srcChar, int srcMods, String buttonText) {
            this.srcKey = -1; this.srcChar = srcChar; this.srcMods = srcMods; this.buttonText = buttonText;
        }
    }

    /*
     * Not — font boyutu keyChar ile eşlenir, keyCode ile DEĞİL: Türkçe Apple klavyede
     * nokta/virgül tuşları Java'ya VK_SLASH(47)/VK_BACK_SLASH(92) olarak gelir (US'te
     * VK_PERIOD/VK_COMMA). keyChar ise her düzende doğru: Türkçe '.'/',' , US '>'/'<'.
     */
    private static final CmdMap[] CMD_MAPS = {
        new CmdMap(KeyEvent.VK_L, META,         "Sola Yasla"),     // ⌘L  sola hizala
        new CmdMap(KeyEvent.VK_E, META,         "Ortala"),         // ⌘E  ortala
        new CmdMap(KeyEvent.VK_R, META,         "Sağa Yasla"),     // ⌘R  sağa hizala
        new CmdMap(KeyEvent.VK_J, META,         "İki Yana Yasla"), // ⌘J  iki yana yasla
        new CmdMap(KeyEvent.VK_K, META,         "Köprü Ekle"),     // ⌘K  köprü ekle
        new CmdMap('.',           META | SHIFT, "Font Büyüt"),     // ⌘⇧.  (TR) font büyüt
        new CmdMap('>',           META | SHIFT, "Font Büyüt"),     // ⌘⇧>  (US)
        new CmdMap(',',           META | SHIFT, "Font Küçült"),    // ⌘⇧,  (TR) font küçült
        new CmdMap('<',           META | SHIFT, "Font Küçült"),    // ⌘⇧<  (US)

        // — UDE'ye özel komutlar: Ctrl→⌘ (ribbon butonu olanlar; doClick) —
        new CmdMap(KeyEvent.VK_G, META,         "Paragraf Özellikleri"), // ⌘G   (Win Ctrl+G)
        new CmdMap(KeyEvent.VK_R, META | SHIFT, "Stil Özellikleri"),     // ⌘⇧R  (Win Shift+Ctrl+R)
        new CmdMap(KeyEvent.VK_J, META | SHIFT, "Türünü Değiştir"),      // ⌘⇧J  (Win Shift+Ctrl+J)
        new CmdMap(KeyEvent.VK_G, META | SHIFT, "İmzalar"),             // ⌘⇧G  (Win Shift+Ctrl+G)
        new CmdMap(KeyEvent.VK_D, META | SHIFT, "Sayfa Düzenle"),       // ⌘⇧D  (Win Shift+Ctrl+D)
        new CmdMap(KeyEvent.VK_B, META | SHIFT, "Biçim Kopyala"),       // ⌘⇧B  (Win Shift+Ctrl+B)
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
            if (m != null) {
                if (id == KeyEvent.KEY_PRESSED) perform(m, e);
                return true;                                // özgün Cmd basma/bırakmayı yut
            }
            CmdMap cm = cmdLookup(e, mods);
            if (cm != null) {
                if (id == KeyEvent.KEY_PRESSED) clickCommandButton(cm.buttonText, focusOwner(e));
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static Map lookup(int keyCode, int mods) {
        for (Map m : MAPS) if (m.srcKey == keyCode && m.srcMods == mods) return m;
        return null;
    }

    static CmdMap cmdLookup(KeyEvent e, int mods) {
        for (CmdMap m : CMD_MAPS) {
            if (m.srcMods != mods) continue;
            if (m.srcChar != 0) { if (m.srcChar == e.getKeyChar()) return m; }
            else if (m.srcKey == e.getKeyCode()) return m;
        }
        return null;
    }

    // ——— Flamingo ribbon butonu (AbstractCommandButton) reflection köprüsü ———
    //     Agent jar Flamingo'suz derlendiğinden tüm erişim reflection'ladır.

    private static final String ACB = "org.pushingpixels.flamingo.api.common.AbstractCommandButton";

    /** Görünen metni {@code text} olan ETKİN command-button'ı bulup eylemini tetikler. */
    private static void clickCommandButton(String text, Component focus) {
        Object b = findCommandButton(text, focus);
        if (b == null) return;
        try {
            b.getClass().getMethod("doActionClick").invoke(b);
        } catch (Throwable ignore) {
            // doActionClick yoksa/başarısızsa sessizce geç (uygulamayı düşürme).
        }
    }

    private static Object findCommandButton(String text, Component focus) {
        // Önce odaktaki pencere, sonra tüm çerçeveler (görünür olmayanlar dâhil).
        Window w = (focus != null) ? SwingUtilities.getWindowAncestor(focus) : null;
        if (w != null) {
            Object r = scanCmd(w, text);
            if (r != null) return r;
        }
        for (Frame f : Frame.getFrames()) {
            Object r = scanCmd(f, text);
            if (r != null) return r;
        }
        return null;
    }

    private static Object scanCmd(Component c, String text) {
        if (isCommandButton(c) && c.isEnabled() && text.equals(cmdText(c))) return c;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                Object r = scanCmd(child, text);
                if (r != null) return r;
            }
        }
        if (c instanceof Window) {
            for (Window ow : ((Window) c).getOwnedWindows()) {
                Object r = scanCmd(ow, text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isCommandButton(Object o) {
        if (o == null) return false;
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            if (ACB.equals(k.getName())) return true;
        }
        return false;
    }

    private static String cmdText(Object o) {
        try {
            Object r = o.getClass().getMethod("getText").invoke(o);
            return r == null ? null : r.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void perform(Map m, KeyEvent e) {
        Component c = focusOwner(e);

        // 0) Odak, editör DIŞI bir metin alanındaysa (şerit arama kutusu, e-imza
        //    formu girdileri vb.) pano/seçim komutları menüye GİTMEZ: menüdeki
        //    Yapıştır/Kopyala/Kes/Tümünü Seç eylemleri odaktan bağımsız her zaman
        //    EDITÖR belgesine işler; doğru hedef odaktaki alanın kendisidir.
        if (c instanceof JTextComponent && !isEditor(c)
                && performLocal(m.fb, (JTextComponent) c)) return;

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
            case PLAIN_PASTE:
                if (c instanceof JTextComponent) {
                    try {
                        Class.forName("macospasterich.PlainPaste")
                             .getMethod("paste", JTextComponent.class)
                             .invoke(null, (JTextComponent) c);
                    } catch (Throwable ignore) {
                        ((JTextComponent) c).paste();   // PASTERICH yoksa normal yapıştır
                    }
                }
                break;
            case CUT:        if (c instanceof JTextComponent) ((JTextComponent) c).cut();       break;
            case CLOSE_WINDOW: closeFocusedWindow(c);                                           break;
            case MINIMIZE:   minimizeFocusedWindow(c);                                          break;
            case SYNTHETIC:  redispatch(c, e.getWhen(), m.dstKey, m.dstMods);                   break;
            case NONE:       /* Emacs gölgesi: yanlış hareket etmektense hiçbir şey yapma */    break;
        }
    }

    /** Pano/seçim komutunu doğrudan odaktaki alana uygula; uygulanamadıysa false. */
    private static boolean performLocal(Fb fb, JTextComponent tc) {
        switch (fb) {
            case SELECT_ALL: tc.selectAll(); return true;
            case COPY:       tc.copy();      return true;
            case PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
            case PLAIN_PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
            case CUT:   if (tc.isEditable() && tc.isEnabled()) { tc.cut();   return true; } return false;
            default: return false;
        }
    }

    /**
     * UDE belge editörü mü? Editör zinciri: text.t → fi → hj → wp.prof.b.c →
     * wp.a.f → JTextPane; wp.a.f UDE'nin zengin-metin tabanıdır. Editörde pano
     * komutları menü eyleminden (zengin-metin yapıştırma) akmalı, düz
     * JTextComponent.paste() biçimlendirmeyi kaybeder. Sınıf adıyla bakılır
     * (agent jar uygulama sınıflarına classpath'siz derlenir).
     */
    private static boolean isEditor(Component c) {
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("tr.com.havelsan.uyap.system.swing.wp.a.f")
                    || n.equals("tr.com.havelsan.uyap.system.editor.common.text.hj")) return true;
        }
        return false;
    }

    private static Component focusOwner(KeyEvent e) {
        Component c = e.getComponent();
        if (c == null) c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return c;
    }

    /**
     * Odaktaki pencereyi, kullanıcı kırmızı kapat düğmesine basmış gibi kapatır:
     * WINDOW_CLOSING gönderir → pencerenin kendi WindowListener'ı / defaultCloseOperation'ı
     * çalışır (kaydedilmemiş belge için "kaydet?" sorusu dâhil). Sert dispose YAPMAZ.
     */
    private static void closeFocusedWindow(Component c) {
        Window w = (c != null) ? SwingUtilities.getWindowAncestor(c) : null;
        if (w == null) w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (w == null) return;
        w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
    }

    /**
     * Odaktaki pencereyi Dock'a küçültür: pencere ağacında en yakın Frame'i bulup
     * ICONIFIED durumuna alır (kullanıcı sarı küçült düğmesine basmış gibi). Frame
     * olmayan pencerelerde (diyalog vb.) sahibi olan Frame'e tırmanılır.
     */
    private static void minimizeFocusedWindow(Component c) {
        Window w = (c != null) ? SwingUtilities.getWindowAncestor(c) : null;
        if (w == null) w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        while (w != null && !(w instanceof Frame)) w = w.getOwner();
        if (w instanceof Frame) {
            Frame f = (Frame) w;
            f.setExtendedState(f.getExtendedState() | Frame.ICONIFIED);
        }
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
