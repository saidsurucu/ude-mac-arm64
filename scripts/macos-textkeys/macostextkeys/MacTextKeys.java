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
     * Sağ Option tuşu macOS + Java'da ALT_GRAPH (AltGr) bitini EK olarak set eder
     * ama genel option bayrağını da bıraktığı için modifiers ALT | ALT_GRAPH olur
     * (kaynak: sun.lwawt.macosx.NSEvent.nsToJavaModifiers). Swing InputMap TAM
     * maske eşleşmesi istediğinden yalnız ALT_GRAPH ile bağlamak YETMEZ — sağ
     * Option'ın gerçek imzası OPT | OPT_R'dir; putOpt bu kombinasyonu da bağlar.
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
        // Native diyalog (NSSavePanel) pano kısayolları: dosya adı kutusunda
        // Cmd+V/C/X/A. Panel AWT olay zincirinin dışında olduğundan Java tarafında
        // çözülemez; agent jar'ın yanındaki dylib NSEvent local monitor kurar.
        loadNativeDialogKeys();
        // UYAP'ın alışılmadık Ctrl kısayollarını standart Cmd kısayollarına bağla
        // (Cmd+B→kalın, Cmd+I→italik, Cmd+U→altı çizili, Cmd+F→bul, Cmd+S→kaydet …).
        MacShortcutRemap.install();
        // macOS Option ile üretilen özel karakterleri (@, #, [, ], { } \ | …) metne
        // yazılabilir kıl (Türkçe-Q klavye; mnemonic baypası + KEY_TYPED ekleme).
        MacOptionChars.install();
        // Ribbon tooltip'lerindeki Windows kısayollarını Mac karşılıklarıyla değiştir
        // (Kaydet "(Shift+Ctrl+K)" → "(⌘S)", Kalın "(Ctrl+K)" → "(⌘B)", hizalama → "(⌘L)" …).
        MacTooltips.install();
        // Dikte/IME teşhisi (UDE_DICTLOG=1 ile etkin; aksi halde no-op).
        DictationProbe.install();
        // Dikte düzeltmesi: no-op InputMethodListener → sentetik keyTyped kapanır,
        // commit kit'in normal yazma aksiyonundan akar (metin kaybı + donma biter).
        DictationFix.install();
        // macOS sistem geneli Metin Değiştirme (Ayarlar → Klavye) kısayollarını
        // UDE metin alanlarında uygula ("mrb " → "Merhaba! ").
        TextReplace.install();
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
     * Agent jar'ın yanındaki libnativedialogkeys.dylib'i yükler (varsa).
     * Kaynak: scripts/macos-textkeys/native/NativeDialogKeys.m — yalnız key
     * window NSSavePanel iken Cmd kısayollarını AppKit eylemine çevirir.
     * Dylib bulunamazsa sessizce geçilir (uygulama native panelde yapıştırma
     * olmadan da çalışır; agent asla açılışı engellememeli).
     */
    private static void loadNativeDialogKeys() {
        try {
            java.io.File jar = new java.io.File(MacTextKeys.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            java.io.File lib = new java.io.File(jar.getParentFile(), "libnativedialogkeys.dylib");
            if (lib.isFile()) System.load(lib.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[macos-textkeys] native diyalog dylib yüklenemedi: " + t);
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

        // Word benzeri Backspace/Delete ile tablo silme.
        TableDelete.bind(tc);
    }

    /**
     * Bir Option kısayolunu sol Option (ALT), sağ Option'ın gerçek imzası
     * (ALT | ALT_GRAPH) ve güvenlik için yalnız-ALT_GRAPH maskesiyle bağlar.
     * Sağ Option modifiers'ı her iki biti birden taşıdığından kombinasyon
     * bağlaması ŞARTtır (tek-bit bağlamalar Swing'in tam-maske eşleşmesinde
     * sağ Option'a uymaz). {@code extraMods} ek maskedir (ör. SHIFT) ya da 0.
     */
    private static void putOpt(InputMap im, int keyCode, int extraMods, Object action) {
        im.put(KeyStroke.getKeyStroke(keyCode, OPT | extraMods), action);
        im.put(KeyStroke.getKeyStroke(keyCode, OPT | OPT_R | extraMods), action);
        im.put(KeyStroke.getKeyStroke(keyCode, OPT_R | extraMods), action);
    }

    /** macOS Cmd+Delete: imleçten satır başına kadar olan metni siler. */
    private static final Action DELETE_TO_LINE_START = new TextAction(ACT_DELETE_TO_LINE_START) {
        @Override public void actionPerformed(ActionEvent e) {
            JTextComponent t = getTextComponent(e);
            if (t == null || !t.isEditable() || !t.isEnabled()) return;
            try {
                // Aktif seçim varsa (ör. Cmd+A ile tümünü seç, Cmd hâlâ basılıyken
                // Backspace) macOS davranışı seçimin tamamını siler. replaceSelection
                // UDE editöründe de doğru yoldur (hj.replaceSelection → yapı/geri-al
                // korunur); düz alanlarda standart davranıştır.
                if (t.getSelectionStart() != t.getSelectionEnd()) {
                    t.replaceSelection("");
                    return;
                }
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
