package macosfiledialog;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

/**
 * macOS native dosya pencereleri köprüsü. UDE'nin JFileChooser.show* çağrıları
 * build-time (FileDialogPatch) buraya yönlendirilir; burada java.awt.FileDialog
 * (NSOpenPanel/NSSavePanel) açılır ve sonuç JFileChooser sözleşmesine uygun
 * (fc.getSelectedFile()/getSelectedFiles()) şekilde geri yazılır.
 *
 * Loglama: System.err uygulama logger'ına yutulur; -Dmacosfiledialog.debug=1
 * ile /tmp/macos-filedialog.log dosyasına yazılır.
 */
public final class MacFileDialog {
    private MacFileDialog() {}

    private static void log(String m) {
        if (!"1".equals(System.getProperty("macosfiledialog.debug"))) return;
        try (PrintStream p = new PrintStream(new FileOutputStream("/tmp/macos-filedialog.log", true), true, "UTF-8")) {
            p.println(m);
        } catch (Throwable ignore) {}
    }

    public static int showOpen(JFileChooser fc, Component parent) {
        return show(fc, parent, FileDialog.LOAD);
    }

    public static int showSave(JFileChooser fc, Component parent) {
        return show(fc, parent, FileDialog.SAVE);
    }

    public static int showCustom(JFileChooser fc, Component parent, String approveText) {
        // approveText: java.awt.FileDialog özel buton metnini desteklemez (NSOpenPanel/NSSavePanel) — yok sayılır.
        int mode = (fc.getDialogType() == JFileChooser.SAVE_DIALOG) ? FileDialog.SAVE : FileDialog.LOAD;
        return show(fc, parent, mode);
    }

    /**
     * Seçilen dosyayı kabul eden ilk accept-all-olmayan choosable filtreyi döndürür;
     * yoksa null. Native panelde filtre açılır listesi olmadığından, seçimden sonra
     * UDE'nin fc.getFileFilter()'ının doğru ayrıştırıcıya işaret etmesini sağlamak için.
     */
    static FileFilter matchChoosableFilter(JFileChooser fc, File f) {
        if (fc == null || f == null) return null;
        FileFilter acceptAll = fc.getAcceptAllFileFilter();
        FileFilter[] all = fc.getChoosableFileFilters();
        if (all == null) return null;
        for (FileFilter ff : all) {
            if (ff == null || ff.equals(acceptAll)) continue;
            if (ff.accept(f)) return ff;
        }
        return null;
    }

    private static int show(JFileChooser fc, Component parent, int mode) {
        try {
            Window owner = (parent instanceof Window) ? (Window) parent
                          : (parent != null ? SwingUtilities.getWindowAncestor(parent) : null);

            String title = fc.getDialogTitle();
            if (title == null || title.isEmpty()) title = (mode == FileDialog.SAVE) ? "Kaydet" : "Aç";

            FileDialog fd;
            if (owner instanceof Frame)       fd = new FileDialog((Frame) owner, title, mode);
            else if (owner instanceof Dialog) fd = new FileDialog((Dialog) owner, title, mode);
            else                              fd = new FileDialog((Frame) null, title, mode);

            File dir = fc.getCurrentDirectory();
            if (dir != null) fd.setDirectory(dir.getAbsolutePath());

            File sel = fc.getSelectedFile();
            if (sel != null) fd.setFile(sel.getName());

            if (fc.isMultiSelectionEnabled()) fd.setMultipleMode(true);

            // Dizin seçimi (savunmacı; UDE'de görülmedi)
            String prevDirProp = null;
            boolean dirMode = fc.getFileSelectionMode() == JFileChooser.DIRECTORIES_ONLY;
            if (dirMode) {
                prevDirProp = System.getProperty("apple.awt.fileDialogForDirectories");
                System.setProperty("apple.awt.fileDialogForDirectories", "true");
            }

            String name;
            String d;
            File[] multi;
            try {
                // Katı dosya filtresi UYGULANMAZ: macOS NSOpenPanel filtreyi onurlandırdığında
                // .udf dışı dosyalar (rtf/xml/usf) gizleniyordu; NSSavePanel'de FilenameFilter
                // isim alanını bozuyordu. Format/uzantı mantığı UDE'nin kendi JFileChooser
                // durumunda kalır; LOAD'da seçim sonrası eşleşen filtre ayarlanır (aşağıda).
                fd.setVisible(true);
            } finally {
                if (dirMode) {
                    if (prevDirProp == null) System.clearProperty("apple.awt.fileDialogForDirectories");
                    else System.setProperty("apple.awt.fileDialogForDirectories", prevDirProp);
                }
            }

            name = fd.getFile();
            if (name == null) {
                log("iptal (mode=" + mode + ")");
                return JFileChooser.CANCEL_OPTION;
            }
            d = fd.getDirectory();
            multi = fd.getFiles();
            File chosen;
            if (fc.isMultiSelectionEnabled() && multi != null && multi.length > 0) {
                fc.setSelectedFiles(multi);
                fc.setSelectedFile(multi[0]);
                chosen = multi[0];
            } else {
                chosen = new File(d, name);
                fc.setSelectedFile(chosen);
            }
            if (d != null) fc.setCurrentDirectory(new File(d));
            if (mode == FileDialog.LOAD) {
                FileFilter mf = matchChoosableFilter(fc, chosen);
                if (mf != null) fc.setFileFilter(mf);
            }
            log("seçildi: " + d + name);
            return JFileChooser.APPROVE_OPTION;
        } catch (Throwable t) {
            log("hata: " + t);
            return JFileChooser.CANCEL_OPTION;
        }
    }
}
