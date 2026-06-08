package macostextkeys;

/*
 * macOS metin düzenleme kısayolları javaagent'ı.
 *
 * Sorun: UDE, yerel olmayan bir Swing Look&Feel (WebLaF) kullanır. macOS'ta
 *   Option+Delete (kelime sil), Option+ok (kelime atla), Cmd+ok (satır başı/sonu)
 *   gibi tuş eşlemeleri Swing'in kendisinden gelmez; bunları yalnızca native Aqua
 *   L&F, apple.laf.AquaKeyBindings üzerinden ekler. WebLaF'e geçildiğinde bu Apple
 *   bağlamaları uygulanmaz → kullanıcı bu kısayolların "kaybolduğunu" görür.
 *
 * Çözüm: premain'de bir AWTEventListener kurulur; her JTextComponent odaklandığında
 *   input-map'ine macOS'un beklediği kelime/satır/belge düzenleme bağlamaları eklenir.
 *   DefaultEditorKit aksiyonları zaten hazırdır (deletePrevWordAction vb.) — yalnızca
 *   tuşlara bağlanırlar. Satır başına kadar silme (Cmd+Delete) için küçük bir özel
 *   aksiyon eklenir. Yaklaşım L&F ve zamanlamadan bağımsızdır; vendor jar'a dokunmaz.
 */

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.instrument.Instrumentation;

import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import javax.swing.text.Utilities;

public final class MacTextKeys {

    /** Sol Option tuşu macOS'ta ALT olarak gelir. */
    private static final int OPT = InputEvent.ALT_DOWN_MASK;
    /**
     * Sağ Option tuşu macOS + Java'da ALT değil ALT_GRAPH (AltGr) üretir. Aynı
     * kısayolların sağ Option ile de çalışması için bu maskeyle de bağlanırlar.
     */
    private static final int OPT_R = InputEvent.ALT_GRAPH_DOWN_MASK;
    /** Command tuşu (menü kısayol maskesi macOS'ta META döner). */
    private static final int CMD = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    private static final String ACT_DELETE_TO_LINE_START = "mac-delete-to-line-start";

    private MacTextKeys() {}

    /** -javaagent giriş noktası (uygulamanın main()'inden önce çalışır). */
    public static void premain(String args, Instrumentation inst) {
        install();
    }

    /** Çalışan JVM'e sonradan iliştirilme (attach) giriş noktası. */
    public static void agentmain(String args, Instrumentation inst) {
        install();
    }

    private static void install() {
        // UYAP'ın alışılmadık Ctrl kısayollarını standart Cmd kısayollarına bağla
        // (Cmd+B→kalın, Cmd+I→italik, Cmd+U→altı çizili, Cmd+F→bul, Cmd+S→kaydet …).
        MacShortcutRemap.install();
        // Ribbon tooltip'lerindeki Windows kısayollarını Mac karşılıklarıyla değiştir
        // (Kaydet "(Shift+Ctrl+K)" → "(⌘S)", Kalın "(Ctrl+K)" → "(⌘B)", hizalama → "(⌘L)" …).
        MacTooltips.install();
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (src instanceof JTextComponent) {
                        applyBindings((JTextComponent) src);
                    }
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli: hata olursa sessizce vazgeç.
            System.err.println("[macos-textkeys] kurulamadı: " + t);
        }
    }

    /**
     * Her odaklanmada idempotent olarak uygulanır (WebLaF input-map'i yeniden
     * kurarsa bağlamalar tekrar yerine oturur; aynı put'lar zararsızdır).
     */
    private static void applyBindings(JTextComponent tc) {
        try {
            applyBindings0(tc);
        } catch (Throwable t) {
            // applyBindings asla EDT'yi düşürmemeli.
            System.err.println("[macos-textkeys] bağlama uygulanamadı: " + t);
        }
    }

    private static void applyBindings0(JTextComponent tc) {
        InputMap im = tc.getInputMap(); // WHEN_FOCUSED
        if (im == null) return;

        // Kelime silme (sol + sağ Option)
        putOpt(im, KeyEvent.VK_BACK_SPACE, 0, DefaultEditorKit.deletePrevWordAction);
        putOpt(im, KeyEvent.VK_DELETE, 0, DefaultEditorKit.deleteNextWordAction);

        // Kelime atlama (+ Shift ile seçerek)
        putOpt(im, KeyEvent.VK_LEFT, 0, DefaultEditorKit.previousWordAction);
        putOpt(im, KeyEvent.VK_RIGHT, 0, DefaultEditorKit.nextWordAction);
        putOpt(im, KeyEvent.VK_LEFT, SHIFT, DefaultEditorKit.selectionPreviousWordAction);
        putOpt(im, KeyEvent.VK_RIGHT, SHIFT, DefaultEditorKit.selectionNextWordAction);

        // Satır başı / sonu (+ Shift ile seçerek)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, CMD), DefaultEditorKit.beginLineAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, CMD), DefaultEditorKit.endLineAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, CMD | SHIFT), DefaultEditorKit.selectionBeginLineAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, CMD | SHIFT), DefaultEditorKit.selectionEndLineAction);

        // Belge başı / sonu (+ Shift ile seçerek)
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, CMD), DefaultEditorKit.beginAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, CMD), DefaultEditorKit.endAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, CMD | SHIFT), DefaultEditorKit.selectionBeginAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, CMD | SHIFT), DefaultEditorKit.selectionEndAction);

        // Satır başına kadar sil (özel aksiyon)
        ActionMap am = tc.getActionMap();
        if (am != null && am.get(ACT_DELETE_TO_LINE_START) == null) {
            am.put(ACT_DELETE_TO_LINE_START, DELETE_TO_LINE_START);
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, CMD), ACT_DELETE_TO_LINE_START);
    }

    /**
     * Bir Option kısayolunu hem sol Option (ALT) hem sağ Option (ALT_GRAPH) ile
     * bağlar. {@code extraMods} ek maskedir (ör. SHIFT) ya da yoksa 0.
     */
    private static void putOpt(InputMap im, int keyCode, int extraMods, Object action) {
        im.put(KeyStroke.getKeyStroke(keyCode, OPT | extraMods), action);
        im.put(KeyStroke.getKeyStroke(keyCode, OPT_R | extraMods), action);
    }

    /** macOS Cmd+Delete: imleçten satır başına kadar olan metni siler. */
    private static final Action DELETE_TO_LINE_START = new TextAction(ACT_DELETE_TO_LINE_START) {
        @Override public void actionPerformed(ActionEvent e) {
            JTextComponent t = getTextComponent(e);
            if (t == null || !t.isEditable() || !t.isEnabled()) return;
            try {
                int caret = t.getCaretPosition();
                int rowStart = Utilities.getRowStart(t, caret);
                if (rowStart < 0) rowStart = 0;
                if (caret > rowStart) {
                    t.getDocument().remove(rowStart, caret - rowStart);
                } else if (caret > 0) {
                    // Zaten satır başındaysa: önceki karakteri (satır sonunu) sil.
                    t.getDocument().remove(caret - 1, 1);
                }
            } catch (BadLocationException ex) {
                // Görünür view yoksa sessizce geç.
            }
        }
    };
}
