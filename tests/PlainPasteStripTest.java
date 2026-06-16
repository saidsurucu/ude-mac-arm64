import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * NativeInsert düz-karakter modu birim testi (headless; UDE tipi gerektiren
 * tablo/imaj YOK — yalnız paragraf + liste). RichPaste.insertInto(editor, html,
 * cursorAttrs) aşırı yüklemesini kullanır.
 * Derle + çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java
 *   java -cp OUT PlainPasteStripTest
 */
public class PlainPasteStripTest {
    public static void main(String[] a) throws Exception {
        // İmleç hedef stili: Calibri 16 (kaynaktan farklı). bold YOK, renk YOK.
        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak: kalın + kırmızı + Arial 20 metin + madde listesi.
        String html =
              "<p><b><span style='font-family:Arial;font-size:20pt;color:#FF0000'>Kalin kirmizi</span></b></p>"
            + "<ul><li>Madde bir</li></ul>";

        JTextPane pane = new JTextPane();
        pane.setCaretPosition(0);
        boolean ok = macospasterich.RichPaste.insertInto(pane, html, cursor);
        if (!ok) throw new AssertionError("insertInto(plain) false döndü");

        StyledDocument doc = (StyledDocument) pane.getDocument();

        // 1) Karakter stili düşmüş, imleç stili alınmış olmalı.
        boolean sawText = false;
        Element root = doc.getDefaultRootElement();
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            for (int r = 0; r < para.getElementCount(); r++) {
                Element run = para.getElement(r);
                int s = run.getStartOffset(), e = Math.min(run.getEndOffset(), doc.getLength());
                if (e <= s) continue;
                String txt = doc.getText(s, e - s);
                if (txt.trim().isEmpty()) continue;
                sawText = true;
                AttributeSet at = run.getAttributes();
                if (StyleConstants.isBold(at))
                    throw new AssertionError("kalın düşmedi: '" + txt + "'");
                if (StyleConstants.getForeground(at).getRGB() != new java.awt.Color(0,0,0).getRGB()
                        && at.isDefined(StyleConstants.Foreground))
                    throw new AssertionError("renk düşmedi: '" + txt + "'");
                if (!"Calibri".equals(StyleConstants.getFontFamily(at)))
                    throw new AssertionError("font imleç stilini almadı: '" + txt
                            + "' → " + StyleConstants.getFontFamily(at));
            }
        }
        if (!sawText) throw new AssertionError("hiç metin eklenmedi");

        // 2) Liste paragrafı korunmuş olmalı ("Bulleted" özniteliği).
        boolean sawBullet = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            if (root.getElement(p).getAttributes().getAttribute("Bulleted") != null) sawBullet = true;
        }
        if (!sawBullet) throw new AssertionError("liste (Bulleted) korunmadı");

        System.out.println("PlainPasteStripTest OK");
    }
}
