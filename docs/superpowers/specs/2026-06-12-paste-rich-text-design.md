# Harici stilli yapıştırma (PASTERICH) — Tasarım

**Tarih:** 2026-06-12
**Durum:** Tasarım onayı bekliyor
**Branch:** `feature/paste-rich-text`

## Problem

Word, tarayıcı veya PDF gibi UDE dışı bir uygulamadan kopyalanan **stilli metin**,
UDE editörüne yapıştırıldığında **düz metne** dönüşüyor (kalın/italik/renk/font/
hizalama/liste/tablo kaybı). Kullanıcı bu içeriğin biçimlendirmesiyle yapışmasını
istiyor — tam sadakat (liste ve tablo dahil).

## Mevcut davranışın nedeni (kök neden — kanıtlı)

`hj.paste()` (`tr.com.havelsan.uyap.system.editor.common.text.hj`) stilli içeriği
YALNIZCA iki kaynaktan korur:

1. **UDE-içi kopyalama:** `copy()` panoya `EditorDataFlavor`
   (`application/x-java-serialized-object`) koyar; `paste()` bunu tanır.
2. **UYAP web editöründen kopyalama:** `hj.a(Transferable)` ("paste from web"),
   panodaki HTML içinde `<span style="display:none" id="uyap-web-editor-data">
   BASE64</span>` işaretini arar. Bulursa BASE64'ü çözüp `WPDocumentPanel.a(
   InputStream)`'e besler → `select-all` → `copy()` → `this.paste()`.

Harici kaynakta (Word/tarayıcı/PDF) bu işaret yoktur; `a(Transferable)` `false`
döner ve `paste()` imaj/düz-metin dallarına düşer → **biçim kaybı (tasarımı gereği)**.

## Yeniden kullanılacak hazır parçalar (kanıtlı bulgular)

- **UDE içerik formatı `content.xml` (format_id 1.8):** `<content><![CDATA[…tüm düz
  metin…]]></content>` (global offset referansı) + `<elements resolver="hvl-default">`
  içinde yapısal `<paragraph>`/`<table>`/`<row>`/`<cell>`; her `<content startOffset
  length family size bold italic underline foreground>` run'ı global CDATA metnine
  offset'le bağlanır. Liste: `Numbered/Bulleted` + `NumberType/BulletType` +
  `ListLevel`. Renk: işaretli int RGB (ör. -65536=kırmızı). Hizalama: 0/1/2/3.
- **`WPDocumentPanel.a(InputStream)` bir UDF (zip) okuyucusudur:** içerideki `d.u`
  okuyucu `content.xml` ve `sign.sgn` girdilerini okur. Yani panele **UDF zip
  akışı** verilir (imzasız UDF kabul edilir — UDE imzasız taslakları açar).
- **`udf-cli` projesi** (`/Users/saidsurucu/Documents/GitHub/udf-cli`, TypeScript,
  v0.4.0): "Convert between HTML/Markdown and UYAP UDF". `html2udf` komutu HTML'i
  (dosya/stdin/ham string) alıp **`.udf` (zip)** üretir. HTML→content.xml mantığı
  (`html/parser.ts` + `model/document.ts` + `udf/serializer.ts` + `cdata-builder.ts`)
  zaten yazılmış, test edilmiş ve `test-output/` altında doğrulanmış. **html2udf
  yolu tamamen saf-JS** (`htmlparser2` + `jszip`); native bağımlılık yok (pkcs11
  yalnız `sign/` yolunda, html2udf onu çekmez).

## Mimari

Harici stilli içeriği UDE'nin **kendi formatına** çevirip **kendi yükleyicisine**
besleyerek tam sadakate ulaşılır. Dönüşüm udf-cli ile yapılır (yeniden yazma yok).

```
Pano (public.html → allHtmlFlavor : String)
  └─► udf-cli html2udf  (paketli, self-contained bun ikilisi; alt süreç)
        └─► .udf bayt dizisi (zip: content.xml [+ yok sign.sgn])
              └─► WPDocumentPanel.a(InputStream)   ← UDF okuyucu (mevcut)
                    └─► DocumentEx → select-all → copy()  (EditorDataFlavor panoya)
                          └─► hj.paste()  (mevcut EditorDataFlavor dalı → caret'e ekler)
```

Bu, UYAP-web-işareti yolunun **birebir aynısı**; tek farkımız base64-from-marker
yerine udf-cli'nin ürettiği `.udf`'yi beslemek. UDE'nin tüm liste/tablo/paragraf/
karakter işleme makinesi olduğu gibi yeniden kullanılır.

### Bağlanma noktası

`hj.a(Transferable)`'ı Javassist ile genişlet: `allHtmlFlavor` destekleniyor **ama**
`uyap-web-editor-data` işareti **yok** ise → harici-stil yoluna gir. Başarıyla
beslenirse `true` döndür (işlendi); dönüşüm/besleme başarısızsa `true` DÖNDÜRME →
mevcut imaj/düz-metin dalları graceful fallback olarak çalışsın. UDE-içi ve
UYAP-web yolları aynen korunur (tam geriye dönük uyum).

EDT notu: `paste()` EDT'de çalışır; mevcut web yolu da `WPDocumentPanel`'i EDT'de
üretir. Yeni yol da aynı iş parçacığında kalır (Substance EDT-threading kuralı için
mevcut davranışla tutarlı). Alt süreç çağrısı kısa-bloklayıcıdır; büyük HTML için
makul bir timeout uygulanır.

## Bileşenler

1. **`macospasterich` agent/patch paketi** (yeni)
   - `RichPaste.fromClipboardHtml(String html) -> byte[] udf | null`: udf-cli
     ikilisini `ProcessBuilder` ile çağırır (`html2udf - -`: stdin HTML, stdout
     .udf), baytları döndürür. Hata/timeout/boş çıktıda `null`.
   - İkili yolu çözümleme: `.app/Contents/Resources/udf-cli` (paketli). Geliştirme
     ortamı için `UDE_UDFCLI` env override.
   - Teşhis: `UDE_PASTERICHLOG=1` → `~/Library/Logs/ude-pasterich.txt`.

2. **`PasteRichPatch` (build-zamanı Javassist)** (yeni)
   - `hj.a(Transferable)` gövdesine: işaret yoksa + HTML varsa
     `RichPaste.handle(this, transferable)` çağrısı; başarıda mevcut `select-all →
     copy → paste` zincirini sentezleyip `true` döndür. Mantık tek noktada
     (`RichPaste`) toplanır; patch yalnız kanca enjekte eder.

3. **Paketli udf-cli ikilisi**
   - `bun build --compile --target=bun-darwin-arm64` ile html2udf-only giriş
     noktasından üretilir (saf-JS, native-dep yok). `.app/Contents/Resources/`
     altına kopyalanır.

## Build entegrasyonu

- `build.sh`: `PASTERICH="${PASTERICH:-1}"` bayrağı (varsayılan açık).
- `apply_pasterich`: (a) udf-cli ikilisini üret/çöz (kaynak `udf-cli` reposundan
  ya da paketli) → Resources'a koy; (b) `macospasterich` sınıflarını jar'a enjekte;
  (c) `PasteRichPatch`'i çalıştır. İdempotans guard'ı (marker sınıfı).
- jpackage java-options: ek gerekmiyor (alt süreç runtime'da çözülür).
- Risk: ikilinin nasıl üretileceği (udf-cli reposu mevcut mu, sürüm sabitleme) plan
  aşamasında netleşir. udf-cli yoksa PASTERICH atlanır (uyarı), build düşmez.

## Phase 0 — besleme spike (ilk uygulama adımı, doğrulama kapısı)

Tasarımın tek empirik bilinmeyeni: udf-cli `html2udf` çıktısı `.udf`'nin
`WPDocumentPanel.a(InputStream)` tarafından kabul edilip doğru DocumentEx üretmesi.
Statik kanıt güçlü (`u` UDF zip okuyor; test-output .udf'leri UDE'de açılıyor) ama
canlı doğrulama şart:

- Dynamic-attach probe (CLAUDE.md deseni): canlı UDE'de bir test `.udf`'yi
  (`test-output/02-tablo.udf` vb.) `WPDocumentPanel.a(InputStream)`'e ver → dönen
  DocumentEx'in metin uzunluğu/element sayısını logla; imzasız UDF kabul ediliyor mu
  gör. Geçerse mimari onaylı; kalırsa besleme yolu (imza ekleme / ham content.xml
  alternatifi) yeniden değerlendirilir.

## Kenar durumlar / riskler

- **Yalnız düz metin panoda** (HTML yok): yeni yol tetiklenmez, mevcut düz-metin
  dalı çalışır. ✅ değişiklik yok.
- **Word HTML başlıkları / macOS `public.html` sarması:** htmlparser2 keyfi HTML'i
  ayrıştırır; udf-cli zaten gerçek dünya HTML'inde test edilmiş.
- **Resimler:** Word panoda resmi HTML içinde değil ayrı item olarak verir → ilk
  kapsamda resim taşınmaz (mevcut imaj-yapıştırma yolu ayrı). Sınırlama olarak not
  edilir.
- **Alt süreç gecikmesi / yokluğu:** ikili yoksa/başarısızsa `null` → düz-metin
  fallback (özellik sessizce devre dışı, çökme yok).
- **Büyük HTML:** timeout (ör. 5 sn); aşılırsa fallback.
- **Dikteyle giren metin:** ilgisiz (pano yolu değil).
- **Güvenlik:** alt süreç yalnız paketli sabit ikiliyi çağırır (kullanıcı girdisi
  argüman değil stdin); shell injection yok.

## Test

- **udf-cli tarafı:** mevcut udf-cli test suit'i (HTML→UDF) zaten kapsıyor;
  dokunmuyoruz.
- **Besleme:** Phase 0 dynamic-attach probe (yukarıda).
- **RichPaste birim testi:** örnek Word-HTML string → `fromClipboardHtml` →
  geçerli `.udf` baytı (zip imzası `PK`, içinde `content.xml`); `tests/` altında
  javac+java elle (proje deseni).
- **Uçtan uca (elle, kullanıcı):** Word'den stilli metin/tablo/liste kopyala →
  UDE'ye yapıştır → biçim korunuyor mu (kullanıcı GUI doğrulaması — proje tercihi).

## Kapsam dışı (YAGNI)

- Resim taşıma (ayrı clipboard item).
- RTF kaynağı (ilk sürüm `allHtmlFlavor`/HTML; Word ve tarayıcılar HTML verir).
- udf-cli'nin Java'ya portu (alt süreç yeniden kullanımı tercih edildi — kullanıcı
  udf-cli'yi aktif geliştiriyor; tek doğruluk kaynağı korunur).
```
