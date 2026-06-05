package macoszoom;

/*
 * macOS trackpad zoom javaagent'ı.
 *
 * Sorun: UDE'de zoom yalnızca durum çubuğundaki bir JSlider ile yapılabiliyor;
 *   trackpad jesti yok. Gerçek pinch (NSMagnificationGesture) modern Java'ya
 *   iletilmez; Java'ya ulaşan tek trackpad sinyali iki parmak kaydırmadır
 *   (MouseWheelEvent).
 *
 * Çözüm: Cmd basılıyken gelen MouseWheelEvent yakalanır, YUTULUR (belge kaymaz)
 *   ve jest yönünde uygulamanın zoom JSlider'ının değeri değiştirilir. Slider'ın
 *   kendi dinleyicisi gerçek zoom'u uygular; zoom mantığı yeniden yazılmaz.
 *
 * Mekanizma notları (Faz 0 keşfi):
 *   - Olayı YUTMAK için EventQueue override'ı gerekir (AWTEventListener olayı
 *     tüketemez). Ama EventQueue'yu premain'de ERKEN itmek işe yaramaz: uygulama
 *     (WebLaF) sonradan kendi kuyruğunu üste itip bizimkini baypas eder. Bu yüzden
 *     kuyruğumuzu İLK FOCUS_GAINED olayında, doğru EDT/AppContext üzerinde itiyoruz
 *     → bizimki en üstte kalır ve olayları alır.
 *   - Cmd, macOS'ta META_DOWN_MASK (0x100) olarak gelir.
 *   - Zoom slider'ı pencere ağacındaki tek JSlider'dır (min=0, max=100, yatay).
 *   - Uygulama System.err'i kendi logger'ına yönlendirdiğinden, -Dmacoszoom.debug=1
 *     ile ayrıntılı log doğrudan /tmp/macos-zoom-agent.log dosyasına yazılır.
 *   Vendor jar'a dokunulmaz; yaklaşım L&F'den bağımsızdır.
 */

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;

public final class MacZoom {

    /** Command tuşu (macOS'ta menü kısayol maskesi META döner). */
    private static final int CMD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    /** -Dmacoszoom.debug=1 ile dosyaya ayrıntılı log. */
    private static final boolean DEBUG = "1".equals(System.getProperty("macoszoom.debug"));
    /** Bir tam adım için biriken |rotasyon| eşiği ve slider adımının ölçeği. */
    private static final int STEP_DIVISOR = 40;
    /** Klavye ⌘+/⌘− için her basışta slider adımının ölçeği (daha iri adım). */
    private static final int KEY_STEP_DIVISOR = 20;

    private static final PrintStream LOG = DEBUG ? openLog() : null;

    private MacZoom() {}

    private static PrintStream openLog() {
        try {
            return new PrintStream(new FileOutputStream("/tmp/macos-zoom-agent.log", true), true, "UTF-8");
        } catch (Exception e) {
            return System.err;
        }
    }

    private static void log(String m) {
        if (LOG != null) {
            try { LOG.println("[macos-zoom] " + m); } catch (Throwable ignore) {}
        }
    }

    /** -javaagent giriş noktası (main()'den önce çalışır). */
    public static void premain(String args, Instrumentation inst) { install(); }

    /** Çalışan JVM'e sonradan attach giriş noktası. */
    public static void agentmain(String args, Instrumentation inst) { install(); }

    private static void install() {
        try {
            // Kuyruğu erken itmek baypas ediliyor; ilk focus'ta (uygulama UI'ı kurulduktan
            // sonra) doğru AppContext üzerinde itiyoruz → en üstte kalır.
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                private boolean pushed = false;
                @Override public void eventDispatched(AWTEvent e) {
                    if (!pushed && e.getID() == FocusEvent.FOCUS_GAINED) {
                        pushed = true;
                        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new ZoomEventQueue());
                        log("EventQueue push edildi (ilk focus)");
                    }
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
            // Klavye ile zoom: ⌘+ / ⌘− slider'ı sürer (trackpad jestine ek).
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(MacZoom::dispatchKey);
            log("yüklendi CMD=0x" + Integer.toHexString(CMD));
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
            log("kurulamadı: " + t);
        }
    }

    /** Cmd+wheel'i yakalar, yutar ve zoom slider'ını sürer; gerisini aynen geçirir. */
    private static final class ZoomEventQueue extends EventQueue {
        private double accum = 0;     // trackpad'in kesirli rotasyonunu biriktir
        private JSlider cached;       // bulunan slider'ı önbelleğe al

