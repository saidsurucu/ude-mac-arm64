package macostextkeys;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;

import javax.swing.Action;
import javax.swing.JTextArea;

/**
 * Cmd+Delete (satır başına kadar sil) aksiyonu, AKTİF BİR SEÇİM varken o seçimi
 * silmelidir (macOS davranışı). Kullanıcı senaryosu: Cmd+A ile tümünü seç, Cmd
 * hâlâ basılıyken Backspace → seçimin tamamı gitmeli.
 *
 * Elle çalıştır: javac -d /tmp/cmddel scripts/macos-textkeys/macostextkeys/MacTextKeys.java \
 *                      tests/CmdDeleteSelectionTest.java
 *                java -cp /tmp/cmddel macostextkeys.CmdDeleteSelectionTest
 */
public final class CmdDeleteSelectionTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Action act = deleteToLineStartAction();

        // 1) ÇEKİRDEK HATA: imleç offset 0'dayken (seçim geriye doğru yapılmış)
        //    aksiyon seçimi yok sayıp HİÇBİR ŞEY silmiyordu. View'a bağlı değil,
        //    deterministik. Cmd+A sonrası Cmd basılı Backspace'in özü budur.
        deletesBackwardSelectionAtOffsetZero(act);

        // 2) İleri yönde tam seçim (dot=son, mark=0): seçim silinmeli.
        deletesForwardSelection(act);

        // 3) Seçim yokken eski davranış: imleçten satır başına kadar sil.
        keepsDeleteToLineStartWhenNoSelection(act);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " durum başarısız");
            System.exit(1);
        }
        System.out.println("OK: tüm durumlar geçti");
    }

    private static void deletesBackwardSelectionAtOffsetZero(Action act) {
        JTextArea ta = new JTextArea("hello world");
        ta.setCaretPosition(6);     // mark=6
        ta.moveCaretPosition(0);    // dot=0 → imleç 0'da, "hello " seçili
        fire(act, ta);
        check("offset-0 geri seçim", "world", ta.getText());
    }

    private static void deletesForwardSelection(Action act) {
        JTextArea ta = new JTextArea("hello world");
        ta.setCaretPosition(0);     // mark=0
        ta.moveCaretPosition(5);    // dot=5 → "hello" seçili, imleç 5'te
        fire(act, ta);
        check("ileri seçim", " world", ta.getText());
    }

    private static void keepsDeleteToLineStartWhenNoSelection(Action act) {
        JTextArea ta = new JTextArea("hello world");
        ta.setCaretPosition(ta.getText().length()); // satır sonunda, seçim yok
        fire(act, ta);
        // Tek satırda imleçten satır başına kadar = tüm satır gider.
        check("seçimsiz satır başına sil", "", ta.getText());
    }

    private static void fire(Action act, JTextArea ta) {
        act.actionPerformed(new ActionEvent(ta, ActionEvent.ACTION_PERFORMED, "mac-delete-to-line-start"));
    }

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("  ok  " + name);
        } else {
            failures++;
            System.out.println("  XX  " + name + ": beklenen=" + q(expected) + " gelen=" + q(actual));
        }
    }

    private static String q(String s) { return "\"" + s.replace("\n", "\\n") + "\""; }

    private static Action deleteToLineStartAction() throws Exception {
        Field f = MacTextKeys.class.getDeclaredField("DELETE_TO_LINE_START");
        f.setAccessible(true);
        return (Action) f.get(null);
    }
}
