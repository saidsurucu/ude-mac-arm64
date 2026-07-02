package macoslinespacing;

import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Satır aralığı çekirdeği: seçime (yoksa imlecin paragrafına) UDE'nin
 * kullandığı StyleConstants.LineSpacing özniteliğini basar.
 *
 * Kodlama (UDF ile birebir): saklanan değer = görünen aralık − 1
 * (1,0→0.0, 1,15→0.15, 1,5→0.5). UDE Paragraf diyaloğu (gui.cM) ve
 * udf-converter-go aynı dönüşümü yapar; content.xml <paragraph
 * LineSpacing="..."> float — formatta değişiklik yok.
 *
 * replace=false: diğer paragraf öznitelikleri (hizalama, girinti, liste
 * anahtarları) korunur. Caret'e dokunulmaz (moveDot NPE zinciri).
 */
public final class LineSpacingApply {

    public static final float[] VALUES = {1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f};
    public static final String[] LABELS = {"1,0", "1,15", "1,5", "2,0", "2,5", "3,0"};

    private LineSpacingApply() {}

    /** display = görünen aralık (1.5 gibi). StyledDocument değilse false. */
    public static boolean apply(JTextComponent tc, float display) {
        Document d = tc.getDocument();
        if (!(d instanceof StyledDocument)) return false;
        StyledDocument doc = (StyledDocument) d;
        int s = Math.min(tc.getSelectionStart(), tc.getSelectionEnd());
        int e = Math.max(tc.getSelectionStart(), tc.getSelectionEnd());
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(attrs, display <= 1.0f ? 0f : display - 1f);
        doc.setParagraphAttributes(s, e - s, attrs, false);
        return true;
    }

    /** İmlecin paragrafının görünen aralığı (öznitelik yoksa 1.0). */
    public static float current(JTextComponent tc) {
        Document d = tc.getDocument();
        if (!(d instanceof StyledDocument)) return 1.0f;
        StyledDocument doc = (StyledDocument) d;
        return StyleConstants.getLineSpacing(
                doc.getParagraphElement(tc.getCaretPosition()).getAttributes()) + 1f;
    }

    /** Float hassasiyeti için gevşek eşleme (1.15f−1f = 0.14999998). */
    public static boolean matches(float current, float option) {
        return Math.abs(current - option) < 0.02f;
    }
}
