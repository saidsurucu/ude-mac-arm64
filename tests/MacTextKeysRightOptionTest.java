package macostextkeys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;

import javax.swing.InputMap;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

/*
 * macOS'ta SAĞ Option tuşunun ürettiği değiştirici, sol Option'dan FARKLIDIR:
 *   sol Option  → ALT_DOWN_MASK
 *   sağ Option  → ALT_DOWN_MASK | ALT_GRAPH_DOWN_MASK   (her iki bit birden!)
 * (Kaynak: sun.lwawt.macosx.NSEvent.nsToJavaModifiers — genel option bayrağı
 *  ALT, sağ-alternatif bayrağı ek olarak ALT_GRAPH ekler.)
 *
 * Swing InputMap eşleşmesi TAM maske eşleşmesi ister. putOpt yalnız "ALT" ve
 * yalnız "ALT_GRAPH" tek-bit bağlamaları kurarsa, sağ Option'ın 0x2200 olayı
 * hiçbirine eşleşmez → sağ Option kelime gezinmesi çalışmaz.
 *
 * Bu test, bağlamalar uygulandıktan sonra SAĞ Option imzasının (ALT|ALT_GRAPH)
 * kelime aksiyonuna çözüldüğünü doğrular. Düzeltmeden ÖNCE başarısız olur.
 */
public final class MacTextKeysRightOptionTest {

    private static final int ALT     = InputEvent.ALT_DOWN_MASK;
    private static final int ALT_GR  = InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final int SHIFT   = InputEvent.SHIFT_DOWN_MASK;
    private static final int RIGHT_OPT = ALT | ALT_GR;   // gerçek sağ Option imzası

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        JTextField tf = new JTextField();
        Method m = MacTextKeys.class.getDeclaredMethod("applyBindings0", JTextComponent.class);
        m.setAccessible(true);
        m.invoke(null, tf);

        InputMap im = tf.getInputMap();

        // Sol Option (regresyon koruması): hâlâ çalışmalı.
        check(im, KeyEvent.VK_LEFT,  ALT, DefaultEditorKit.previousWordAction, "sol Option + ←");
        check(im, KeyEvent.VK_RIGHT, ALT, DefaultEditorKit.nextWordAction,     "sol Option + →");

        // Sağ Option (asıl hata): ALT|ALT_GRAPH imzası kelime aksiyonuna çözülmeli.
        check(im, KeyEvent.VK_LEFT,  RIGHT_OPT, DefaultEditorKit.previousWordAction, "sağ Option + ←");
        check(im, KeyEvent.VK_RIGHT, RIGHT_OPT, DefaultEditorKit.nextWordAction,     "sağ Option + →");
        check(im, KeyEvent.VK_BACK_SPACE, RIGHT_OPT, DefaultEditorKit.deletePrevWordAction, "sağ Option + Backspace");
        check(im, KeyEvent.VK_DELETE,     RIGHT_OPT, DefaultEditorKit.deleteNextWordAction, "sağ Option + Delete");

        // Sağ Option + Shift ile seçerek gezinme de çalışmalı.
        check(im, KeyEvent.VK_LEFT,  RIGHT_OPT | SHIFT, DefaultEditorKit.selectionPreviousWordAction, "sağ Option + Shift + ←");
        check(im, KeyEvent.VK_RIGHT, RIGHT_OPT | SHIFT, DefaultEditorKit.selectionNextWordAction,     "sağ Option + Shift + →");

        if (failures == 0) System.out.println("TÜM TESTLER GEÇTİ");
        else { System.out.println(failures + " TEST BAŞARISIZ"); System.exit(1); }
    }

    private static void check(InputMap im, int keyCode, int mods, String expected, String label) {
        Object got = im.get(KeyStroke.getKeyStroke(keyCode, mods));
        if (expected.equals(got)) {
            System.out.println("GEÇTİ: " + label + " → " + got);
        } else {
            System.out.println("BAŞARISIZ: " + label + " → beklenen '" + expected + "', bulunan '" + got + "'");
            failures++;
        }
    }
}
