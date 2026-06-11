package macostextkeys;

/*
 * macOS dikte (IME) düzeltmesi.
 *
 * Sorun: macOS dikte metni InputMethodEvent'lerle verir. JTextComponent,
 * processInputMethodEvent override'ı ya da kayıtlı InputMethodListener'ı
 * OLMAYAN bileşeni "pasif IME istemcisi" sayar ve commit edilen her karakteri
 * SENTETİK KEY_TYPED olarak keyTyped dinleyicilerine işler
 * (JTextComponent.replaceInputMethodText). UDE'nin kelime-denetim zinciri
 * (im.keyTyped → … → gui.aC.a) o sırada getCaret()'i kendi text.l tipine cast
 * eder; commit anında caret Swing'in geçici ComposedTextCaret'i olduğundan
 * ClassCastException fırlar → commit yarıda kalır (dikte metni kaybolur),
 * EDT'deki istisna CInputMethod akışını bozar (donma).
 *
 * Çözüm: her JTextComponent odaklandığında no-op InputMethodListener eklenir.
 * addInputMethodListener, JTextComponent'te needToSendKeyTypedEvent=false
 * yapar → sentetik keyTyped üretilmez; committed metin
 * mapCommittedTextToAction ile editör kit'inin NORMAL yazma aksiyonundan
 * belgeye girer. Composed görüntüleme, ölü tuşlar (^→â) ve emoji seçici aynı
 * yoldan bozulmadan çalışır (canlı JVM'de attach deneyiyle kanıtlandı).
 */

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;

import javax.swing.text.JTextComponent;

public final class DictationFix {

    /** No-op dinleyici; varlığı sentetik keyTyped üretimini kapatır. */
    private static final class NoopImListener implements InputMethodListener {
        @Override public void inputMethodTextChanged(InputMethodEvent e) {}
        @Override public void caretPositionChanged(InputMethodEvent e) {}
    }

    private DictationFix() {}

    public static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (src instanceof JTextComponent) apply((JTextComponent) src);
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
            System.err.println("[macos-textkeys] DictationFix kurulamadı: " + t);
        }
    }

    private static void apply(JTextComponent tc) {
        try {
            for (InputMethodListener l : tc.getInputMethodListeners()) {
                if (l instanceof NoopImListener) return; // idempotent
            }
            tc.addInputMethodListener(new NoopImListener());
        } catch (Throwable t) {
            // EDT'yi asla düşürme.
        }
    }
}
