import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * RichPaste.fromClipboardHtml birim testi (paketli ikiliye karsi).
 * UDE_UDFCLI env degiskeniyle ikiliyi gosterip javac+java ile calistir
 * (derleme: PrLog + RichPaste + bu dosya; calistirma: UDE_UDFCLI=&lt;ikili&gt;).
 */
public class RichPasteUdfTest {
    public static void main(String[] a) throws Exception {
        String html = "<p style='text-align:center'><b>Başlık</b></p>"
                + "<p>Normal <i>italik</i> ve <u>altı çizili</u>.</p>"
                + "<table><tr><td>A</td><td>B</td></tr></table>";
        byte[] udf = macospasterich.RichPaste.fromClipboardHtml(html);
        if (udf == null) throw new AssertionError("null döndü (UDE_UDFCLI ayarlı ve çalıştırılabilir mi?)");
        if (udf[0] != 'P' || udf[1] != 'K') throw new AssertionError("PK zip değil");
        boolean hasContent = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(udf))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if ("content.xml".equals(e.getName())) hasContent = true;
            }
        }
        if (!hasContent) throw new AssertionError("content.xml yok");
        System.out.println("PASS RichPasteUdfTest (" + udf.length + " bayt)");
    }
}
