# Formatsız yapıştırma varsayılan — tasarım (2026-07-01)

## Amaç

Harici kaynaklardan (Word, tarayıcı, PDF, Pages…) yapıştırmada VARSAYILAN davranış
formatsız olsun; formatlı yapıştırma isteğe bağlı hâle gelsin. Kısayollar yer
değiştirir: **⌘V = formatsız (akıllı)**, **⌘⇧V = formatlı**. UDE-İÇİ kopyalar
(EditorDataFlavor) ⌘V'de FORMATLI kalır (kullanıcı kararı: belge içinde metin
taşırken biçim kaybolmasın).

## Davranış tablosu

| Tetikleyici | UDE-içi kopya | Harici içerik |
|---|---|---|
| ⌘V | formatlı (değişmez) | **formatsız** (yapı korunur: tablo/liste/imaj; karakter+paragraf biçimi imleçten) |
| Sağ tık "Yapıştır" (UDE öğesi) | formatlı | **formatsız** |
| ⌘⇧V | formatlı | **formatlı** (eski ⌘V davranışı) |
| Sağ tık "Formatlı Yapıştır" (YENİ) | formatlı | **formatlı** |
| Sağ tık "Formatsız Yapıştır" (kalır) | formatsız | formatsız |
| Editör-dışı metin alanları | düz `tc.paste()` (değişmez) | düz `tc.paste()` (değişmez) |

"Formatsız" = PLAINPASTE'in mevcut anlamı: karakter stili ve paragraf biçimi
imleçten, tablo/imaj/liste YAPISI kaynaktan (`NativeInsert` düz-karakter modu).

## Yaklaşım (seçilen: A — kanca-düzeyinde varsayılan değişimi)

`paste()` UDE-içi kopyayı (EditorDataFlavor) kancaya uğramadan başta kendisi
işler; harici içerik `hj.a(Transferable)` kancasına düşer. Kancanın varsayılanını
formatsıza çevirmek, `paste()`'e giden HER yolu (⌘V, menü çubuğu "Yapıştır",
sağ tık "Yapıştır") tek noktadan "akıllı" yapar — pano koklama, EditorDataFlavor
keşfi, katman tekrarları gerekmez. Formatlı istek "zorla formatlı" bayrağıyla
aynı kancadan eski zengin yola gider.

Reddedilen B: kısayol/menü katmanında pano flavor koklama — mantık iki katmanda
tekrarlanır, obfuscate EditorDataFlavor sınıfının keşfi kırılgan.

## Bileşenler

1. **`macospasterich.PasteMode` (YENİ, küçük):**
   - `private static volatile boolean forceRich` + `setForceRich(boolean)`.
   - `insertHtml(Object editor, String html)` ve `insertRtf(Object editor,
     Transferable t)`: `forceRich` ise `cursorAttrs=null` (zengin), değilse
     PlainPaste'in imleç-stili katmanlaması (taban TNR 12 → caret → kit input
     attrs) ile `RichPaste.insertInto/insertRtf(..., cursorAttrs)` çağrılır.
   - `PlainPaste.cursorAttrs` şu an private → PasteMode'un erişebileceği bir
     public/paket-içi yardımcıya açılır (davranış birebir aynı kalır).
   - Bayrak EDT'de set/temizlendiğinden (tek kullanıcı) volatile yeter; her
     kullanım `try/finally` ile temizler.

2. **`PasteRichPatch` (enjekte dal değişir):** `RichPaste.insertInto(this,__h)`
   → `macospasterich.PasteMode.insertHtml(this,__h)`; `RichPaste.insertRtf(this,__t)`
   → `PasteMode.insertRtf(this,__t)`. Kalan her şey aynı (uyap-web işaret koruması,
   başarısızlıkta düz-metin fallback, `logExternal`).

3. **`PlainPaste.addMenuItem` (menü):**
   - YENİ "Formatlı Yapıştır" öğesi ("Formatsız Yapıştır"dan sonra): eylem =
     `PasteMode.setForceRich(true); tc.paste(); finally setForceRich(false)`.
     (tc = hj türevi editör → virtual dispatch zengin `hj.paste()`.)
     Hızlandırıcı göstergesi: ⌘⇧V.
   - "Formatsız Yapıştır" öğesinin ⌘⇧V hızlandırıcı GÖSTERGESİ kaldırılır
     (⌘⇧V artık formatlı; formatsızın özel kısayolu yok — ⌘V "akıllı").
   - İdempotans korunur (iki öğe için de ada bakılır).

4. **`MacShortcutRemap`:**
   - ⌘V Map girdisi DEĞİŞMEZ (`Fb.PASTE`, "Yapıştır" menü eylemi — kanca
     değişince kendiliğinden akıllı olur).
   - ⌘⇧V: `Fb.PLAIN_PASTE` → `Fb.RICH_PASTE` (label=null kalır). `perform`:
     editörde reflection ile `PasteMode.setForceRich(true)` → menü "Yapıştır"
     `doClick` (bulunamazsa `tc.paste()`) → finally bayrak temizle. PasteMode
     sınıfı yoksa (PASTERICH=0 build) reflection düşer → yalnız menü/paste
     (zaten kanca yok = formatlı). `performLocal` (editör-dışı): `tc.paste()`
     (değişmez).

## Hata durumu

- Formatsız ekleme başarısız → kancadaki dal false/istisna → mevcut düz-metin
  fallback aynen çalışır.
- Bayrak `try/finally` ile hep temizlenir; yarıda kalan bayrak sonraki
  yapıştırmayı bozamaz.

## Test

- YENİ headless test (`tests/PasteModeTest.java`, javac+java elle): forceRich
  kapalıyken `insertHtml` karakter stilini düşürür (imleç stili), açıkken kaynak
  stili korunur; finally sonrası bayrak temiz.
- Mevcut testler (`PlainPasteStripTest`, `PlainPasteParaFormatTest`,
  `RichPasteSourcesTest` vb.) davranışı değişmediğinden aynen geçmeli
  (RichPaste API'sine dokunulmuyor, yalnız kancanın çağırdığı sarmalayıcı değişiyor).
- GUI doğrulaması kullanıcıda (elle test tercihi): Word'den ⌘V → formatsız,
  ⌘⇧V → formatlı; UDE-içi kopya ⌘V → formatlı; sağ tık üç öğe.

## Build notları

- `apply_pasterich` `macospasterich/*.java`'yı zaten topluca derler → PasteMode
  otomatik dahil; PasteRichPatch aynı adımda çalışır.
- ⌘⇧V değişikliği agent'ta → **`build.sh textkeys` adımı ŞART** (bilinen tuzak:
  `download && patch && lookagent && package` textkeys'i atlar, agent jar bayat
  kalır). Tam sıra: `download && patch && lookagent && textkeys && package && sign`.
