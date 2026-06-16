# Formatsız Yapıştır (PLAINPASTE=1) — Tasarım

Tarih: 2026-06-16
Durum: onaylandı (agy adversaryal incelemesi → needs-revision → 3 düzeltme katıldı)

## Amaç

Word'ün "Yalnızca Metni Koru" davranışının UDE'ye uyarlanmış hâli: panodaki
içeriği **karakter stili olmadan** yapıştır, ama **yapıyı koru**.

- **Atılır:** font ailesi, boyut, renk, kalın, italik, altı-çizili, vurgu
  (highlight). Metin imlecin bulunduğu yerin karakter stilini alır.
- **Korunur:** tablo, imaj, liste (madde/numara), paragraf hizalama ve girinti.

İki tetikleyici: **⌘⇧V** ve editör sağ tık menüsünde **"Formatsız Yapıştır"**.

## Bağlam / mevcut durum

- Normal yapıştırma (⌘V, sağ tık "Yapıştır") **zengin** yoldan gider:
  `JTextComponent.paste()` → UDE TransferHandler → PASTERICH (`hj.a(Transferable)`)
  → harici HTML/RTF için `RichPaste` → `NativeInsert` (tablo/imaj/liste/stil).
- Sağ tık menüsü editörden değil **Substance/lafwidget**'ten gelir:
  `org.jvnet.lafwidget.text.EditContextMenuWidget$1.handleMouseEvent` bir
  `JPopupMenu` kurar: Kes / Kopyala / Yapıştır / —— / Sil / Tümünü Seç. Bu sınıf
  **obfuscate DEĞİL** (isimle Javassist hedefi). Editör metin bileşenlerinin
  (`hj`/`fi`/`text.t`) kendi MouseListener'ı/popup'ı yoktur.
- Tüm karakter stili tek noktadan uygulanır:
  `NativeInsert.charAttrs(UdeDoc.TextStyle)` — hem paragraf metni (`insertParagraph`,
  ~L140) hem tablo hücreleri (`fillCell`, ~L221). Yapısal işlevler (`paraAttrs`,
  `insertTable`/`tableBuild`, `insertImage`) ayrıdır.

Bu yüzden "yapıyı koru, karakter stilini at" = PASTERICH boru hattını aynen
çalıştır, yalnız `charAttrs`'ı imlecin stilini döndürecek şekilde değiştir.

## Mimari

