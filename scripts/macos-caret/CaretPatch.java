import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE imleç çizim düzeltmesi (CARETFIX=1) build-zamanı bytecode yaması.
 *
 * tr…editor.common.s.a(Graphics, Rectangle) — imleci çizen metot (editör
 * imleci text.l bunu MİRAS alır, override etmez). Orijinal gövde:
 *     g.fillRect(r.x + 1, r.y + 2, 2, r.height - 3);   // 2px geniş, +1px kaydık
 * macOS fractional render'ında bu imleci harf gövdesine bindiriyordu.
 * Yeni gövde temiz 1px imleç, kaydırma yok, harfler-arası boşlukta:
 *     g.fillRect(r.x, r.y + 2, 1, r.height - 3);
 *
 * Renk paint() içinde g.setColor(getCaretColor()) ile zaten ayarlı.
 * Silme/damage bölgesi a(Rectangle): x=r.x-4, width=10 → yeni imleç kapsanır,
 * hayalet piksel olmaz.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class CaretPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: CaretPatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass s = pool.get("tr.com.havelsan.uyap.system.editor.common.s");
        CtMethod draw = s.getMethod("a", "(Ljava/awt/Graphics;Ljava/awt/Rectangle;)V");
        // $1 = Graphics, $2 = Rectangle (instance metot)
        draw.setBody("{ $1.fillRect($2.x, $2.y + 2, 1, $2.height - 3); }");
        writeClass(s, outDir);
        System.out.println("[CaretPatch] common.s.a(Graphics,Rectangle) temiz 1px imlec yamasi uygulandi.");

        // --- Zoom imleç kayması düzeltmesi (Faz 2) ---
        // Bölüm görünümü wp.prof.d.O device<->logical köprüsü (p.a/p.c) kullanır.
        // O.paint ve O.modelToView(5-arg, seçim) girişi p.c ile device->logical
        // çevirir; ama O.modelToView(3-arg, İMLEÇ) bu p.c'yi ATLAR → caret_x =
        // (int)((D+L)*s)-1, paint_x ~= D+s*L → kayma = (s-1)*D-1 (zoom'la büyür).
        // Düzeltme: 3-arg modelToView'a (ve eşgüdümlü viewToModel'e — yoksa tıklama
        // imlece oturmaz) p.c giriş çevirimini ekle, 5-arg/paint kardeşini yansıtarak.
        // p.a/p.c verilen Rectangle'ı YERİNDE değiştirir → getBounds() klon verir,
        // çağıranın şekli korunur.
        CtClass o = pool.get("tr.com.havelsan.uyap.system.swing.wp.prof.d.O");
        CtMethod m2v = o.getMethod("modelToView",
            "(ILjava/awt/Shape;Ljavax/swing/text/Position$Bias;)Ljava/awt/Shape;");
        m2v.insertBefore(
            "{ if ($2 != null) { java.awt.Rectangle _r = $2.getBounds();"
          + " _r = tr.com.havelsan.uyap.system.swing.wp.textUtils.p.c(_r, this.a());"
          + " $2 = _r; } }");
        CtMethod v2m = o.getMethod("viewToModel",
            "(FFLjava/awt/Shape;[Ljavax/swing/text/Position$Bias;)I");
        v2m.insertBefore(
            "{ if ($3 != null) { java.awt.Rectangle _r = $3.getBounds();"
          + " _r = tr.com.havelsan.uyap.system.swing.wp.textUtils.p.c(_r, this.a());"
          + " $3 = _r; } }");
        writeClass(o, outDir);
        System.out.println("[CaretPatch] wp.prof.d.O modelToView/viewToModel p.c cevirimi eklendi (zoom kaymasi).");
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
