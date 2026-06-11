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
    /** Trafik ışıkları için şerit sol içliği (px). */
    private static final int TRAFFIC_INSET = 72;
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
                        try { removeMemoryBar(f); } catch (Throwable t) { log("membar: " + t); }
                        try { fixRulerBackground(f); } catch (Throwable t) { log("ruler: " + t); }
                        try { boldTaskTabs(f); } catch (Throwable t) { log("tabfont: " + t); }
                        try { removeScopeCombo(f); } catch (Throwable t) { log("scopecombo: " + t); }
                        try { addDarkPageToggle(f); } catch (Throwable t) { log("darkpage: " + t); }
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
            JComponent r = (JComponent) ribbon;
            r.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createEmptyBorder(0, TRAFFIC_INSET, 0, 0),
                r.getBorder()));
            log("ribbon insetlendi");
        }
        java.awt.Color bg = UIManager.getColor("Panel.background");
        if (bg != null) f.setBackground(bg);
        root.revalidate();
        f.repaint();
        log("titlebar bütünleşti: " + f.getTitle());
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