Tek implementasyon (`macospasterich`), iki çağıran (sağ tık yaması + ⌘⇧V agent).
`macospasterich` zaten PasteRichPatch ile editor-app.jar'a enjekte edildiğinden
yamalı LAF sınıfı ona doğrudan, agent ise reflection ile erişir (ikisi de System
ClassLoader → erişim sorunsuz; agy #3 ile doğrulandı).

### 1. Çekirdek — `macospasterich`

**`NativeInsert` düz-karakter modu.** `charAttrs(TextStyle)` çağrı sırasında bir
imleç-öznitelik kümesi etkinse, stil yerine onu döndürür.

- Yeni aşırı yükleme: `insert(Object editor, UdeDoc.Document model, AttributeSet cursorAttrs)`.
- `cursorAttrs != null` ise düz-karakter modu. EDT tek-iş-parçacıklı; mod tek bir
  `static AttributeSet CURSOR_ATTRS` ile taşınır, `insert()` içinde
  **`try { CURSOR_ATTRS = cursorAttrs; … } finally { CURSOR_ATTRS = null; }`**
  (agy #1: try/finally şart).
- `charAttrs(TextStyle s)`: `if (CURSOR_ATTRS != null) return CURSOR_ATTRS;` aksi
  hâlde mevcut davranış (stilden üret).
- `insertTable`/`fillCell`/`insertImage`/`paraAttrs` **değişmez** → tablo, imaj,
  liste, hizalama, girinti aynen korunur (agy #1 doğruladı).
- Mevcut iki-argümanlı `insert(editor, model)` zengin yol için aynen kalır
  (`insert(editor, model, null)`'a yönlendirilebilir).

**Yeni `macospasterich.PlainPaste`.**

`static boolean paste(javax.swing.text.JTextComponent editor)`:

1. Düzenlenebilir/etkin değilse `false`.
2. **İmleç stilini çöz (agy #2 — FontFamily fallback):**
   - Kit `StyledEditorKit` ise `getInputAttributes()` kopyası.
   - Çözülen kümede `StyleConstants.FontFamily` yoksa sırayla:
     `editor.getCharacterAttributes()` → yine yoksa `DEFAULT_BREAK` (TNR 12)
     bir alt katman olarak serilir (Swing'in "Monospaced"a düşmesi engellenir).
3. **Pano:**
   - HTML flavor varsa → `HtmlToUde` ile model → `NativeInsert.insert(editor, model, cursorAttrs)`.
   - RTF flavor varsa → `textutil -convert html` → HTML → aynı yol
     (`RichPaste.insertRtf` mantığı yeniden kullanılır).
   - Yalnız `stringFlavor` → seçim varsa `AbstractDocument.replace(start,len,text,cursorAttrs)`,
     yoksa `insertString(caret, text, cursorAttrs)`. `moveDot` YASAK (CLAUDE.md
     TextReplace/NPE dersi). `\n`'ler doğal paragraf kırması olur.
4. **Savunmacı (agy #4):** Belge `StyledDocument` değilse cast yapılmaz; düz
   `insertString` ile eklenir (doğrudan çağrı güvenli).
5. Teşhis: `UDE_PLAINPASTELOG=1` → `~/Library/Logs/ude-plainpaste.txt`.

Derleme: `apply_pasterich` zaten tüm `macospasterich/*.java` derleyip enjekte
ediyor → `PlainPaste.java` otomatik dahil.

### 2. Sağ tık — `PlainPastePatch.java` (Javassist, yeni `apply_plainpaste` adımı)

- Hedef: `org.jvnet.lafwidget.text.EditContextMenuWidget$1.handleMouseEvent`
  (obfuscate değil).
- `PasteAction` eklenişinden hemen sonra **insertAfter** ile bir
  `JMenuItem("Formatsız Yapıştır")` eklenir; eylemi `e.getComponent()`'i
  `javax.swing.text.JTextComponent`'e çevirip `macospasterich.PlainPaste.paste(...)`
  çağırır. Menü sırası: Kes / Kopyala / Yapıştır / **Formatsız Yapıştır** / —— /
  Sil / Tümünü Seç.
- İdempotans: `handleMouseEvent` gövdesi zaten `PlainPaste` referansı içeriyorsa
  yeniden yamalanmaz.
- Bayrak `PLAINPASTE=1` (varsayılan açık), PASTERICH'e bağımlı.

### 3. Klavye ⌘⇧V — `MacShortcutRemap`

- Yeni `Fb.PLAIN_PASTE`.
- Yeni eşleme: `Map(KeyEvent.VK_V, META | SHIFT, null, 0, 0, Fb.PLAIN_PASTE)`
  (⌘⇧V şu an boşta; R/J/G/D/B ⌘⇧ kullanılıyor).
- `perform()` switch'inde `case PLAIN_PASTE`: odaktaki `JTextComponent`'e
  `Class.forName("macospasterich.PlainPaste").getMethod("paste", JTextComponent.class)`
  ile reflection çağrısı (agent app-cp'siz derlenir; agy #3 ile classloader OK).
- **`performLocal()`'e (agy #4):** `case PLAIN_PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;`
  → editör-dışı alanlar (şerit arama kutusu = `PlainDocument`) normal yapıştırmaya
  gider, `StyledDocument` cast'ı denenmez (ClassCastException önlenir).

## agy adversaryal incelemesi (özet)

- #1 charAttrs + tablo hücreleri: **OK** (try/finally guard önerisi katıldı).
- #2 FontFamily fallback: **KUSUR** → `getCharacterAttributes()` → `DEFAULT_BREAK`
  katmanı eklendi.
- #3 reflection/classloader: **OK** (`Class.forName`).
- #4 editör-dışı ⌘⇧V cast hatası: **KUSUR** → `performLocal` `tc.paste()` + PlainPaste
  savunmacı cast.
- #5 daha basit alternatif: **yok** (düz metin okumak tablo/imaj/listeyi yok eder).

## Sınırlar / riskler

- PLAINPASTE, PASTERICH'e bağlı (tablo/imaj rich boru hattından gelir); ikisi de
  varsayılan açık.
- İmleç stili `StyledEditorKit` türevine dayanır; değilse `getCharacterAttributes()`
  / TNR 12 fallback.
- Pano yalnız düz metin içeriyorsa (örn. Pages metni) zaten o metin gelir — beklenen.
- GUI doğrulaması elle (kullanıcı tercihi).

## Test

- `tests/PlainPasteStripTest.java` (javac+java elle): bir model + cursorAttrs ile
  `NativeInsert` düz-karakter modu → headless `DefaultStyledDocument`'te tablo/liste
  korunur, karakter stili cursorAttrs'a eşit doğrulanır.
- Canlı GUI doğrulaması kullanıcıda (⌘⇧V + sağ tık ile Word/Docs tablolu+stilli içerik).
