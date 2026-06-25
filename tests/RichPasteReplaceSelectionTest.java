import javax.swing.JTextPane;
import javax.swing.text.StyledDocument;

/**
 * NativeInsert seçim-değiştirme testi (headless): seçili metnin ÜZERİNE rich
 * yapıştırınca seçim KALDIRILMALI (Word/standart editör semantiği). Hata:
 * insert() getCaretPosition()'a ekleyip seçimi bırakıyordu → "üstüne ekliyor".
 * Derle + çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/RichPasteReplaceSelectionTest.java
 *   java -cp OUT RichPasteReplaceSelectionTest
 */
public class RichPasteReplaceSelectionTest {
    public static void main(String[] a) throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        doc.insertString(0, "AAA BBB CCC", null);

        // "BBB"yi seç (offset 4..7).
        pane.setSelectionStart(4);
        pane.setSelectionEnd(7);

        boolean ok = macospasterich.RichPaste.insertInto(pane, "<p>XXX</p>");
        if (!ok) throw new AssertionError("insertInto false döndü");

        String text = doc.getText(0, doc.getLength());
        if (text.contains("BBB"))
            throw new AssertionError("seçim kaldırılmadı, BBB hâlâ var: '" + text + "'");
        if (!text.contains("XXX"))
            throw new AssertionError("yapıştırılan metin yok: '" + text + "'");
        if (!text.startsWith("AAA ") || !text.trim().endsWith("CCC"))
            throw new AssertionError("bağlam bozuldu: '" + text + "'");

        System.out.println("RichPasteReplaceSelectionTest OK: '" + text.replace("\n", "\\n") + "'");
    }
}
