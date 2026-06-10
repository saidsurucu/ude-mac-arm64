package macosfiledialog;

import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

/** matchChoosableFilter için başsız (GUI açmayan) doğrulama. */
public class MacFileDialogFilterTest {
    static int failures = 0;

    static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("PASS: " + label);
        } else {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    public static void main(String[] args) {
        JFileChooser fc = new JFileChooser();
        FileNameExtensionFilter udf = new FileNameExtensionFilter("Uyap Dokuman [.udf]", "udf");
        FileNameExtensionFilter xml = new FileNameExtensionFilter("XML [.xml]", "xml");
        FileNameExtensionFilter usf = new FileNameExtensionFilter("Sablon [.usf]", "usf");
        FileNameExtensionFilter rtf = new FileNameExtensionFilter("RTF [.rtf]", "rtf");
        fc.addChoosableFileFilter(udf);
        fc.addChoosableFileFilter(xml);
        fc.addChoosableFileFilter(usf);
        fc.addChoosableFileFilter(rtf);
        fc.setFileFilter(udf); // o an seçili = udf (native panelde değişmez)

        check("rtf dosyasi rtf filtresine eslesir",
              MacFileDialog.matchChoosableFilter(fc, new File("/tmp/belge.rtf")) == rtf);
        check("udf dosyasi udf filtresine eslesir",
              MacFileDialog.matchChoosableFilter(fc, new File("/tmp/belge.udf")) == udf);
        check("xml dosyasi xml filtresine eslesir",
              MacFileDialog.matchChoosableFilter(fc, new File("/tmp/belge.xml")) == xml);
        check("bilinmeyen uzanti null doner",
              MacFileDialog.matchChoosableFilter(fc, new File("/tmp/belge.docx")) == null);
        check("null dosya null doner",
              MacFileDialog.matchChoosableFilter(fc, null) == null);

        check("forceExtension udf->xml strip",
              MacFileDialog.forceExtension("belge.udf", "xml").equals("belge.xml"));
        check("forceExtension uzantisiz ekler",
              MacFileDialog.forceExtension("belge", "udf").equals("belge.udf"));
        check("forceExtension ara nokta korunur",
              MacFileDialog.forceExtension("belge.2024.udf", "rtf").equals("belge.2024.rtf"));
        check("forceExtension buyuk-kucuk harf",
              MacFileDialog.forceExtension("Belge.UDF", "xml").equals("Belge.xml"));
        check("forceExtension ayni uzanti no-op",
              MacFileDialog.forceExtension("belge.xml", "xml").equals("belge.xml"));
        check("forceExtension bilinmeyen uzanti korunur",
              MacFileDialog.forceExtension("belge.docx", "udf").equals("belge.docx.udf"));
        check("forceExtension null ad null doner",
              MacFileDialog.forceExtension(null, "udf") == null);
        check("forceExtension null ext degismez",
              MacFileDialog.forceExtension("belge.udf", null).equals("belge.udf"));
        check("forceExtension bos ext degismez",
              MacFileDialog.forceExtension("belge.udf", "").equals("belge.udf"));
        check("knownExtOf null null",
              MacFileDialog.knownExtOf(null) == null);
        check("knownExtOf udf taniyor",
              "udf".equals(MacFileDialog.knownExtOf("belge.udf")));
        check("knownExtOf docx null",
              MacFileDialog.knownExtOf("belge.docx") == null);
        check("knownExtOf sonda nokta null",
              MacFileDialog.knownExtOf("belge.") == null);

        check("probeExtension rtf filtresi",
              "rtf".equals(MacFileDialog.probeExtension(rtf)));
        check("probeExtension udf filtresi",
              "udf".equals(MacFileDialog.probeExtension(udf)));
        check("probeExtension null filtre null",
              MacFileDialog.probeExtension(null) == null);
        FileNameExtensionFilter docx = new FileNameExtensionFilter("Word [.docx]", "docx");
        check("probeExtension bilinmeyen uzanti null",
              MacFileDialog.probeExtension(docx) == null);
        FileNameExtensionFilter multi = new FileNameExtensionFilter("UDF+RTF", "udf", "rtf");
        check("probeExtension cok-uzantili ilk esleseni doner",
              "udf".equals(MacFileDialog.probeExtension(multi)));

        check("friendlyLabel udf etiketi",
              "UDF Belgesi (.udf)".equals(MacFileDialog.friendlyLabel("udf")));
        check("friendlyLabel rtf etiketi",
              "Word / RTF (.rtf)".equals(MacFileDialog.friendlyLabel("rtf")));
        check("friendlyLabel pdf etiketi",
              "PDF (.pdf)".equals(MacFileDialog.friendlyLabel("pdf")));
        check("friendlyLabel xml etiketi",
              "XML (.xml)".equals(MacFileDialog.friendlyLabel("xml")));
        check("friendlyLabel usf etiketi",
              "USF (.usf)".equals(MacFileDialog.friendlyLabel("usf")));
        check("friendlyLabel buyuk-kucuk harf duyarsiz",
              "PDF (.pdf)".equals(MacFileDialog.friendlyLabel("PDF")));
        check("friendlyLabel bilinmeyen null",
              MacFileDialog.friendlyLabel("docx") == null);
        check("friendlyLabel null null",
              MacFileDialog.friendlyLabel(null) == null);

        check("isPlaceholderName isimsiz.UDF yakalar",
              MacFileDialog.isPlaceholderName("isimsiz.UDF"));
        check("isPlaceholderName kucuk harf isimsiz.udf yakalar",
              MacFileDialog.isPlaceholderName("isimsiz.udf"));
        check("isPlaceholderName uzantisiz isimsiz yakalar",
              MacFileDialog.isPlaceholderName("isimsiz"));
        check("isPlaceholderName gercek ad gecer",
              !MacFileDialog.isPlaceholderName("dilekce.udf"));
        check("isPlaceholderName isimsiz-on-ekli ad gecer",
              !MacFileDialog.isPlaceholderName("isimsiz-dilekce.udf"));
        check("isPlaceholderName null false",
              !MacFileDialog.isPlaceholderName(null));

        if (failures > 0) { System.out.println(failures + " test BASARISIZ"); System.exit(1); }
        System.out.println("Tum testler GECTI");
    }
}
