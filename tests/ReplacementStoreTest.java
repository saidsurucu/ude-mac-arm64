package macostextkeys;

import java.io.File;
import java.util.Map;

/*
 * ReplacementStore SQLite okuma testi: gerçek şemayla geçici db kurulur
 * (sqlite3 CLI), çok satırlı/Türkçe karakterli/silinmiş kayıtlar doğrulanır.
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/trs-test \
 *     scripts/macos-textkeys/macostextkeys/TrLog.java \
 *     scripts/macos-textkeys/macostextkeys/ReplacementStore.java \
 *     tests/ReplacementStoreTest.java
 *   java -cp /tmp/trs-test macostextkeys.ReplacementStoreTest
 */
public final class ReplacementStoreTest {

    public static void main(String[] args) throws Exception {
        File db = File.createTempFile("ude-trs", ".db");
        db.delete();
        String sql = "CREATE TABLE ZTEXTREPLACEMENTENTRY (Z_PK INTEGER PRIMARY KEY,"
                + " ZWASDELETED INTEGER, ZPHRASE VARCHAR, ZSHORTCUT VARCHAR);"
                + " INSERT INTO ZTEXTREPLACEMENTENTRY (ZWASDELETED, ZPHRASE, ZSHORTCUT) VALUES"
                + " (0, 'Merhaba!', 'mrb'),"
                + " (0, 'Satır 1' || char(10) || 'Satır 2', 'adr'),"
                + " (0, 'Şişli/İstanbul ğüöç', 'işadr'),"
                + " (1, 'silinmiş', 'sil');";
        Process p = new ProcessBuilder("/usr/bin/sqlite3", db.getAbsolutePath(), sql).start();
        if (p.waitFor() != 0) throw new AssertionError("fixture db kurulamadı");

        Map<String, String> m = ReplacementStore.readFromDb(db);
        db.delete();

        int fails = 0;
        if (m == null) { System.out.println("FAIL: null döndü"); System.exit(1); }
        if (m.size() != 3) { fails++; System.out.println("FAIL boyut: " + m); }
        if (!"Merhaba!".equals(m.get("mrb"))) { fails++; System.out.println("FAIL mrb: " + m.get("mrb")); }
        if (!"Satır 1\nSatır 2".equals(m.get("adr"))) { fails++; System.out.println("FAIL çok satır: " + m.get("adr")); }
        if (!"Şişli/İstanbul ğüöç".equals(m.get("işadr"))) { fails++; System.out.println("FAIL türkçe: " + m.get("işadr")); }
        if (m.containsKey("sil")) { fails++; System.out.println("FAIL silinmiş kayıt geldi"); }

        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }
}
