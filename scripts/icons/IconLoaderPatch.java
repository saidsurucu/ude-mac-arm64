import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE'nin merkezi ikon yükleyicisi
 *   tr.com.havelsan.uyap.system.editor.common.Utils.b(java.lang.String):javax.swing.ImageIcon
 * (tam resource yolu alır, Utils.class.getResource + new ImageIcon ile yükler)
 * metoduna insertAfter ile köprü ekler: yüklenen ".png" için "@2x.png" eşi varsa,
 * ikiyi java.awt.image.BaseMultiResolutionImage'a sarıp ImageIcon olarak döndürür.
 * Böylece ikon mantıksal boyutta çizilir ama Retina'da @2x pikselle KESKİN olur.
 * @2x yoksa orijinal ImageIcon aynen döner (diğer ikonlar etkilenmez).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class IconLoaderPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: IconLoaderPatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass utils = pool.get("tr.com.havelsan.uyap.system.editor.common.Utils");
        CtMethod b = utils.getMethod("b", "(Ljava/lang/String;)Ljavax/swing/ImageIcon;");
        b.insertAfter(
            "{ if ($_ != null && $1 != null && $1.endsWith(\".png\")"
          + "     && !($_.getImage() instanceof java.awt.image.BaseMultiResolutionImage)) {"
          + "   String __h = $1.substring(0, $1.length() - 4) + \"@2x.png\";"
          + "   java.net.URL __u = tr.com.havelsan.uyap.system.editor.common.Utils.class.getResource(__h);"
          + "   if (__u != null) {"
          + "     javax.swing.ImageIcon __hi = new javax.swing.ImageIcon(__u);"
          + "     java.awt.Image __m = new java.awt.image.BaseMultiResolutionImage("
          + "         new java.awt.Image[]{ $_.getImage(), __hi.getImage() });"
          + "     $_ = new javax.swing.ImageIcon(__m);"
          + "   }"
          + " } }");

        byte[] code = utils.toBytecode();
        File f = new File(outDir, "tr/com/havelsan/uyap/system/editor/common/Utils.class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
        System.out.println("[IconLoaderPatch] Utils.b multi-resolution yaması yazıldı.");
    }
}
