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

        if (failures > 0) { System.out.println(failures + " test BASARISIZ"); System.exit(1); }
        System.out.println("Tum testler GECTI");
    }
}
