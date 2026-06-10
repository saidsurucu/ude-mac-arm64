import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.expr.Cast;
import javassist.expr.ExprEditor;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE panodan imaj yapıştırma macOS düzeltmesi (build-zamanı bytecode yaması).
 *
 * hj.paste() pano imajını "checkcast BufferedImage" ile alır; macOS JDK'sı
 * imageFlavor için MultiResolutionCachedImage döndürdüğünden cast CCE fırlatır
 * ve imaj yapıştırma sessizce ölür. Yama: paste() içindeki hedefi BufferedImage
 * olan her cast'i macospasteimage.Conv.toBuffered($1) ile değiştirir. Kontrol
 * akışı (metin önceliği, sayfaya sığdırma, dosya dalı) değişmez.
 *
 * ÖN KOŞUL: macospasteimage/Conv.class bu patcher çalışmadan ÖNCE jar'a
 * enjekte edilmiş olmalı (apply_pasteimage sırası; Javassist replace
 * derlemesi Conv'u jar classpath'inden çözer).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class PasteImagePatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: PasteImagePatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass hj = pool.get("tr.com.havelsan.uyap.system.editor.common.text.hj");
        CtMethod paste = hj.getDeclaredMethod("paste", new CtClass[0]);

        final int[] replaced = { 0 };
        paste.instrument(new ExprEditor() {
            public void edit(Cast c) throws CannotCompileException {
                try {
                    if ("java.awt.image.BufferedImage".equals(c.getType().getName())) {
                        c.replace("{ $_ = macospasteimage.Conv.toBuffered($1); }");
                        replaced[0]++;
                    }
                } catch (NotFoundException e) {
                    // cast hedef tipi havuzda çözülemedi -> BufferedImage değil, dokunma
                }
            }
        });

        if (replaced[0] == 0) {
            System.err.println("[PasteImagePatch] HATA: hj.paste() içinde BufferedImage cast'i bulunamadı (UDE sürümü değişmiş olabilir).");
            System.exit(1);
        }
        writeClass(hj, outDir);
        System.out.println("[PasteImagePatch] hj.paste() yamandı: " + replaced[0]
            + " BufferedImage cast'i Conv.toBuffered ile değiştirildi.");
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