        @Override protected void dispatchEvent(AWTEvent event) {
            if (event instanceof MouseWheelEvent) {
                MouseWheelEvent e = (MouseWheelEvent) event;
                if ((e.getModifiersEx() & CMD) != 0) {
                    try { handleZoom(e); } catch (Throwable t) {
                        log("zoom hatası: " + t);
                    }
                    return; // olayı yut: belge kaymasın
                }
            }
            super.dispatchEvent(event);
        }

        private void handleZoom(MouseWheelEvent e) {
            JSlider s = resolveSlider(e);
            if (s == null) { log("slider bulunamadı"); return; }
            accum += e.getPreciseWheelRotation();
            int steps = (int) accum;         // tam adımlar
            if (steps == 0) return;           // eşik dolmadı
            accum -= steps;                   // kalanı taşı
            int range = s.getMaximum() - s.getMinimum();
            int step = Math.max(1, range / STEP_DIVISOR);
            // Tekerlek yukarı/uzağa = negatif rotation = yakınlaştır (değer artar).
            int v = s.getValue() - steps * step;
            v = Math.max(s.getMinimum(), Math.min(s.getMaximum(), v));
            if (v != s.getValue()) {
                s.setValue(v);                // EventQueue zaten EDT'de
                log("zoom → " + v + " [" + s.getMinimum() + ".." + s.getMaximum() + "]");
            }
        }

        private JSlider resolveSlider(MouseWheelEvent e) {
            Component c = e.getComponent();
            Window w = (c instanceof Window) ? (Window) c : SwingUtilities.getWindowAncestor(c);
            if (cached != null && cached.isShowing()
                    && (w == null || SwingUtilities.getWindowAncestor(cached) == w)) {
                return cached;
            }
            List<JSlider> sliders = new ArrayList<>();
            if (w != null) collect(w, sliders);
            if (sliders.isEmpty()) {
                for (Window ww : Window.getWindows()) collect(ww, sliders);
            }
            cached = pick(sliders);
            return cached;
        }

    }

    // ——— Klavye ile zoom (⌘+ / ⌘−) ———

    /** ⌘ basılıyken +/= → yakınlaştır, −/_ → uzaklaştır; olayı yutar. */
    static boolean dispatchKey(KeyEvent e) {
        try {
            if ((e.getModifiersEx() & CMD) == 0) return false;
            int dir;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_EQUALS: case KeyEvent.VK_PLUS: case KeyEvent.VK_ADD:  dir = +1; break;
                case KeyEvent.VK_MINUS:  case KeyEvent.VK_SUBTRACT:                    dir = -1; break;
                default: return false;
            }
            int id = e.getID();
            if (id != KeyEvent.KEY_PRESSED && id != KeyEvent.KEY_RELEASED) return false;
            if (id == KeyEvent.KEY_PRESSED) {
                JSlider s = findSlider(e.getComponent());
                if (s != null) bump(s, dir);
            }
            return true; // ⌘+/⌘− basma/bırakmayı yut
        } catch (Throwable t) {
            return false;
        }
    }

    private static void bump(JSlider s, int dir) {
        int range = s.getMaximum() - s.getMinimum();
        int step = Math.max(1, range / KEY_STEP_DIVISOR);
        // Bu slider'da düşük değer = yakınlaştır; ⌘+ (dir=+1) değeri DÜŞÜRMELİ.
        int v = s.getValue() - dir * step;
        v = Math.max(s.getMinimum(), Math.min(s.getMaximum(), v));
        if (v != s.getValue()) { s.setValue(v); log("klavye zoom → " + v); }
    }

    private static JSlider findSlider(Component focus) {
        Window w = (focus instanceof Window) ? (Window) focus
                : (focus != null ? SwingUtilities.getWindowAncestor(focus) : null);
        List<JSlider> sliders = new ArrayList<>();
        if (w != null) collect(w, sliders);
        if (sliders.isEmpty()) {
            for (Window ww : Window.getWindows()) collect(ww, sliders);
        }
        return pick(sliders);
    }

    private static JSlider pick(List<JSlider> sliders) {
        if (sliders.isEmpty()) return null;
        if (sliders.size() == 1) return sliders.get(0);
        // Birden fazlaysa: en alttaki yatay slider (durum çubuğu zoom'u).
        JSlider best = null;
        for (JSlider s : sliders) {
            if (s.getOrientation() != JSlider.HORIZONTAL) continue;
            if (best == null
                    || s.getLocationOnScreen().y > best.getLocationOnScreen().y) {
                best = s;
            }
        }
        return best != null ? best : sliders.get(0);
    }

    private static void collect(Component c, List<JSlider> out) {
        if (c instanceof JSlider && c.isShowing()) out.add((JSlider) c);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) collect(k, out);
        }
    }
}
