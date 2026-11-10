import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Sayfa arka plan resmi (antet) hızlı çizim yaması — BGIMG=1.
 *
 * tr…swing.wp.b.at sayfa zeminini boyar; içindeki TEK
 * drawImage(Image,x,y,w,h,ImageObserver) çağrısı belgenin arka plan resmidir
 * (satır-içi imajlar buradan GEÇMEZ). Çağrı macosbgimage.BgPaint.draw(...)'a
 * yönlendirilir: ölçekli kopya bir kez üretilip önbellekten çizilir.
 *
 * Kök neden + ölçümler BgPaint sınıf yorumunda.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class BgImagePatch {
    static final String AT = "tr.com.havelsan.uyap.system.swing.wp.b.at";
    static final String HELPER = "macosbgimage.BgPaint";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: BgImagePatch <editor-app.jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass at = pool.get(AT);
        if (at.getRefClasses().contains(HELPER)) {
            System.out.println("[BgImagePatch] zaten yamalı (BgPaint atfı var); atlandı.");
            return;
        }

        final int[] sites = { 0 };
        at.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getMethodName().equals("drawImage")
                        && m.getSignature().equals("(Ljava/awt/Image;IIIILjava/awt/image/ImageObserver;)Z")) {
                    m.replace("{ $_ = " + HELPER + ".draw($0, $$); }");
                    sites[0]++;
                }
            }
        });
        if (sites[0] == 0) {
            System.err.println("[BgImagePatch] at içinde beklenen drawImage çağrısı bulunamadı.");
            System.exit(1);
        }

        byte[] code = at.toBytecode();
        File f = new File(outDir, at.getName().replace('.', '/') + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
        System.out.println("[BgImagePatch] at arka plan çizimi BgPaint'e yönlendirildi (" + sites[0] + " çağrı yeri).");
    }
}
