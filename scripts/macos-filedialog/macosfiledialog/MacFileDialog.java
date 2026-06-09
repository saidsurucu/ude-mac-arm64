package macosfiledialog;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.PrintStream;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

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
                // Filtre koruma: glob asıl yol, FilenameFilter yedek.
                final FileFilter ff = fc.getFileFilter();
                boolean acceptAll = (ff == null) || ff.equals(fc.getAcceptAllFileFilter());
                if (!acceptAll) {
                    if (mode == FileDialog.LOAD && sel == null && ff instanceof FileNameExtensionFilter) {
                        String[] exts = ((FileNameExtensionFilter) ff).getExtensions();
                        if (exts != null && exts.length > 0) fd.setFile("*." + exts[0]);
                    }
                    fd.setFilenameFilter(new FilenameFilter() {
                        public boolean accept(File dd, String n) { return ff.accept(new File(dd, n)); }
                    });
                }

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
            if (fc.isMultiSelectionEnabled() && multi != null && multi.length > 0) {
                fc.setSelectedFiles(multi);
                fc.setSelectedFile(multi[0]);
            } else {
                fc.setSelectedFile(new File(d, name));
            }
            log("seçildi: " + d + name);
            return JFileChooser.APPROVE_OPTION;
        } catch (Throwable t) {
            log("hata: " + t);
            return JFileChooser.CANCEL_OPTION;
        }
    }
}
