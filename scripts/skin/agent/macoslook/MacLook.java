package macoslook;

/*
 * UDE görünüm javaagent'ı (SKIN=1 paketiyle gelir).
 *  (a) Ana JFrame'e bütünleşik başlık çubuğu: apple.awt.fullWindowContent +
 *      apple.awt.transparentTitleBar (Zulu 11'de pencere açıldıktan sonra
 *      dinamik uygulanabildiği FwcProbe ile doğrulandı: DYNAMIC_FWC=true)
 *      + JRibbon'a trafik ışıkları için sol içlik.
 *  (b) Durum çubuğundaki WebMemoryBar kaldırılır.
 * Uygulama System.err'i yuttuğu için log /tmp/macos-look-agent.log dosyasına
 * yazılır (-Dmacoslook.debug=1 ile). Agent hiçbir koşulda uygulamayı düşürmez.
 */

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class MacLook {

    /** FwcProbe karar kapısı sonucu: "full" | "transparent". */
    private static final String FWC_MODE = "full";
    /** Trafik ışıkları için klasik menü çubuğu sol içliği (px). */
    private static final int TRAFFIC_INSET = 72;
    /** Ribbon modunda (klasik menü çubuğu yokken) orb'u trafik ışıklarının
     *  altına indirmek için ribbon ÜST içliği (px). Klasik menü çubuğu 23px;
     *  28 hem ışıkları güvenle temizler hem klasik görünüme yakın durur. */
    private static final int TITLEBAR_TOP_INSET = 28;
    private static final boolean DEBUG = "1".equals(System.getProperty("macoslook.debug"));
    private static final PrintStream LOG = DEBUG ? openLog() : null;

    private MacLook() {}

    private static PrintStream openLog() {
        try {
            return new PrintStream(new FileOutputStream("/tmp/macos-look-agent.log", true), true, "UTF-8");
        } catch (Exception e) { return System.err; }
    }

    private static void log(String m) {
        if (LOG != null) { try { LOG.println("[macos-look] " + m); } catch (Throwable ignore) {} }
    }

    public static void premain(String args, Instrumentation inst) { install(); }
    public static void agentmain(String args, Instrumentation inst) { install(); }

    private static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != WindowEvent.WINDOW_OPENED) return;
                    Object src = e.getSource();
                    if (!(src instanceof JFrame)) return;
                    JFrame f = (JFrame) src;
                    SwingUtilities.invokeLater(() -> {
                        try { unifyTitleBar(f); } catch (Throwable t) { log("titlebar: " + t); }
                        try { hookTitle(f); } catch (Throwable t) { log("titlehook: " + t); }
                        try { removeMemoryBar(f); } catch (Throwable t) { log("membar: " + t); }
                        try { fixRulerBackground(f); } catch (Throwable t) { log("ruler: " + t); }
                        try { boldTaskTabs(f); } catch (Throwable t) { log("tabfont: " + t); }
                        try { removeScopeCombo(f); } catch (Throwable t) { log("scopecombo: " + t); }
                        try { addDarkPageToggle(f); } catch (Throwable t) { log("darkpage: " + t); }
                        try { addColorModeCombo(f); } catch (Throwable t) { log("colormode: " + t); }
                    });
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
            log("yüklendi (mode=" + FWC_MODE + ")");
        } catch (Throwable t) {
            log("kurulamadı: " + t);
        }
    }

    private static void unifyTitleBar(JFrame f) {
        // Yalnız ana editör penceresi: içinde JRibbon olan çerçeve.
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) { log("JRibbon yok, atlandı: " + f.getTitle()); return; }
        JComponent root = f.getRootPane();
        if (Boolean.TRUE.equals(root.getClientProperty("macoslook.done"))) return;
        root.putClientProperty("macoslook.done", Boolean.TRUE);
        root.putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
        if ("full".equals(FWC_MODE)) {
            root.putClientProperty("apple.awt.fullWindowContent", Boolean.TRUE);
            // Ribbon'a trafik-ışığı SOL içliği UYGULANMAZ (kullanıcı isteği: şerit
            // hep sol kenardan başlasın, soldaki yatay boşluk olmasın). Bunun
            // yerine ribbon ÜST içlikle aşağı itilir (syncRibbonTopInset) ki orb
            // klasik moddaki gibi trafik ışıklarının ALTINA insin. Klasik menü
            // çubuğu kendi SOL içliğini insetMenuBar'dan alır.
            insetMenuBar(f);
            syncRibbonTopInset(f);
            installMenuBarWatcher(f);
        }
        java.awt.Color bg = UIManager.getColor("Panel.background");
        if (bg != null) f.setBackground(bg);
        root.revalidate();
        f.repaint();
        log("titlebar bütünleşti: " + f.getTitle());
    }

    /** "Klasik görünüme geç" açıkken UDE şeritin üstüne klasik in-window
     *  JMenuBar'ı (Dosya/Giriş/… mnemonikli) gösterir. fullWindowContent ile bu
     *  çubuk da trafik ışıklarının altına düşer → "Dosya" ışıkların ALTINA biner
     *  (kullanıcının "sol üst köşede menüler iç içe" şikâyeti). Ribbon gibi menü
     *  çubuğunu da trafik-ışığı sol içliğiyle iteriz. Menü çubuğu örneği iki
     *  görünümde de aynıdır (ribbon modunda 0-yükseklik gizli, klasik modda
     *  23px görünür; her iki probe ile doğrulandı) → açılışta içlik uygulamak
     *  klasik moda geçişte de geçerli kalır. Sol içlik bordürü menü öğelerini
     *  çubuğun KENDİ düzeninde sağa kaydırır; çubuğun mutlak bounds'u (UDE'nin
     *  null-layout JLayeredPane'i [0,0,w,23]) değişmez. İçlik canlı dynamic-attach
     *  ile doğrulandı: "Dosya" x=0 → x=72, ışıkları temizledi. Idempotans:
     *  client property guard. */
    private static void insetMenuBar(JFrame f) {
        javax.swing.JMenuBar mb = f.getJMenuBar();
        if (mb == null) { log("menü çubuğu yok"); return; }
        if (Boolean.TRUE.equals(mb.getClientProperty("macoslook.mbinset"))) return;
        mb.putClientProperty("macoslook.mbinset", Boolean.TRUE);
        javax.swing.border.Border ob = mb.getBorder();
        javax.swing.border.Border inset =
            javax.swing.BorderFactory.createEmptyBorder(0, TRAFFIC_INSET, 0, 0);
        mb.setBorder(ob == null ? inset
            : javax.swing.BorderFactory.createCompoundBorder(inset, ob));
        mb.revalidate();
        mb.repaint();
        log("menü çubuğu insetlendi");
    }

    /** Ribbon ÜST içliğini menü görünüm moduna göre ayarlar.
     *  - KLASİK mod (menü çubuğu GÖRÜNÜR): ribbon UI zaten üstte menü çubuğu
     *    için yer ayırdığından orb ışıkların altında → ek içlik = 0.
     *  - RIBBON mod (menü çubuğu GÖRÜNMEZ): hiç yer ayrılmaz, orb tepede
     *    ışıklarla çakışır → üst içlik = TITLEBAR_TOP_INSET ile orb aşağı iner.
     *  KRİTİK: ribbon modunda menü çubuğu 0-yükseklik OLMAZ (yükseklik 23 kalır),
     *  UDE onu setVisible(false) ile GİZLER → mod ayrımı `isVisible()` ile
     *  yapılır (getHeight DEĞİL; canlı StateA probe ile doğrulandı: ribbon
     *  modunda h=23 ama vis=false). Üst border'ın orb'u tam border yüksekliği
     *  kadar aşağı ittiği FixProbe ile doğrulandı (orb ekran-y 76→104, top=28).
     *  Taban border bir kez saklanır (macoslook.ribbonBase); mod geçişlerinde
     *  compound border ÜST ÜSTE binmesin diye her seferinde tabandan kurulur.
     *  İdempotans: uygulanan içlik macoslook.ribbonTop'ta tutulur. */
    private static void syncRibbonTopInset(JFrame f) {
        Component ribbon = findByClassName(f, "JRibbon");
        if (!(ribbon instanceof JComponent)) return;
        JComponent r = (JComponent) ribbon;
        javax.swing.JMenuBar mb = f.getJMenuBar();
        boolean classic = mb != null && mb.isVisible();
        int top = classic ? 0 : TITLEBAR_TOP_INSET;
        Object appliedO = r.getClientProperty("macoslook.ribbonTop");
        int applied = (appliedO instanceof Integer) ? (Integer) appliedO : -1;
        if (applied == top) return;
        if (!Boolean.TRUE.equals(r.getClientProperty("macoslook.ribbonBaseSet"))) {
            r.putClientProperty("macoslook.ribbonBase", r.getBorder()); // null olabilir
            r.putClientProperty("macoslook.ribbonBaseSet", Boolean.TRUE);
        }
        javax.swing.border.Border base =
            (javax.swing.border.Border) r.getClientProperty("macoslook.ribbonBase");
        javax.swing.border.Border out;
        if (top == 0) {
            out = base;
        } else {
            javax.swing.border.Border insetB =
                javax.swing.BorderFactory.createEmptyBorder(top, 0, 0, 0);
            out = (base == null) ? insetB
                : javax.swing.BorderFactory.createCompoundBorder(insetB, base);
        }
        r.setBorder(out);
        r.putClientProperty("macoslook.ribbonTop", Integer.valueOf(top));
        r.revalidate();
        r.repaint();
        log("ribbon üst içlik = " + top + " (klasik=" + classic + ")");
    }

    /** Menü çubuğunun GÖRÜNÜRLÜK değişimini (klasik ↔ ribbon geçişi) izleyip
     *  ribbon üst içliğini yeniden senkronlar. UDE mod geçişinde menü çubuğunu
     *  setVisible ile gösterir/gizler (yeniden BOYUTLANDIRMAZ) → componentResized
     *  DEĞİL, componentShown/componentHidden tetiklenir; ikisi de dinlenir
     *  (resized de güvenlik için). Menü çubuğu örneği iki modda da aynıdır →
     *  dinleyici geçişte de geçerli. invokeLater ile UDE'nin yeniden yerleşimi
     *  oturduktan SONRA uygulanır. İdempotans: rootpane client property guard. */
    private static void installMenuBarWatcher(JFrame f) {
        javax.swing.JMenuBar mb = f.getJMenuBar();
        if (mb == null) return;
        JComponent root = f.getRootPane();
        if (Boolean.TRUE.equals(root.getClientProperty("macoslook.mbwatch"))) return;
        root.putClientProperty("macoslook.mbwatch", Boolean.TRUE);
        java.awt.event.ComponentAdapter ca = new java.awt.event.ComponentAdapter() {
            private void resync() {
                SwingUtilities.invokeLater(() -> {
                    try { syncRibbonTopInset(f); } catch (Throwable t) { log("mbwatch: " + t); }
                });
            }
            @Override public void componentShown(java.awt.event.ComponentEvent e) { resync(); }
            @Override public void componentHidden(java.awt.event.ComponentEvent e) { resync(); }
            @Override public void componentResized(java.awt.event.ComponentEvent e) { resync(); }
        };
        mb.addComponentListener(ca);
        log("menü çubuğu izleyicisi kuruldu");
    }

    /** Yerel macOS başlık METNİ şeffaf başlık çubuğunda da çizilir; macOS onu
     *  pencere genişliğine göre ortalar/kaydırır ve dar pencerede hızlı erişim
     *  ikonlarının ÜSTÜNE düşürür (UDE'nin başlığa eklediği ~100 boşlukluk
     *  sağa-itme dolgusu yalnız geniş pencerede tutar; Zulu 11 AWT'de
     *  NSWindow.titleVisibility erişimi yok — apple.awt.windowTitleVisible
     *  yalnız JDK 17+).
     *
     *  ESKİ çözüm yerel başlığı tek boşluğa indiriyordu; ama Dock sağ-tık menüsü
     *  / Pencere menüsü / Mission Control pencereleri NSWindow.title ile listeler
     *  → tüm belgeler aynı boş adla görünüyordu (kullanıcı: "hangisi hangisi belli
     *  olmuyor"). YENİ çözüm: gerçek (temiz) belge adı yerel başlık olarak KORUNUR
     *  (Dock/menü doğru adı gösterir); başlık METNİ çizimi macos-textkeys dylib'i
     *  (NativeDialogKeys.m) tarafından titleVisibility=NSWindowTitleHidden ile
     *  bastırılır (titlebarAppearsTransparent pencerelerde) → çakışma yok, başlık
     *  metni macOS tarafından çizilmez. SkinPatch'in TaskbarPanel.paintComponent
     *  yaması temiz adı macoslook.title'dan çizmeyi sürdürür (uygulama içi görünüm
     *  değişmez). NOT: başlık METNİNİ gizleyen native kod TEXTKEYS bayrağında;
     *  SKIN=1 TEXTKEYS=0 (varsayılan-dışı) build'de yerel başlık metni yeniden
     *  görünür/çakışabilir. UDE her belge değişiminde setTitle'ı yeniler; "title"
     *  property dinleyicisi temizleyip yeniden kurar (idempotent: temiz ad tekrar
     *  yazılınca firePropertyChange eşit değerde olay üretmez → döngü kapanır). */
    private static void hookTitle(JFrame f) {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        JComponent root = f.getRootPane();
        if (Boolean.TRUE.equals(root.getClientProperty("macoslook.titlehook"))) return;
        root.putClientProperty("macoslook.titlehook", Boolean.TRUE);
        f.addPropertyChangeListener("title", ev -> {
            Object nv = ev.getNewValue();
            if (nv != null) {
                try { applyTitle(f, nv.toString()); }
                catch (Throwable t) { log("titlehook değişim: " + t); }
            }
        });
        applyTitle(f, f.getTitle());
        log("titlehook kuruldu");
    }

    private static void applyTitle(JFrame f, String raw) {
        if (raw == null) return;
        String clean = raw.trim();
        if (clean.isEmpty()) return;
        int p = clean.lastIndexOf(" (");
        if (p > 0 && clean.endsWith(")") && clean.indexOf('/', p) > p) {
            clean = clean.substring(0, p);
        }
        int d = clean.indexOf(" - ");
        if (d > 0 && d + 3 < clean.length()) {
            clean = clean.substring(d + 3);
        }
        f.getRootPane().putClientProperty("macoslook.title", clean);
        // Gerçek belge adını yerel başlık olarak KORU (Dock/Pencere menüsü onu
        // listeler); başlık METNİ çizimi dylib'te titleVisibility=hidden ile
        // bastırılır. clean zaten f.getTitle()'a eşitse setTitle olay üretmez.
        if (!clean.equals(f.getTitle())) f.setTitle(clean);
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon != null) ribbon.repaint();
        log("başlık devralındı: " + clean);
    }

    private static void removeMemoryBar(JFrame f) {
        Component bar = findByClassName(f, "WebMemoryBar");
        if (bar == null) { log("WebMemoryBar yok"); return; }
        Container parent = bar.getParent();
        if (parent != null) {
            parent.remove(bar);
            parent.revalidate();
            parent.repaint();
            log("WebMemoryBar kaldırıldı");
        }
    }

    /** Cetvel zeminini koyu temada Word tonuna çek (sabit-beyaz setBackground'u ezer).
     *  Koyu tema tespiti UIManager üzerinden — skin sınıflarına derleme bağımlılığı yok. */
    private static void fixRulerBackground(JFrame f) {
        java.awt.Color bg = UIManager.getColor("Panel.background");
        boolean dark = bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 100;
        if (!dark) return;
        fixRulerWalk(f);
    }

    private static void fixRulerWalk(Component c) {
        for (Class<?> i : c.getClass().getInterfaces()) {
            if (i.getName().endsWith("IRuler")) {
                c.setBackground(new java.awt.Color(70, 70, 70));
                c.repaint();
                log("cetvel zemini ayarlandı: " + c.getClass().getName());
                break;
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) fixRulerWalk(k);
        }
    }

    /** Şerit sekme başlıkları Word'deki gibi kalın. Font bileşen üzerinde
     *  türetilir ki tercih edilen genişlik de kalın ölçüyle hesaplansın. */
    private static void boldTaskTabs(Component c) {
        if (c.getClass().getSimpleName().equals("JRibbonTaskToggleButton")) {
            java.awt.Font fo = c.getFont();
            if (fo != null && !fo.isBold()) {
                c.setFont(fo.deriveFont(java.awt.Font.BOLD));
                log("sekme kalınlaştı: " + c);
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) boldTaskTabs(k);
        }
    }

    /** Şeritteki "Geçerli/Gövde" kapsam seçici combo'sunu kaldırır (istek:
     *  menüde hiç görünmesin). Combo, az öğeli ve "Geçerli" içeren modeliyle
     *  tanınır; Flamingo sarmalayıcısı (JRibbonComponent) varsa o kaldırılır
     *  ki band düzeni boşluğu geri kazansın. */
    private static void removeScopeCombo(JFrame f) {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        javax.swing.JComboBox<?> combo = findScopeCombo(ribbon);
        if (combo == null) { log("kapsam combo bulunamadı"); return; }
        Component victim = combo;
        Container p = combo.getParent();
        while (p != null && p.getClass().getSimpleName().equals("JRibbonComponent")) {
            victim = p;
            p = p.getParent();
        }
        Container parent = victim.getParent();
        if (parent != null) {
            parent.remove(victim);
            parent.revalidate();
            parent.repaint();
            log("kapsam combo kaldırıldı: " + victim.getClass().getName());
        }
    }

    /** Görünüm sekmesine "Koyu belge arkaplanı" onay kutusu ekler (Word koyu
     *  modu sayfası). Band, ribbon MODELİNDEN bulunur ("Klasik görünüme geç"
     *  kutusunu içeren band) — bileşen ağacı yalnız seçili sekmeyi içerdiğinden
     *  ağaç araması açılışta bulamazdı. Durum macosskin.DarkPage'te (prefs ile
     *  kalıcı, varsayılan kapalı); Flamingo/macosskin sınıflarına derleme
     *  bağımlılığı yok, hepsi yansımayla. */
    private static void addDarkPageToggle(JFrame f) throws Exception {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        JComponent r = (JComponent) ribbon;
        if (Boolean.TRUE.equals(r.getClientProperty("macoslook.darkpage"))) return;

        Class<?> dp;
        try {
            dp = Class.forName("macosskin.DarkPage");
        } catch (ClassNotFoundException e) { log("darkpage: DarkPage sınıfı yok"); return; }

        Object targetBand = null;
        int taskCount = (Integer) ribbon.getClass().getMethod("getTaskCount").invoke(ribbon);
        for (int i = 0; i < taskCount && targetBand == null; i++) {
            Object task = ribbon.getClass().getMethod("getTask", int.class).invoke(ribbon, i);
            java.util.List<?> bands = (java.util.List<?>)
                task.getClass().getMethod("getBands").invoke(task);
            for (Object band : bands) {
                Component cp = (Component)
                    band.getClass().getMethod("getControlPanel").invoke(band);
                if (cp != null && findCheckBox(cp, "Klasik görünüme geç") != null) {
                    targetBand = band;
                    break;
                }
            }
        }
        if (targetBand == null) { log("darkpage: hedef band bulunamadı"); return; }

        final javax.swing.JCheckBox cb = new javax.swing.JCheckBox("Koyu belge arkaplanı");
        cb.setOpaque(false);
        cb.setSelected((Boolean) dp.getMethod("isOn").invoke(null));
        final java.lang.reflect.Method setOn = dp.getMethod("setOn", boolean.class);
        cb.addActionListener(e -> {
            try {
                setOn.invoke(null, cb.isSelected());
                for (java.awt.Window w : java.awt.Window.getWindows()) w.repaint();
            } catch (Throwable t) { log("darkpage toggle: " + t); }
        });

        Class<?> jrcCls = Class.forName("org.pushingpixels.flamingo.api.ribbon.JRibbonComponent");
        Object jrc = jrcCls.getConstructor(JComponent.class).newInstance(cb);
        targetBand.getClass().getMethod("addRibbonComponent", jrcCls).invoke(targetBand, jrc);
        r.putClientProperty("macoslook.darkpage", Boolean.TRUE);
        log("darkpage: onay kutusu eklendi");
    }

    /** Görünüm sekmesine, koyu belge onay kutusunun altına "Renk modu"
     *  açılır listesi ekler (Açık/Koyu/Sistem; varsayılan Sistem). Tercih
     *  macosskin.DarkMode'da prefs ile kalıcı; seçim macosskin.ModeSwitch
     *  ile ANINDA uygulanır (skin + delegate + kanvas + cetvel + ağaç
     *  güncellemesi; ikonlar ModeAwareImage ile kendiliğinden uyar). Band,
     *  addDarkPageToggle ile aynı yolla ribbon MODELİNDEN bulunur; her şey
     *  yansımayla. */
    private static void addColorModeCombo(JFrame f) throws Exception {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        JComponent r = (JComponent) ribbon;
        if (Boolean.TRUE.equals(r.getClientProperty("macoslook.colormode"))) return;

        Class<?> dm;
        try {
            dm = Class.forName("macosskin.DarkMode");
        } catch (ClassNotFoundException e) { log("colormode: DarkMode sınıfı yok"); return; }

        Object targetBand = null;
        int taskCount = (Integer) ribbon.getClass().getMethod("getTaskCount").invoke(ribbon);
        for (int i = 0; i < taskCount && targetBand == null; i++) {
            Object task = ribbon.getClass().getMethod("getTask", int.class).invoke(ribbon, i);
            java.util.List<?> bands = (java.util.List<?>)
                task.getClass().getMethod("getBands").invoke(task);
            for (Object band : bands) {
                Component cp = (Component)
                    band.getClass().getMethod("getControlPanel").invoke(band);
                if (cp != null && findCheckBox(cp, "Klasik görünüme geç") != null) {
                    targetBand = band;
                    break;
                }
            }
        }
        if (targetBand == null) { log("colormode: hedef band bulunamadı"); return; }

        final String[] modes = { "light", "dark", "system" };
        final String[] labels = { "Açık", "Koyu", "Sistem" };
        final javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(labels);
        String cur = (String) dm.getMethod("getMode").invoke(null);
        int sel = 2;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) sel = i;
        combo.setSelectedIndex(sel);
        combo.setToolTipText("Açık, koyu ya da sistem görünümü");
        final java.lang.reflect.Method applyMode =
            Class.forName("macosskin.ModeSwitch").getMethod("apply", String.class);
        combo.addActionListener(e -> {
            try {
                int i = combo.getSelectedIndex();
                if (i < 0) return;
                String prev = (String) dm.getMethod("getMode").invoke(null);
                if (DEBUG) {
                    StringBuilder sb = new StringBuilder(
                        "colormode action i=" + i + " prev=" + prev + " stack=");
                    for (StackTraceElement s : Thread.currentThread().getStackTrace()) {
                        sb.append("\n    ").append(s);
                    }
                    log(sb.toString());
                }
                if (modes[i].equals(prev)) return;
                applyMode.invoke(null, modes[i]);
            } catch (Throwable t) { log("colormode seçim: " + t); }
        });

        javax.swing.JPanel row = new javax.swing.JPanel(
            new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        javax.swing.JLabel lbl = new javax.swing.JLabel("Renk modu:");
        row.add(lbl);
        row.add(combo);

        Class<?> jrcCls = Class.forName("org.pushingpixels.flamingo.api.ribbon.JRibbonComponent");
        Object jrc = jrcCls.getConstructor(JComponent.class).newInstance(row);
        targetBand.getClass().getMethod("addRibbonComponent", jrcCls).invoke(targetBand, jrc);
        r.putClientProperty("macoslook.colormode", Boolean.TRUE);
        log("colormode: açılır liste eklendi (seçili=" + cur + ")");
    }

    private static javax.swing.JCheckBox findCheckBox(Component c, String text) {
        if (c instanceof javax.swing.JCheckBox
                && text.equals(((javax.swing.JCheckBox) c).getText())) {
            return (javax.swing.JCheckBox) c;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                javax.swing.JCheckBox hit = findCheckBox(k, text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static javax.swing.JComboBox<?> findScopeCombo(Component c) {
        if (c instanceof javax.swing.JComboBox) {
            javax.swing.JComboBox<?> cb = (javax.swing.JComboBox<?>) c;
            int n = cb.getItemCount();
            if (n > 0 && n < 10) {
                for (int i = 0; i < n; i++) {
                    if ("Geçerli".equals(String.valueOf(cb.getItemAt(i)))) return cb;
                }
            }
            return null;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                javax.swing.JComboBox<?> hit = findScopeCombo(k);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static Component findByClassName(Component c, String simpleName) {
        if (c.getClass().getSimpleName().equals(simpleName)
                || c.getClass().getName().endsWith("." + simpleName)) return c;
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                Component hit = findByClassName(k, simpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
