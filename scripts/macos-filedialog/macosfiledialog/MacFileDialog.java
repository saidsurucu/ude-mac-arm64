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

    /** Format/uzantı zorlamada tanınan UDE uzantıları (sıra = probe önceliği). */
    private static final String[] KNOWN_EXTS = {"udf", "rtf", "pdf", "xml", "usf"};

    /** Ad sonundaki bilinen UDE uzantısını (case-insensitive) döndürür; yoksa null. */
    static String knownExtOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        for (String k : KNOWN_EXTS) if (k.equals(ext)) return ext;
        return null;
    }

    /**
     * Dosya adının uzantısını hedef uzantıya getirir: ad sonundaki bilinen UDE
     * uzantısını (varsa) söküp ".ext" ekler. Yalnız bilinen uzantı sökülür; ara
     * noktalar ve bilinmeyen uzantılar korunur. Çift uzantıyı (belge.udf.xml) önler.
     */
    static String forceExtension(String name, String ext) {
        if (name == null || ext == null || ext.isEmpty()) return name;
        String known = knownExtOf(name);
        String base = (known != null) ? name.substring(0, name.length() - known.length() - 1) : name;
        return base + "." + ext;
    }

    /**
     * Filtrenin uzantısını probe ile bulur: bilinen her uzantı için
     * ff.accept(new File("p."+ext)) dener, kabul edilen ilk uzantıyı döndürür.
     * Obfuscated/özel FileFilter alt sınıflarında da çalışır (yalnız accept()'e dayanır).
     * Dikkat: accept-all filtreler çağıran tarafça dışlanmalı; geçirilirse "udf" döner.
     * Birden çok uzantı kabul eden filtrede KNOWN_EXTS sırasındaki ilk eşleşme döner.
     */
    static String probeExtension(FileFilter ff) {
        if (ff == null) return null;
        for (String ext : KNOWN_EXTS) {
            if (ff.accept(new File("p." + ext))) return ext;
        }
        return null;
    }

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

    /**
     * Pencere başlığından açık belgenin dosya adını çıkarır; yoksa null.
     * Başlık biçimi: "Doküman Editörü vX (*) - isimsiz.UDF (/Users/.../isimsiz.UDF)".
     * Önce son parantez içindeki tam yolun taban adı denenir (en güvenilir);
     * yedek olarak " - " sonrası, " (" öncesi metin alınır.
     */
    private static String docNameFromTitle(Window owner) {
        try {
            String t = (owner instanceof Frame) ? ((Frame) owner).getTitle() : null;
            if (t == null) return null;
            t = t.trim();
            if (t.isEmpty()) return null;
            int close = t.lastIndexOf(')');
            int open = (close > 0) ? t.lastIndexOf('(', close) : -1;
            if (open >= 0 && close > open) {
                String inside = t.substring(open + 1, close).trim();
                if (inside.indexOf('/') >= 0 || inside.indexOf('\\') >= 0) {
                    int slash = Math.max(inside.lastIndexOf('/'), inside.lastIndexOf('\\'));
                    String base = inside.substring(slash + 1).trim();
                    if (!base.isEmpty()) return base;
                }
            }
            int dash = t.indexOf(" - ");
            if (dash >= 0) {
                String after = t.substring(dash + 3).trim();
                int paren = after.indexOf(" (");
                if (paren > 0) after = after.substring(0, paren).trim();
                if (!after.isEmpty()) return after;
            }
            return null;
        } catch (Throwable x) {
            return null;
        }
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
            if (sel != null && sel.getName() != null && !sel.getName().isEmpty()) {
                fd.setFile(sel.getName());
            } else if (mode == FileDialog.SAVE) {
                // "Farklı Kaydet"/"PDF olarak kaydet": UDE varsayılan isim vermez (sel=null).
                // Açık belgenin adını pencere başlığından al (ör. "isimsiz.UDF").
                // Başlık biçimi: "Doküman Editörü vX (*) - <ad> (<tam yol>)".
                String docName = docNameFromTitle(owner);
                if (docName != null && !docName.isEmpty()) fd.setFile(docName);
            }

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
