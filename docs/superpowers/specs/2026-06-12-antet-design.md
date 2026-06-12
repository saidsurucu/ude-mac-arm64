# Antetlerim — kişisel antet bölümü (ANTET) tasarımı

Tarih: 2026-06-12
Durum: Onaylandı (kullanıcı, brainstorming oturumu)

## Amaç

Avukat kullanıcılar dilekçelerine kendi antetlerini kolayca eklesin. Bugünkü iki
şikâyetin çözümü:

1. **"Her seferinde GÖZAT ile antet seçmek zor"** — kurum modülleri (Yargıtay,
   Adalet Bakanlığı…) gibi kullanıcının kendi antetleri de "Arka Plan Resmi
   Düzenleme" diyaloğunda tek tıkla seçilebilir butonlar olsun.
2. **"Basınca antet sayfadan taşıyor, işe yaramıyor"** — Antetlerim'den seçilen
   resim A4 sayfaya orana sadık sığdırılsın; önizlemede görünen = basılan.

Kapsam dışı (kullanıcı eledi): hazır gömülü avukat şablonları, antet üretme
sihirbazı, GÖZAT yolunun davranışını değiştirmek, "Klasörü Aç" butonu.

## Keşif bulguları (bytecode)

- Diyalog: `tr.com.havelsan.uyap.system.editor.common.gui.gR`
  ("Arka Plan Resmi Düzenleme"). Modül listesi `gR.b()` içinde SABİT KODLU
  `gV(gR, dizin, etiket, dosyaListesi)` nesneleri; butonları `gR.c()` kurar
  (`gW` buton + `gY` ActionListener → `/resources/modules/<dizin>/<dosya>`
  classpath'ten yükler). GÖZAT butonu `c()` sonunda `cell 0 8`'e eklenir.
- `gR`'de sentetik erişimciler hazır: `static void a(gR, BufferedImage)` (resim),
  `static void a(gR, String)` (ad), `static dN a(gR)` (panel) — kendi
  handler'ımız resmi basıp STOK önizleme/Tamam akışını kullanabilir.
- Arka plan resmi UDF'ye `bgImage` elementi olarak GÖMÜLÜR (`wp.model.v`,
  `text.C`; width/height öznitelikli) → belge UYAP'a antetiyle gider, karşı
  tarafta da görünür. Persist/baskı katmanına dokunmaya gerek yok.
- Şerit eylemi `text.bn` ("Arka Plan Resmi Ekle", `insert-background-image`);
  "belge önce diske kaydedilmeli" uyarısı stokta var, aynen kalır.
- Diyalogda "Kenarlar (cm)" alanları (Üst/Alt/Sol/Sağ) stok; bizim akışta da
  aynen çalışır.
- Taşmanın kök nedeni: stok yol resmi doğal piksel boyutuyla yerleştirir;
  A4'ten büyük raster (örn. 300dpi tarama) sayfadan taşar.

## Tasarım

### UI (gR diyaloğu)

Modüller kutusunun ALTINA ayrı çerçeveli **"Antetlerim"** bölümü:

- Klasördeki her resim için bir buton; etiket = dosya adı (uzantısız).
  Her butonun yanında küçük **×** — tıklayınca onay sorar, dosyayı siler,
  listeyi yeniler.
- **"Antet Ekle…"** butonu: native dosya penceresi (mevcut FILEDIALOG
  altyapısı) → seçilen PNG/JPG antet klasörüne kopyalanır, liste anında
  yenilenir. Aynı adla ekleme üzerine yazar.
- Antet butonuna tıklayınca: resim diskten okunur → A4'e sığdırılır →
  `gR`'nin resim/ad alanlarına basılır → stok önizleme, Kenarlar ve
  Tamam/Vazgeç akışı değişmeden çalışır.

Liste her diyalog açılışında klasörden taze okunur (cache yok).

### Depo

- Klasör: `~/Library/Application Support/UDE/Antetler` — ilk "Antet Ekle…"de
  oluşturulur. Kabul edilen uzantılar: png, jpg, jpeg (büyük/küçük harf
  duyarsız).

### A4 sığdırma (yalnız Antetlerim yolu)

- Sığdırma hedefi belgenin sayfa boyutudur (gR'nin bağlı olduğu belgeden
  okunur; okunamazsa A4 varsayılır). Resim hedefe **orana sadık, içine
  sığacak şekilde (contain)** ölçeklenir; yeniden örnekleme bicubic. Sayfa
  oranındaki kaynak sayfayı tam doldurur; farklı oranlı kaynak taşmak yerine
  içeride kalır.
- GÖZAT ve kurum modülleri stok davranışta kalır (kullanıcı kararı).

### Bileşenler

1. `scripts/antet/AntetPatch.java` — build-zamanı Javassist yaması:
   - `gR.c()` sonuna insertAfter ile Antetlerim bölümünü kurar.
   - `macosantet/*` sınıflarını jar'a enjekte eder (PopupRemap/LiveToggle
     deseni; agent değil, build yaması).
2. `macosantet.AntetStore` (jar'a enjekte) — klasör tarama/kopyalama/silme,
   resim okuma, contain-fit hesabı, `gR` sentetik erişimcileriyle yansıma
   üzerinden bağ.

### Build kablolaması

- `build.sh`'e `ANTET` bayrağı, varsayılan **1**; `.antet-patched` idempotans
  marker'ı (download her iterasyonda taze kaynak getirir).
- SKIN'den bağımsız; `SKIN=0`'da da çalışır.

## Hata durumları

- Okunamayan/bozuk resim → uyarı diyaloğu, buton atlanır; diyalog çalışmaya
  devam eder.
- Klasör henüz yoksa bölüm YİNE çizilir (boş liste + "Antet Ekle…"); klasör
  ilk eklemede oluşturulur. Oluşturma/kopyalama hatası → uyarı diyaloğu.
- Bölümü kuran kod beklenmedik hata fırlatırsa try/catch ile yutulur; stok
  diyalog bozulmadan açılır.
- Silmede onay diyaloğu; silme başarısızsa uyarı, liste olduğu gibi kalır.
- "Belge önce kaydedilmeli" stok uyarısı (text.bn) aynen devrede.

## Test

- `tests/AntetFitTest.java` — contain-fit matematiği (javac+java elle,
  projedeki test deseni): A4 oranı tam doldurma, geniş/uzun kaynakların
  içeride kalması, küçük resmin büyütülmesi.
- `tests/AntetStoreTest.java` — geçici dizinde tarama/kopyalama/silme/uzantı
  filtresi.
- GUI doğrulaması elle (kullanıcı tercihi): build → diyalogda antet ekleme,
  seçme, silme; bir belgeye antet basıp baskı önizleme/PDF ile taşma kontrolü.

## Açık olmayan noktalar / bilinçli sınırlar

- Antetler yereldir; başka Mac'e taşıma = klasörü kopyalama (UDF'ye gömüldüğü
  için gönderilen belgelerde sorun yok).
- Çok sayıda antet eklenirse sol sütun uzar; panel autoscroll'lu (`dN
  setAutoscrolls(true)`), ilk sürümde ek sayfalama yapılmaz.
- Sığdırma yalnız ekleme anında; sonradan Kenarlar değişirse stok davranış ne
  yapıyorsa o (değiştirmiyoruz).
