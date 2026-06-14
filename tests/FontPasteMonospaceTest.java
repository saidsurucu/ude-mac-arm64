import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;

/**
 * Regresyon testi: Word/web'den stilli yapıştırma sonrası caret'in indiği satır
 * ve sonraki yazım DAKTILO (Monospaced) fontuna düşmemeli.
 *
 * Kök neden (kanıtlı): NativeInsert paragraf-sonu "\n"'lerini fontsuz (null)
 * ekliyordu; FontFamily özniteliği olmayan içeriği Swing StyleConstants
 * "Monospaced"a düşürür → caret bu "\n"e/boş paragrafa inince editör giriş
 * öznitelikleri fontsuz kalır, sonraki yazım Monospaced çıkar ve "düzelmez".
 *
 * Derle (tüm macospasterich + bu dosya) ve çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/FontPasteMonospaceTest.java
 *   java -cp OUT FontPasteMonospaceTest
 */
public class FontPasteMonospaceTest {

    /** createInputAttributes protected → alt sınıfla açığa çıkar. */
    static final class Kit extends StyledEditorKit {
        void exposeInputAttrs(Element runEl, MutableAttributeSet into) {
            createInputAttributes(runEl, into);
        }
    }

    public static void main(String[] a) throws Exception {
        int failures = 0;

        // Çok paragraflı + listeli yapıştırma (Word benzeri); hepsi gerçek font taşımalı.
        String html =
              "<p style='font-family:\"Times New Roman\",serif'>Birinci paragraf.</p>"
            + "<p style='font-family:Arial,sans-serif'>İkinci paragraf.</p>"
            + "<ul><li>Madde bir</li><li>Madde iki</li></ul>"
            + "<p>Son paragraf.</p>";

        JTextPane pane = new JTextPane();
        Kit kit = new Kit();
        pane.setEditorKit(kit);
        StyledDocument doc = pane.getStyledDocument();

        // Mevcut belgeyi taklit et (Times gövde), caret sonda → buraya yapıştır.
        doc.insertString(0, "Mevcut belge metni.\n",
                ca("Times New Roman", 12));
        pane.setCaretPosition(doc.getLength());

        boolean ok = macospasterich.RichPaste.insertInto(pane, html);
        if (!ok) { System.out.println("HATA: insertInto false döndü"); System.exit(1); }

        // 1) HİÇBİR offset Monospaced'e çözülmemeli (her "\n" dahil gerçek font taşır).
        int monoOffsets = 0;
        for (int off = 0; off < doc.getLength(); off++) {
            Element el = doc.getCharacterElement(off);
            String fam = StyleConstants.getFontFamily(el.getAttributes());
            if ("Monospaced".equals(fam)) {
                monoOffsets++;
                if (monoOffsets <= 5) {
                    System.out.println("  Monospaced @ off=" + off
                            + " (char=" + escape(doc.getText(off, 1)) + ")");
                }
            }
        }
        if (monoOffsets > 0) {
            System.out.println("HATA: " + monoOffsets + " offset Monospaced'e düşüyor");
            failures++;
        } else {
            System.out.println("OK: hiçbir offset Monospaced değil");
        }

        // 2) Yapıştırma sonrası caret'te yazılacak metnin fontu gerçek olmalı
        //    (editör giriş öznitelikleri caret-1'deki run'dan türetilir).
        int caret = pane.getCaretPosition();
        Element runEl = doc.getCharacterElement(Math.max(caret - 1, 0));
        MutableAttributeSet input = new SimpleAttributeSet();
        kit.exposeInputAttrs(runEl, input);
        String typedFam = StyleConstants.getFontFamily(input);
        if ("Monospaced".equals(typedFam)) {
            System.out.println("HATA: yapıştırma sonrası yazım fontu Monospaced (caret=" + caret + ")");
            failures++;
        } else {
            System.out.println("OK: yapıştırma sonrası yazım fontu = '" + typedFam + "'");
        }

        if (failures == 0) {
            System.out.println("TÜM TESTLER GEÇTİ");
        } else {
            System.out.println(failures + " TEST BAŞARISIZ");
            System.exit(1);
        }
    }

    private static AttributeSet ca(String fam, int sz) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, fam);
        StyleConstants.setFontSize(a, sz);
        return a;
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
