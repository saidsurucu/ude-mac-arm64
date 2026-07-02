import javax.swing.JTextPane;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import macoslinespacing.LineSpacingApply;

/**
 * LineSpacingApply birim testi (headless, UDE tipi gerekmez).
 * Derle + çalıştır:
 *   javac --release 11 -encoding UTF-8 -d /tmp/lsout \
 *     scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java tests/LineSpacingApplyTest.java
 *   java -cp /tmp/lsout LineSpacingApplyTest
 */
public class LineSpacingApplyTest {
    private static int fails = 0;

    public static void main(String[] a) throws Exception {
        testSingleParagraph();
        testSelectionSpansParagraphs();
        testOtherAttrsPreserved();
        testResetToSingle();
        testCurrentAndMatches();
        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }

    private static JTextPane pane(String text) throws Exception {
        JTextPane p = new JTextPane();
        p.getDocument().insertString(0, text, null);
        return p;
    }

    /** 1) Tek paragraf: imleç içindeyken 1,5 → LineSpacing==0.5 */
    private static void testSingleParagraph() throws Exception {
        JTextPane p = pane("birinci paragraf\nikinci paragraf\n");
        p.setCaretPosition(3); // 1. paragrafın içi
        check("apply döndü", LineSpacingApply.apply(p, 1.5f));
        StyledDocument doc = (StyledDocument) p.getDocument();
        float ls1 = StyleConstants.getLineSpacing(doc.getParagraphElement(3).getAttributes());
        float ls2 = StyleConstants.getLineSpacing(doc.getParagraphElement(20).getAttributes());
        check("p1 LineSpacing==0.5", Math.abs(ls1 - 0.5f) < 0.001f);
        check("p2 dokunulmadı (0)", ls2 == 0f);
    }

    /** 2) Çok paragraflı seçim: her iki paragrafa da uygulanır */
    private static void testSelectionSpansParagraphs() throws Exception {
        JTextPane p = pane("birinci paragraf\nikinci paragraf\n");
        p.setSelectionStart(3);
        p.setSelectionEnd(20); // 2. paragrafın içine taşar
        check("apply döndü", LineSpacingApply.apply(p, 2.0f));
        StyledDocument doc = (StyledDocument) p.getDocument();
        check("p1==1.0", Math.abs(StyleConstants.getLineSpacing(
                doc.getParagraphElement(3).getAttributes()) - 1.0f) < 0.001f);
        check("p2==1.0", Math.abs(StyleConstants.getLineSpacing(
                doc.getParagraphElement(20).getAttributes()) - 1.0f) < 0.001f);
    }

    /** 3) replace=false: mevcut hizalama/girinti korunur */
    private static void testOtherAttrsPreserved() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        StyledDocument doc = (StyledDocument) p.getDocument();
        SimpleAttributeSet pre = new SimpleAttributeSet();
        StyleConstants.setAlignment(pre, StyleConstants.ALIGN_RIGHT);
        StyleConstants.setLeftIndent(pre, 42f);
        doc.setParagraphAttributes(0, 1, pre, false);
        p.setCaretPosition(3);
        LineSpacingApply.apply(p, 1.5f);
        Element para = doc.getParagraphElement(3);
        check("hizalama korunur", StyleConstants.getAlignment(para.getAttributes())
                == StyleConstants.ALIGN_RIGHT);
        check("girinti korunur", StyleConstants.getLeftIndent(para.getAttributes()) == 42f);
        check("aralık geldi", Math.abs(StyleConstants.getLineSpacing(
                para.getAttributes()) - 0.5f) < 0.001f);
    }

    /** 4) 1,0 seçimi mevcut 0.5'i 0.0'a indirir */
    private static void testResetToSingle() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        p.setCaretPosition(3);
        LineSpacingApply.apply(p, 1.5f);
        LineSpacingApply.apply(p, 1.0f);
        StyledDocument doc = (StyledDocument) p.getDocument();
        check("sıfırlandı", StyleConstants.getLineSpacing(
                doc.getParagraphElement(3).getAttributes()) == 0f);
    }

    /** 5) current() + matches() eşlemesi (1,15 float hassasiyeti dahil) */
    private static void testCurrentAndMatches() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        p.setCaretPosition(3);
        check("varsayılan 1,0", LineSpacingApply.matches(LineSpacingApply.current(p), 1.0f));
        LineSpacingApply.apply(p, 1.15f);
        check("1,15 eşleşir", LineSpacingApply.matches(LineSpacingApply.current(p), 1.15f));
        check("1,5 eşleşmez", !LineSpacingApply.matches(LineSpacingApply.current(p), 1.5f));
        check("dizi boyları", LineSpacingApply.VALUES.length == 6
                && LineSpacingApply.LABELS.length == 6);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL ") + name);
        if (!ok) fails++;
    }
}
