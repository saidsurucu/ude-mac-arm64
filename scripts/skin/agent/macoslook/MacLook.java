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
