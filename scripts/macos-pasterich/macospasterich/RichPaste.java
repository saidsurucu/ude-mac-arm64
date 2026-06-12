package macospasterich;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import macospasterich.UdeDoc.Document;

/**
 * Harici stilli yapıştırma köprüsü (saf Java — harici bağımlılık / alt süreç YOK).
 * Panodaki HTML'i (Word/tarayıcı/PDF) UDE'nin kendi .udf (UDF zip: content.xml)
 * formatına çevirir. Boru hattı: Html tokenizer → HtmlToUde (model) → UdeXml
 * (content.xml) → zip. Dönen baytlar build-zamanı kancası (PasteRichPatch)
 * tarafından WPDocumentPanel.a(InputStream)'e beslenir → select-all/copy/paste.
 *
 * Tasarımı gereği SESSIZ BAŞARISIZLIK: hata/boş içerik → null; çağıran mevcut
 * düz-metin yoluna düşer (çökme yok).
 */
public final class RichPaste {

    /** Pano HTML'ini .udf (UDF zip) baytına çevirir; başarısızlıkta null. */
    public static byte[] fromClipboardHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        try {
            Document doc = HtmlToUde.convert(html);
            if (doc.body.isEmpty()) { PrLog.log("boş belge (içerik yok)"); return null; }
            String contentXml = UdeXml.serialize(doc);
            byte[] udf = zip(contentXml);
            PrLog.log("ok " + udf.length + " bayt, " + doc.body.size() + " blok");
            return udf;
        } catch (Throwable t) {
            PrLog.log("fromClipboardHtml", t);
            return null;
        }
    }

    /** content.xml dizesini tek girişli UDF zip'ine (.udf) paketler. */
    private static byte[] zip(String contentXml) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry e = new ZipEntry("content.xml");
            zos.putNextEntry(e);
            zos.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /** PasteRichPatch kancası catch bloğundan çağrılır. */
    public static void logExternal(Throwable t) {
        PrLog.log("inject", t);
    }

    /**
     * WPDocumentPanel.a():fi metodunu reflection ile çağırır. WPDocumentPanel'de
     * 'a()' adıyla İKİ no-arg metot vardır (obfuscate dönüş-tipi overload'ı):
     * biri int, biri fi döndürür. Javassist/javac kaynak düzeyinde dönüş tipiyle
     * ayırt edemez ("bad method") → bu yardımcı fi-döndüren olanı seçer. Dönen
     * Object, kancada tr...text.hj'ye cast edilir (fi extends hj).
     */
    public static Object docOf(Object panel) throws Exception {
        for (java.lang.reflect.Method m : panel.getClass().getMethods()) {
            if (m.getName().equals("a") && m.getParameterCount() == 0
                    && m.getReturnType().getName().endsWith(".text.fi")) {
                return m.invoke(panel);
            }
        }
        throw new NoSuchMethodException("WPDocumentPanel.a():fi bulunamadı");
    }

    private RichPaste() {
    }
}
