import javassist.ClassPool;
import javassist.CtClass;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE'nin FOP sürücüsü
 *   tr.com.havelsan.uyap.system.editor.b.a
 * içinde FOP yapılandırması (font kaydı) HİÇ yapılmadan FopFactory kurulur:
 *   FopFactory.newInstance()  →  newFop(mime, out)  →  getDefaultHandler()
 * Bu yüzden FOP base-14 (Cp1252) fontlarına düşer ve Türkçe ğ/ş/ı/İ harfleri
 * PDF'te kaybolur.
 *
 * Bu yama, FopFactory.newInstance() çağrısından HEMEN SONRA üretilen factory'ye
 *   macosfop.FopFonts.apply(<factory>)
 * köprüsünü ekler. apply(...), macOS Arial/Times fontlarını gömen bir kullanıcı
 * yapılandırmasını setUserConfig ile uygular (ayrıntı: macosfop.FopFonts).
 *
 * Sınıfın tüm metotları taranır; newInstance çağrısı nerede olursa olsun yakalanır.
 * macosfop.FopFonts sınıfı bu yamadan ÖNCE jar'a eklenmiş olmalıdır (build.sh
 * sırası bunu garanti eder), aksi halde Javassist köprü ifadesini derleyemez.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class FopConfigPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: FopConfigPatch <editor-app.jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass drv = pool.get("tr.com.havelsan.uyap.system.editor.b.a");

        final boolean[] hit = {false};
        drv.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall mc) throws javassist.CannotCompileException {
                if ("org.apache.fop.apps.FopFactory".equals(mc.getClassName())
                        && "newInstance".equals(mc.getMethodName())) {
                    // $_ = yeni FopFactory; üretildikten sonra font config'i uygula.
                    mc.replace("{ $_ = $proceed($$); macosfop.FopFonts.apply($_); }");
                    hit[0] = true;
                }
            }
        });

        if (!hit[0]) {
            throw new IllegalStateException(
                "FopFactory.newInstance() çağrısı bulunamadı — UDE sürümü değişmiş olabilir.");
        }

        byte[] code = drv.toBytecode();
        File f = new File(outDir, "tr/com/havelsan/uyap/system/editor/b/a.class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
        System.out.println("[FopConfigPatch] b/a: newInstance → FopFonts.apply köprüsü yazıldı.");
    }
}
