package macosantet;

import java.io.File;
import java.io.FileOutputStream;

/*
 * Depo işlemleri testi: geçici dizin `macosantet.dir` system property ile
 * verilir (gerçek Application Support'a dokunulmaz).
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/antet-test \
 *     scripts/antet/macosantet/AntetLog.java \
 *     scripts/antet/macosantet/AntetStore.java \
 *     tests/AntetStoreTest.java
 *   java -cp /tmp/antet-test macosantet.AntetStoreTest
 */
public final class AntetStoreTest {

    private static int fails = 0;

    private static void fail(String msg) { fails++; System.out.println("FAIL " + msg); }

    private static File temp(String name, byte[] data) throws Exception {
        File f = new File(System.getProperty("java.io.tmpdir"), name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        f.deleteOnExit();
        return f;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
            "antet-store-test-" + System.nanoTime());
        System.setProperty("macosantet.dir", dir.getAbsolutePath());

        if (AntetStore.list().length != 0) fail("bos klasor: bos liste beklenir");

        if (!AntetStore.accepts("a.PNG") || !AntetStore.accepts("b.jpg")
                || !AntetStore.accepts("c.JPEG")) fail("png/jpg/jpeg kabul edilmeli");
        if (AntetStore.accepts("d.txt") || AntetStore.accepts("e.pdf"))
            fail("txt/pdf reddedilmeli");

        File src = temp("büro antet.png", new byte[] { 1, 2, 3 });
        File dest = AntetStore.add(src);
        if (!dest.isFile() || dest.length() != 3) fail("kopya olusmadi");
        if (!dest.getParentFile().equals(dir)) fail("yanlis klasore kopyalandi");

        File src2 = temp("büro antet.png", new byte[] { 9, 9, 9, 9 });
        AntetStore.add(src2);
        if (AntetStore.list().length != 1) fail("ayni ad: tek kayit kalmali");
        if (dest.length() != 4) fail("ayni ad: uzerine yazilmali");

        new FileOutputStream(new File(dir, "not.txt")).close();
        File z = temp("z-ikinci.jpg", new byte[] { 5 });
        AntetStore.add(z);
        File[] list = AntetStore.list();
        if (list.length != 2) fail("uzanti filtresi: 2 kayit beklenir, " + list.length);
        if (!"büro antet".equals(AntetStore.displayName(list[0])))
            fail("siralama/ad: " + list[0].getName());
        if (!"z-ikinci".equals(AntetStore.displayName(list[1])))
            fail("siralama/ad 2: " + list[1].getName());

        if (!AntetStore.delete(list[1])) fail("silme basarisiz");
        if (AntetStore.list().length != 1) fail("silme sonrasi 1 kayit beklenir");

        if (fails > 0) { System.out.println(fails + " hata"); System.exit(1); }
        System.out.println("OK AntetStoreTest");
    }
}
