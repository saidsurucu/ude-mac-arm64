import javassist.ClassPool;
import javassist.CtClass;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Build-time: editor-app.jar içindeki tr/com/havelsan/* sınıflarında
 * JFileChooser.show{Open,Save,}Dialog çağrılarını macosfiledialog.MacFileDialog'a
 * yönlendirir.
 *
 * Eşleştirme metot ADI + İMZAsına göre yapılır, MethodCall.getClassName()'e göre
 * DEĞİL: UDE çağrıyı bir com.alee WebFileChooser (JFileChooser alt sınıfı) örneği
 * üzerinden yaparsa getClassName() alt sınıf adını döner ve sınıf-bazlı eşleştirme
 * çağrıyı kaçırır. show{Open,Save}Dialog (Ljava/awt/Component;)I ve
 * showDialog (Ljava/awt/Component;Ljava/lang/String;)I imzaları JFileChooser'a
 * özgüdür; WebFileChooser de JFileChooser'a atanabilir olduğundan $0'ı
 * MacFileDialog.show*(JFileChooser,...) imzasına geçmek tip-güvenlidir.
 *
 * macosfiledialog.MacFileDialog bu patcher'dan ÖNCE jar'a enjekte edilmiş olmalı
 * (build.sh sırası bunu garanti eder), aksi halde Javassist köprü ifadesini
 * derleyemez (FopConfigPatch ile aynı kısıt).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class FileDialogPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: FileDialogPatch <editor-app.jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        int count = 0;
        try (ZipFile zf = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("tr/com/havelsan/") || !n.endsWith(".class")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                CtClass cc = pool.get(cn);
                final boolean[] changed = {false};
                try {
                    cc.instrument(new ExprEditor() {
                        public void edit(MethodCall m) throws javassist.CannotCompileException {
                            String name = m.getMethodName();
                            String sig = m.getSignature();
                            if (name.equals("showOpenDialog") && sig.equals("(Ljava/awt/Component;)I")) {
                                m.replace("{ $_ = macosfiledialog.MacFileDialog.showOpen((javax.swing.JFileChooser)$0, $1); }");
                                changed[0] = true;
                            } else if (name.equals("showSaveDialog") && sig.equals("(Ljava/awt/Component;)I")) {
                                m.replace("{ $_ = macosfiledialog.MacFileDialog.showSave((javax.swing.JFileChooser)$0, $1); }");
                                changed[0] = true;
                            } else if (name.equals("showDialog") && sig.equals("(Ljava/awt/Component;Ljava/lang/String;)I")) {
                                m.replace("{ $_ = macosfiledialog.MacFileDialog.showCustom((javax.swing.JFileChooser)$0, $1, $2); }");
                                changed[0] = true;
                            }
                        }
                    });
                    if (changed[0]) {
                        writeClass(cc, outDir);
                        count++;
                        System.out.println("[FileDialogPatch] yamalandı: " + cn);
                    }
                } finally {
                    cc.detach();
                }
            }
        }
        System.out.println("[FileDialogPatch] toplam " + count + " sınıf yamalandı.");
        if (count == 0) {
            System.err.println("[FileDialogPatch] UYARI: hiç JFileChooser.show* çağrısı bulunamadı (UDE sürümü değişmiş olabilir).");
            System.exit(3);
        }
    }

    private static void writeClass(CtClass cc, File outDir) throws Exception {
        byte[] code = cc.toBytecode();
        File f = new File(outDir, cc.getName().replace('.', '/') + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
    }
}
