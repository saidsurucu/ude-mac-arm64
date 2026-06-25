import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * NativeInsert düz-mod PARAGRAF biçimi testi (headless; UDE tipi gerektiren
 * tablo YOK). İmleç paragrafı iki-yana-yaslı + özel aralık/girinti iken düz
 * yapıştırılan gövde paragrafları o biçimi alır; kaynağın sağa-yaslısı düşer.
 * Derle+çalıştır:
 *   javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java
 *   java -cp /tmp/ppfmt PlainPasteParaFormatTest
 */
public class PlainPasteParaFormatTest {
    public static void main(String[] a) throws Exception {
        checkBodyAdoptsCursorFormat();
        checkRichPasteRegression();
        checkListKeepsSourceIndent();
        System.out.println("PlainPasteParaFormatTest OK");
    }

    /** Düz mod: gövde paragrafı imlecin JUSTIFIED + LineSpacing/SpaceBelow'unu alır. */
    static void checkBodyAdoptsCursorFormat() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        // İmleç paragrafı (para0) lokal olarak iki-yana-yaslı + özel aralık.
        SimpleAttributeSet cp = new SimpleAttributeSet();
        StyleConstants.setAlignment(cp, StyleConstants.ALIGN_JUSTIFIED);
        StyleConstants.setLineSpacing(cp, 0.5f);
        StyleConstants.setSpaceBelow(cp, 6f);
        doc.setParagraphAttributes(0, 1, cp, false);
        pane.setCaretPosition(0);

        // İmleç karakter stili (kaynaktan farklı).
        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak: İKİ sağa-yaslı paragraf (2.si taze oluşur → drop kanıtı).
        String html =
              "<p style='text-align:right'>Birinci satir</p>"
            + "<p style='text-align:right'>Ikinci satir</p>";
        if (!macospasterich.RichPaste.insertInto(pane, html, cursor))
            throw new AssertionError("insertInto(plain) false");

        Element root = doc.getDefaultRootElement();
        boolean sawBody = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            String txt = doc.getText(para.getStartOffset(),
                    Math.min(para.getEndOffset(), doc.getLength()) - para.getStartOffset());
            if (txt.trim().isEmpty()) continue;
            sawBody = true;
            AttributeSet at = para.getAttributes();
            if (StyleConstants.getAlignment(at) != StyleConstants.ALIGN_JUSTIFIED)
                throw new AssertionError("gövde hizalaması imleçten alınmadı: '" + txt.trim()
                        + "' → align=" + StyleConstants.getAlignment(at));
            if (StyleConstants.getLineSpacing(at) != 0.5f)
                throw new AssertionError("gövde LineSpacing imleçten alınmadı: '" + txt.trim()
                        + "' → " + StyleConstants.getLineSpacing(at));
        }
        if (!sawBody) throw new AssertionError("hiç gövde paragrafı eklenmedi");
    }

    /** Rich paste (cursorAttrs=null): kaynak hizalaması KORUNUR — davranış değişmemeli. */
    static void checkRichPasteRegression() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        pane.setCaretPosition(0);
        String html = "<p style='text-align:right'>Sag yasli kaynak</p>";
        if (!macospasterich.RichPaste.insertInto(pane, html))   // 2-arg → cursorAttrs null
            throw new AssertionError("insertInto(rich) false");
        Element root = doc.getDefaultRootElement();
        boolean sawBody = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            String txt = doc.getText(para.getStartOffset(),
                    Math.min(para.getEndOffset(), doc.getLength()) - para.getStartOffset());
            if (txt.trim().isEmpty()) continue;
            sawBody = true;
            if (StyleConstants.getAlignment(para.getAttributes()) != StyleConstants.ALIGN_RIGHT)
                throw new AssertionError("rich paste kaynak hizalamasını kaybetti: '"
                        + txt.trim() + "'");
        }
        if (!sawBody) throw new AssertionError("rich: hiç gövde paragrafı eklenmedi");
    }

    /**
     * Liste paragrafı: işaret + KAYNAK girintisi korunur (imleç sıfır/farklı
     * girintisi EZMEZ — hanging indent); hizalama imleçten gelir.
     */
    static void checkListKeepsSourceIndent() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        // İmleç paragrafı: JUSTIFIED + BÜYÜK sol girinti (listeye SIZMAMALI).
        SimpleAttributeSet cp = new SimpleAttributeSet();
        StyleConstants.setAlignment(cp, StyleConstants.ALIGN_JUSTIFIED);
        StyleConstants.setLeftIndent(cp, 90f);
        doc.setParagraphAttributes(0, 1, cp, false);
        pane.setCaretPosition(0);

        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak liste: girinti taşımaz (UDE list indent ListLevel'dan; LeftIndent=0).
        String html = "<ul><li>Madde bir</li></ul>";
        if (!macospasterich.RichPaste.insertInto(pane, html, cursor))
            throw new AssertionError("insertInto(plain list) false");

        Element root = doc.getDefaultRootElement();
        boolean sawList = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            AttributeSet at = root.getElement(p).getAttributes();
            if (at.getAttribute("Bulleted") == null && at.getAttribute("Numbered") == null) continue;
            sawList = true;
            // İmlecin 90f girintisi listeye sızMAMALI (kaynak 0).
            if (StyleConstants.getLeftIndent(at) != 0f)
                throw new AssertionError("liste girintisi imleçten sızdı (hanging indent bozuldu): "
                        + StyleConstants.getLeftIndent(at));
            // Hizalama imleçten (JUSTIFIED) gelmeli.
            if (StyleConstants.getAlignment(at) != StyleConstants.ALIGN_JUSTIFIED)
                throw new AssertionError("liste hizalaması imleçten alınmadı: "
                        + StyleConstants.getAlignment(at));
        }
        if (!sawList) throw new AssertionError("liste paragrafı bulunamadı");
    }
}
