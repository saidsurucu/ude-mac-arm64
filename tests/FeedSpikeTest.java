import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;

/**
 * Uçtan uca besleme spike'ı: saf-Java dönüştürücü (RichPaste) → .udf →
 * UDE'nin GERÇEK UDF okuyucusu (WPDocumentPanel.a(InputStream)) → DocumentEx.
 * Headless çalışır; gerçek editor-app.jar classpath'te olmalı.
 *
 * Derle + çalıştır (JAR = build/_input/editor-app.jar; OUT = derlenmiş macospasterich):
 *   javac --release 11 -cp "OUT:JAR" -d OUT tests/FeedSpikeTest.java
 *   java -Djava.awt.headless=true -cp "OUT:JAR" FeedSpikeTest
 *
 * SPIKE-OK = besleme yolu çalışıyor (getLength>0, metin doğru).
 */
public class FeedSpikeTest {
    public static void main(String[] a) throws Exception {
        String html = "<p style='text-align:center'><b>BAŞLIK</b></p>"
                + "<p>Normal <i>italik</i> <span style='color:#0000FF'>mavi</span>.</p>"
                + "<table><tr><th>Sıra</th><th>Açıklama</th></tr><tr><td>1</td><td>Arsa</td></tr></table>"
                + "<ul><li>Bir</li><li>İki</li></ul>";

        byte[] udf = macospasterich.RichPaste.fromClipboardHtml(html);
        if (udf == null) throw new AssertionError("dönüştürücü null döndü");

        Class<?> wpc = Class.forName("tr.com.havelsan.uyap.system.editor.common.gui.WPDocumentPanel");
        Object wp = wpc.getDeclaredConstructor().newInstance();
        Method aStream = wpc.getMethod("a", java.io.InputStream.class);
        aStream.invoke(wp, new ByteArrayInputStream(udf));

        Object fi = macospasterich.RichPaste.docOf(wp);
        Object doc = fi.getClass().getMethod("getDocument").invoke(fi);
        int len = (int) doc.getClass().getMethod("getLength").invoke(doc);
        String txt = (String) doc.getClass().getMethod("getText", int.class, int.class)
                .invoke(doc, 0, Math.min(len, 200));

        System.out.println(".udf=" + udf.length + " bayt, DocumentEx.getLength=" + len);
        System.out.println("metin: " + txt.replace("\n", "\\n"));
        if (len <= 0) throw new AssertionError("DocumentEx boş — besleme başarısız");
        if (!txt.contains("BAŞLIK") || !txt.contains("Arsa")) throw new AssertionError("beklenen metin yok");
        System.out.println("SPIKE-OK");
    }
}
