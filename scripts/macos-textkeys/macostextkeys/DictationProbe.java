package macostextkeys;

/*
 * macOS dikte (IME) teşhis sınıfı — UDE_DICTLOG=1 ile etkinleşir, aksi halde no-op.
 *
 * Amaç: dikte sırasında akan InputMethodEvent'leri ve EDT'de yakalanmamış
 * istisnaları ~/Library/Logs/ude-dictation.txt'ye dökmek. Dikte kapanınca
 * metnin kaybolması (H2: composed-text commit'i özel doküman katmanında
 * patlıyor) ya da olayların hiç gelmemesi (H1: native kilit) buradan kanıtlanır.
 *
 * Not: AWTEventListener salt dinleyicidir (olay tüketemez) — teşhis için yeterli,
 * düzeltme için değil. Faz 2 düzeltmesi ayrı (DictationGuard, Javassist).
 */

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputMethodEvent;
import java.awt.im.InputMethodRequests;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;

import javax.swing.text.JTextComponent;

public final class DictationProbe {

    private static final boolean LOG = "1".equals(System.getenv("UDE_DICTLOG"));
    private static volatile boolean dumpedHierarchy = false;

    private DictationProbe() {}

    public static void install() {
        if (!LOG) return;
        try {
            // EDT dahil yakalanmamış istisnalar (Java 7+ EDT istisnaları default
            // handler'a düşer): composed-text commit patlaması buradan görünür.
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable ex) {
                    logLine("UNCAUGHT thread=" + t.getName() + "\n" + stackTrace(ex));
                    if (prev != null) prev.uncaughtException(t, ex);
                }
            });
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e instanceof InputMethodEvent) logIme((InputMethodEvent) e);
                }
            }, AWTEvent.INPUT_METHOD_EVENT_MASK);
            logLine("DictationProbe kuruldu (java.version="
                    + System.getProperty("java.version") + ")");
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
        }
    }

    private static void logIme(InputMethodEvent e) {
        try {
            Object src = e.getSource();
            StringBuilder sb = new StringBuilder();
            sb.append(e.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED
                    ? "TEXT_CHANGED" : "CARET_CHANGED");
            sb.append(" committed=").append(e.getCommittedCharacterCount());
            AttributedCharacterIterator it = e.getText();
            if (it == null) {
                sb.append(" text=null");
            } else {
                StringBuilder all = new StringBuilder();
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
                    all.append(c);
                }
                sb.append(" len=").append(all.length())
                  .append(" text=\"").append(all).append('"');
            }
            sb.append(" src=").append(src.getClass().getName());
            if (src instanceof JTextComponent) {
                sb.append(" caret=").append(((JTextComponent) src).getCaretPosition());
            }
            logLine(sb.toString());
            if (!dumpedHierarchy) {
                dumpedHierarchy = true;
                StringBuilder h = new StringBuilder("hiyerarşi:");
                for (Class<?> k = src.getClass(); k != null; k = k.getSuperclass()) {
                    h.append(' ').append(k.getName());
                }
                logLine(h.toString());
                if (src instanceof Component) {
                    InputMethodRequests req = ((Component) src).getInputMethodRequests();
                    logLine("inputMethodRequests="
                            + (req == null ? "null" : req.getClass().getName()));
                }
            }
        } catch (Throwable t) {
            logLine("logIme hata: " + t);
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static synchronized void logLine(String s) {
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-dictation.txt");
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(System.currentTimeMillis() + " " + s + System.lineSeparator());
            }
        } catch (IOException ignore) {
            // log başarısızsa sessizce geç
        }
    }
}
