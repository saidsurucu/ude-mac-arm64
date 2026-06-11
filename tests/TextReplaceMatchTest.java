package macostextkeys;

import java.util.HashMap;
import java.util.Map;

import javax.swing.JTextArea;

/*
 * TextReplace eşleştirme çekirdeği testi (GUI'siz; JTextArea headless çalışır).
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/trm-test \
 *     scripts/macos-textkeys/macostextkeys/TrLog.java \
 *     scripts/macos-textkeys/macostextkeys/TextReplace.java \
 *     tests/TextReplaceMatchTest.java
 *   java -Djava.awt.headless=true -cp /tmp/trm-test macostextkeys.TextReplaceMatchTest
 */
public final class TextReplaceMatchTest {

    static int fails = 0;

    public static void main(String[] args) {
        Map<String, String> t = new HashMap<String, String>();
        t.put("mrb", "Merhaba!");
        t.put("Yldym", "Yoldayım!");
        t.put("işadr", "Şişli/İstanbul adresi");
        t.put("adr", "Satır 1\nSatır 2");

        // --- tokenStart / isBoundary ---
        eq("tokenStart düz", 6, TextReplace.tokenStart("selam mrb", 9));
        eq("tokenStart belge başı", 0, TextReplace.tokenStart("mrb", 3));
        eq("tokenStart paren", 1, TextReplace.tokenStart("(mrb", 4));
        ok("isBoundary boşluk", TextReplace.isBoundary(' '));
        ok("isBoundary nokta", TextReplace.isBoundary('.'));
        ok("isBoundary değil harf", !TextReplace.isBoundary('ş'));

        // --- lookup ---
        eq("lookup birebir", "Merhaba!", TextReplace.lookup(t, "mrb"));
        eq("lookup ilk-harf-büyük uyarlama", "Merhaba!", TextReplace.lookup(t, "Mrb"));
        eq("lookup tr İ uyarlama", "Şişli/İstanbul adresi", TextReplace.lookup(t, "İşadr"));
        eq("lookup büyük kısayol birebir", "Yoldayım!", TextReplace.lookup(t, "Yldym"));
        eq("lookup büyük kısayol küçüğü eşleşmez", null, TextReplace.lookup(t, "yldym"));
        eq("lookup yok", null, TextReplace.lookup(t, "xmrb"));

        // --- maybeReplace (belge üzerinde) ---
        eq("replace boşlukla", "Merhaba! ", replaced("mrb ", 4, t));
        eq("replace noktayla", "selam Merhaba!.", replaced("selam mrb.", 10, t));
        eq("replace ilk-harf-büyük", "Merhaba! ", replaced("Mrb ", 4, t));
        eq("replace çok satırlı", "Satır 1\nSatır 2 ", replaced("adr ", 4, t));
        eq("replace eşleşmeyen dokunmaz", "xmrb ", replaced("xmrb ", 5, t));
        eq("replace tetiksiz dokunmaz", "mrb", replaced("mrb", 3, t));
        eq("replace ortada", "Merhaba! sonu", replaced("mrb sonu", 4, t));

        // Caret konumu: değiştirilen metin + sonlandırıcının arkası.
        JTextArea ta = new JTextArea("mrb ");
        ta.setCaretPosition(4);
        TextReplace.maybeReplace(ta, t, 16);
        eq("caret sonlandırıcı arkasında", 9, ta.getCaretPosition());

        // Pencere sınırı koruması: sözcük maxLen penceresinden uzunsa eşleştirme yok.
        JTextArea ta2 = new JTextArea("aaaaaaaaaaaaaaaaaaaamrb ");
        ta2.setCaretPosition(24);
        TextReplace.maybeReplace(ta2, t, 3);
        eq("pencere sınırı", "aaaaaaaaaaaaaaaaaaaamrb ", ta2.getText());

        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }

    static String replaced(String text, int caret, Map<String, String> t) {
        JTextArea ta = new JTextArea(text);
        ta.setCaretPosition(caret);
        TextReplace.maybeReplace(ta, t, 16);
        return ta.getText();
    }

    static void eq(String name, Object want, Object got) {
        boolean p = want == null ? got == null : want.equals(got);
        if (!p) { fails++; System.out.println("FAIL " + name + ": beklenen=" + want + " gelen=" + got); }
    }

    static void ok(String name, boolean cond) { if (!cond) { fails++; System.out.println("FAIL " + name); } }
}
