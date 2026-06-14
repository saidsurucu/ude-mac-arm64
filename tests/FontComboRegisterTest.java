import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.SimpleAttributeSet;

/**
 * Regresyon testi: macOS'ta YÜKLÜ OLMAYAN bir fontu (Calibri) Word'den yapıştırınca,
 * şerit font kutusu o adı SEÇEBİLSİN diye ad kutu modeline eklenmeli (NativeInsert.
 * ensureFontsSelectable). Kutu modeli alfabetik ve "Times New Roman" 100. indeksin
 * ötesinde → kutu bulma TÜM modeli taramalı (ilk-N kısıtı kutuyu kaçırıyordu).
 *
 * Derle + çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/FontComboRegisterTest.java
 *   java -cp OUT FontComboRegisterTest
 */
public class FontComboRegisterTest {
    public static void main(String[] a) throws Exception {
        // Editör + font kutusu aynı pencerede (getWindowAncestor için gerçekleşmiş hiyerarşi).
        JFrame frame = new JFrame();
        JPanel root = new JPanel();
        JTextPane pane = new JTextPane();
        JComboBox<String> fontCombo = new JComboBox<>();
        // Alfabetik font listesi; "Times New Roman"ı 100. indeksin ÖTESİNE koy (T harfi).
        for (int i = 0; i < 120; i++) fontCombo.addItem(String.format("Aaa%03dFont", i));
        fontCombo.addItem("Arial");
        fontCombo.addItem("Times New Roman");   // imza; index > 100
        root.add(pane);
        root.add(fontCombo);
        frame.getContentPane().add(root);
        frame.pack();   // gerçekleştir (gösterme gerekmez)

        StyledDocument doc = pane.getStyledDocument();
        doc.insertString(0, "Mevcut.\n", ca("Times New Roman", 12));
        pane.setCaretPosition(doc.getLength());

        boolean before = contains(fontCombo, "Calibri");
        boolean ok = macospasterich.RichPaste.insertInto(pane,
                "<p style='font-family:\"Calibri\",sans-serif'>Calibri metni.</p>");
        if (!ok) { System.out.println("HATA: insertInto false"); System.exit(1); }

        boolean after = contains(fontCombo, "Calibri");
        System.out.println("Calibri kutuda: önce=" + before + " sonra=" + after);

        int failures = 0;
        if (before) { System.out.println("HATA: test kurulumu — Calibri zaten kutuda"); failures++; }
        if (!after) { System.out.println("HATA: Calibri kutu modeline EKLENMEDİ (menü fontu gösteremez)"); failures++; }

        // Yüklü/var olan fontlar çift eklenmemeli (Arial zaten vardı).
        if (count(fontCombo, "Arial") != 1) { System.out.println("HATA: Arial çift eklendi"); failures++; }

        if (failures == 0) System.out.println("TÜM TESTLER GEÇTİ");
        else { System.out.println(failures + " TEST BAŞARISIZ"); System.exit(1); }
        System.exit(0);   // Swing thread'leri kapansın
    }

    private static javax.swing.text.AttributeSet ca(String fam, int sz) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, fam);
        StyleConstants.setFontSize(a, sz);
        return a;
    }

    private static boolean contains(JComboBox<String> cb, String v) { return count(cb, v) > 0; }

    private static int count(JComboBox<String> cb, String v) {
        int n = 0;
        for (int i = 0; i < cb.getItemCount(); i++) if (v.equals(cb.getItemAt(i))) n++;
        return n;
    }
}
