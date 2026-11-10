import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE satır-içi imaj tam-çözünürlük (IMGFULL=1) build-zamanı bytecode yaması:
 * tr…editor.utils.h.a(hj, BufferedImage) gövdesi "return $2;" olur → ekleme
 * anındaki yıkıcı ~600x790 küçültme kaldırılır, imaj tam çözünürlükte (kayıpsız
 * PNG) gömülür.
 *
 * NOT: eskiden burada wp.b.at.drawImage'a BICUBIC ipucu da konuyordu. O çağrı
 * satır-içi imaj DEĞİL sayfa ARKA PLAN resmiymiş; her boyamada yazılım bicubic'i
 * antetli belgeleri ~100 ms/boyamaya düşürüyordu → kaldırıldı, yerini BGIMG
 * (scripts/macos-bgimage) önbellekli çizimi aldı.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class ImageInsertPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: ImageInsertPatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        // h.a(hj, BufferedImage): yıkıcı küçültmeyi kaldır
        CtClass h = pool.get("tr.com.havelsan.uyap.system.editor.utils.h");
        CtMethod fit = h.getMethod(
            "a",
            "(Ltr/com/havelsan/uyap/system/editor/common/text/hj;Ljava/awt/image/BufferedImage;)Ljava/awt/image/BufferedImage;");
        fit.setBody("{ return $2; }");   // static → $2 = BufferedImage parametresi
        writeClass(h, outDir);
        System.out.println("[ImageInsertPatch] h.a(hj,BufferedImage) tam-çözünürlük yaması uygulandı (return $2).");
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
