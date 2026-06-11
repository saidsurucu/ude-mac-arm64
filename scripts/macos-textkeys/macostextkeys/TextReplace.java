package macostextkeys;

/*
 * macOS sistem geneli "Metin Değiştirme" (Text Replacement) genişleticisi.
 *
 * Sorun: Sistem Ayarları → Klavye → Metin Değiştirmeleri kısayolları
 * ("mrb" → "Merhaba!") native uygulamalarda Cocoa metin-denetim katmanından
 * uygulanır; Java/Swing bu kanala bağlanmaz → UDE'de çalışmaz.
 *
 * Çözüm: KEY_TYPED ile sonlandırıcı karakter (boşluk, Enter, noktalama)
 * yazıldığında, UDE'nin kendi keyTyped zinciri işini bitirdikten sonra
 * (invokeLater) caret'in solundaki sözcük sistem listesiyle (ReplacementStore)
 * eşleştirilir ve belge üzerinden değiştirilir. Kısayol tamamen küçük harfse
 * ilk-harfi-büyük yazılışı da eşleşir (macOS davranışı + UDE "Otomatik Büyük
 * Harf" uyumu) ve karşılığın ilk harfi tr-TR ile büyütülür.
 */

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.Map;

import javax.swing.JEditorPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.Document;
import javax.swing.text.EditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledEditorKit;

public final class TextReplace {

    private static final Locale TR = new Locale("tr", "TR");
    /** Değiştirmeyi tetikleyen sonlandırıcılar (yazılan karakter). */
    static final String TRIGGERS = " \t\n\r.,;:!?)\"'";
    /** Geri taramada sözcüğü sınırlayanlar (tetikleyiciler + açılış ayraçları). */
    private static final String BOUNDARIES = TRIGGERS + "([{";

    private TextReplace() {}

    /** Agent giriş noktası; -Dmacostextreplace.off=1 ile tamamen kapatılır. */
    public static void install() {
        if (System.getProperty("macostextreplace.off") != null) return;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() == KeyEvent.KEY_TYPED) {
                        onKeyTyped((KeyEvent) e);
                    } else if (e.getID() == WindowEvent.WINDOW_ACTIVATED) {
                        // Mac'te eklenen yeni kısayol restart'sız gelsin.
                        ReplacementStore.refreshAsync();
                    }
                }
            }, AWTEvent.KEY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
            ReplacementStore.refreshAsync();
            TrLog.log("TextReplace kuruldu");
        } catch (Throwable t) {
            System.err.println("[macos-textkeys] TextReplace kurulamadı: " + t);
        }
    }

    private static void onKeyTyped(KeyEvent e) {
        try {
            if (TRIGGERS.indexOf(e.getKeyChar()) < 0) return;
            Object src = e.getSource();
            if (!(src instanceof JTextComponent) || src instanceof JPasswordField) return;
            final JTextComponent tc = (JTextComponent) src;
            if (!tc.isEditable() || !tc.isEnabled()) return;
            // AWTEventListener bileşen işlemeden ÖNCE çağrılır; karakterin belgeye
            // girmesini ve UDE'nin kendi keyTyped zincirini (otomatik büyük harf
            // vb.) beklemek için invokeLater.
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    maybeReplace(tc, ReplacementStore.table(), ReplacementStore.maxShortcutLen());
                }
            });
        } catch (Throwable t) {
            TrLog.log("onKeyTyped hata: " + t);
        }
    }

    static boolean isBoundary(char c) {
        return BOUNDARIES.indexOf(c) >= 0;
    }

    /** end'den (hariç) geriye doğru sözcük başlangıcını bulur. */
    static int tokenStart(String text, int end) {
        int i = end;
        while (i > 0 && !isBoundary(text.charAt(i - 1))) i--;
        return i;
    }

    /**
     * Birebir eşleşme; yoksa macOS uyarlaması: kısayol tamamen küçük harfse
     * ilk-harfi-büyük yazılışı da eşleşir, karşılığın ilk harfi büyütülür.
     */
    static String lookup(Map<String, String> table, String word) {
        String exact = table.get(word);
        if (exact != null) return exact;
        String decap = decapFirst(word);
        if (decap.equals(word)) return null;
        String phrase = table.get(decap);
        if (phrase != null && decap.equals(decap.toLowerCase(TR))) return capFirst(phrase);
        return null;
    }

    static String capFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(TR) + s.substring(1);
    }

    static String decapFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(TR) + s.substring(1);
    }

    /**
     * Caret'in solundaki "<sözcük><sonlandırıcı>" desenini tabloyla eşleştirip
     * sözcüğü değiştirir; sonlandırıcı korunur, caret arkasında bırakılır.
     * invokeLater'dan çağrılır; belge bu arada değişmiş olabilir → her koşul
     * yeniden doğrulanır, her hata yutularak statüko korunur.
     */
    static boolean maybeReplace(JTextComponent tc, Map<String, String> table, int maxShortcutLen) {
        try {
            if (table.isEmpty() || maxShortcutLen <= 0) return false;
            int caret = tc.getCaretPosition();
            if (caret < 2) return false;
            int from = Math.max(0, caret - (maxShortcutLen + 1));
            String ctx = tc.getDocument().getText(from, caret - from);
            char last = ctx.charAt(ctx.length() - 1);
            if (TRIGGERS.indexOf(last) < 0) return false;
            int tokEnd = ctx.length() - 1;
            int tokStart = tokenStart(ctx, tokEnd);
            if (tokStart >= tokEnd) return false;
            if (tokStart == 0 && from > 0
                    && !isBoundary(tc.getDocument().getText(from - 1, 1).charAt(0))) {
                return false; // sözcük pencereden uzun → kısayol olamaz
            }
            String word = ctx.substring(tokStart, tokEnd);
            String phrase = lookup(table, word);
            if (phrase == null) return false;
            int docStart = from + tokStart;
            // Seçim üzerinden değil (moveCaretPosition/moveDot UDE'nin caretUpdate
            // zincirini — hB.caretUpdate → gui.aD.a — NPE'ye düşürüyor), belge
            // düzeyinde atomik replace; yazı nitelikleri caret'in giriş
            // niteliklerinden (JTextPane.replaceSelection'ın kendi yolu).
            Document doc = tc.getDocument();
            AttributeSet attr = null;
            if (tc instanceof JEditorPane) {
                EditorKit kit = ((JEditorPane) tc).getEditorKit();
                if (kit instanceof StyledEditorKit) {
                    attr = ((StyledEditorKit) kit).getInputAttributes().copyAttributes();
                }
            }
            if (doc instanceof AbstractDocument) {
                ((AbstractDocument) doc).replace(docStart, word.length(), phrase, attr);
            } else {
                doc.remove(docStart, word.length());
                doc.insertString(docStart, phrase, attr);
            }
            tc.setCaretPosition(docStart + phrase.length() + 1);
            TrLog.log("değiştirildi: '" + word + "' → " + phrase.length() + " karakter");
            return true;
        } catch (Throwable t) {
            TrLog.log("maybeReplace hata: " + t);
            return false;
        }
    }
}
