package macostextkeys;

/*
 * macOS'ta Option ile üretilen özel karakterleri (@, #, [, ], {, }, \, | …)
 * UDE metin alanına yazılabilir kılan javaagent parçası.
 *
 * Sorun: Türkçe-Q Mac klavyede bu karakterler Option(+Shift)+tuş ile üretilir.
 *   Option = Swing'de ALT. İki olası engel vardır: (1) ALT+tuş bir menü
 *   mnemonic'ine denk gelirse KEY_PRESSED tüketilir → JVM eşleşen KEY_TYPED'i
 *   hiç üretmez; (2) özel metin motoru / WebLaF'in keyTyped işleyicisi bazı
 *   ALT'lı karakterleri metne almaz. (€ = Option+E çalıştığından genel bir
 *   Option filtresi YOKtur; engel karaktere özeldir.)
 *
 * Çözüm: Bileşenden ÖNCE çalışan bir KeyEventDispatcher:
 *   • KEY_PRESSED (gezinme/kontrol tuşları hariç): consume ETMEDEN return true.
 *     return true → olay post-processor'a (mnemonic) ulaşmaz; consume edilmediği
 *     için "consumed" bayrağı set edilmez → KEY_TYPED yine üretilir. Burada
 *     EKLEME YAPILMAZ: macOS'ta KEY_PRESSED.getKeyChar() çoğu zaman birleşik
 *     karakteri değil temel harfi taşır; doğru karakter yalnız KEY_TYPED'te gelir.
 *   • KEY_TYPED: yazdırılabilir karakteri replaceSelection ile ekler ve tüketir
 *     (çift-yazma olmaz; varsayılan ekleme çalışmaz).
 *
 * Not: Cmd/Ctrl içeren olaylara dokunulmaz (MacShortcutRemap / uygulamaya ait).
 *   Gezinme/silme (Option+ok/Backspace) dışlandığından MacTextKeys kelime
 *   bağlamaları bozulmaz. Tüm hatalar yutulur — agent uygulamayı düşürmemeli.
 */

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import javax.swing.text.JTextComponent;

public final class MacOptionChars {

    /** Sol Option = ALT, sağ Option = ALT_GRAPH. */
    private static final int ALT    = InputEvent.ALT_DOWN_MASK;
    private static final int ALT_GR = InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final int META   = InputEvent.META_DOWN_MASK;   // Cmd
    private static final int CTRL   = InputEvent.CTRL_DOWN_MASK;

    /** UDE_KEYLOG=1 ile teşhis logu açılır (System.err yutulduğu için dosyaya). */
    private static final boolean LOG = "1".equals(System.getenv("UDE_KEYLOG"));

    private MacOptionChars() {}

    static void install() {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(MacOptionChars::dispatch);
        } catch (Throwable t) {
            System.err.println("[macos-optionchars] kurulamadı: " + t);
        }
    }

    /** true = "olayı ben işledim/yuttum". */
    static boolean dispatch(KeyEvent e) {
        try {
            int mods = e.getModifiersEx();
            boolean option = (mods & ALT) != 0 || (mods & ALT_GR) != 0;
            if (!option) return false;                       // yalnız Option olayları
            if ((mods & META) != 0 || (mods & CTRL) != 0) return false; // Cmd/Ctrl bizim değil

            Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (!(fo instanceof JTextComponent)) return false;
            JTextComponent tc = (JTextComponent) fo;
            if (!tc.isEditable() || !tc.isEnabled()) return false;

            int id = e.getID();
            if (id == KeyEvent.KEY_PRESSED) {
                if (isNavOrControl(e.getKeyCode())) { log(e, false); return false; }
                // Mnemonic'i baypas et, KEY_TYPED üretimini koru: consume ETME.
                log(e, true);
                return true;
            }
            if (id == KeyEvent.KEY_TYPED) {
                char c = e.getKeyChar();
                if (c >= 0x20 && c != 0x7F) {
                    tc.replaceSelection(String.valueOf(c));
                    log(e, true);
                    return true;
                }
                log(e, false);
                return false;
            }
            return false;                                    // KEY_RELEASED vb.
        } catch (Throwable t) {
            return false;                                    // dispatcher uygulamayı düşürmemeli
        }
    }

    /** Option ile birlikte gelse de yutulmaması gereken gezinme/kontrol tuşları. */
    private static boolean isNavOrControl(int kc) {
        switch (kc) {
            case KeyEvent.VK_LEFT:  case KeyEvent.VK_RIGHT:
            case KeyEvent.VK_UP:    case KeyEvent.VK_DOWN:
            case KeyEvent.VK_BACK_SPACE: case KeyEvent.VK_DELETE:
            case KeyEvent.VK_ENTER: case KeyEvent.VK_TAB: case KeyEvent.VK_ESCAPE:
            case KeyEvent.VK_HOME:  case KeyEvent.VK_END:
            case KeyEvent.VK_PAGE_UP: case KeyEvent.VK_PAGE_DOWN:
            case KeyEvent.VK_F1:  case KeyEvent.VK_F2:  case KeyEvent.VK_F3:
            case KeyEvent.VK_F4:  case KeyEvent.VK_F5:  case KeyEvent.VK_F6:
            case KeyEvent.VK_F7:  case KeyEvent.VK_F8:  case KeyEvent.VK_F9:
            case KeyEvent.VK_F10: case KeyEvent.VK_F11: case KeyEvent.VK_F12:
                return true;
            default:
                return false;
        }
    }

    private static void log(KeyEvent e, boolean handled) {
        if (!LOG) return;
        try {
            File f = new File(System.getProperty("user.home"), "Library/Logs/ude-keylog.txt");
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(String.format(
                    "id=%d keyCode=%d keyChar=0x%04x mods=0x%x handled=%b%n",
                    e.getID(), e.getKeyCode(), (int) e.getKeyChar(),
                    e.getModifiersEx(), handled));
            }
        } catch (IOException ignore) {
            // log başarısızsa sessizce geç
        }
    }
}
