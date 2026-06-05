package macoszoom;

/*
 * macOS trackpad zoom javaagent'ı.
 *
 * Sorun: UDE'de zoom yalnızca durum çubuğundaki bir JSlider ile yapılabiliyor;
 *   trackpad jesti yok. Gerçek pinch (NSMagnificationGesture) modern Java'ya
 *   iletilmez; Java'ya ulaşan tek trackpad sinyali iki parmak kaydırmadır
 *   (MouseWheelEvent).
 *
 * Çözüm: Sistem EventQueue'su override edilir. Cmd basılıyken gelen
 *   MouseWheelEvent yakalanır ve YUTULUR (belge kaymaz); jest yönünde zoom
 *   JSlider'ının değeri değiştirilir. Slider'ın kendi dinleyicisi zoom'u yapar.
 *   AWTEventListener kullanılmaz çünkü olayı tüketemez (yutamaz). Vendor jar'a
 *   dokunulmaz; yaklaşım L&F'den bağımsızdır.
 */

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.lang.instrument.Instrumentation;

public final class MacZoom {

    /** Command tuşu (macOS'ta menü kısayol maskesi META döner). */
    private static final int CMD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    /** -Dmacoszoom.debug=1 ile ayrıntılı slider/zoom logu. */
    private static final boolean DEBUG = "1".equals(System.getProperty("macoszoom.debug"));

    private MacZoom() {}

    /** -javaagent giriş noktası (main()'den önce çalışır). */
    public static void premain(String args, Instrumentation inst) { install(); }

    /** Çalışan JVM'e sonradan attach giriş noktası. */
    public static void agentmain(String args, Instrumentation inst) { install(); }

    private static void install() {
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(new ZoomEventQueue());
            System.err.println("[macos-zoom] yüklendi" + (DEBUG ? " (debug)" : ""));
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
            System.err.println("[macos-zoom] kurulamadı: " + t);
        }
    }

    /** Cmd+wheel'i yakalayıp yutan, diğer her olayı aynen geçiren kuyruk. */
    private static final class ZoomEventQueue extends EventQueue {
        @Override protected void dispatchEvent(AWTEvent event) {
            super.dispatchEvent(event);
        }
    }
}
