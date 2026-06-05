# UDE — Apple Silicon (arm64) Native

[Uyap Doküman Editörü](https://uyap.gov.tr)'nü (UDE) Apple Silicon (M1/M2/M3/M4)
Mac'lerde **Rosetta olmadan** native çalıştırır. Rosetta çeviri katmanı olmadığı için
**daha hızlı** açılır ve çalışır. Java gömülü gelir; ayrıca bir şey kurmaya gerek yoktur
ve `.udf` dosyalarına **çift tıklayarak** açabilirsiniz.

![UDE — modern Material ikonlar ve Retina'da keskin metin](assets/ekran-goruntusu.jpeg)

> Modern Material ikonlar + Java 11 HiDPI ile **Retina'da keskin** metin ve arayüz.

> Resmî değildir; hiçbir kamu kurumu tarafından geliştirilmemiş/onaylanmamıştır.
> "Olduğu gibi" sunulur.

> ⚠️ **E-imza:** Kart okuyucu algılaması (`5.4.17_3`+) düzeltildi — gömülü Java artık
> PCSC üzerinden akıllı kartı görüyor. Tam imzalama akışı kullanıcı geri bildirimleriyle
> doğrulanmaktadır. Belge açma/düzenleme sorunsuz.

---

# 👩‍⚖️ Avukatlar için — Kurulum

Teknik bilgi gerektirmez. Java vb. kurmanıza **gerek yok**.

### 1) UDE uygulamasını indirin

1. Bu sayfanın **sağ tarafında** **"Releases"** yazan başlığa tıklayın.
   (Doğrudan gitmek için: [**en güncel sürüm**](../../releases/latest))
2. Açılan sayfada aşağı inin; **"Assets"** bölümünün altında adı **`...arm64.zip`**
   ile biten dosyaya tıklayıp indirin.
3. İndirdiğiniz zip dosyasına çift tıklayın; içinden **`Uyap Doküman Editörü.app`** çıkar.
4. Çıkan bu uygulamayı **Uygulamalar (Applications)** klasörüne sürükleyin.

### 2) İlk açılış (bir kez)

İlk açışta macOS **"geliştirici doğrulanamadı"** diyebilir. Bunu bir kez aşmak için:

1. **Terminal** uygulamasını açın: klavyede `Command (⌘) + Boşluk`'a basın, açılan
   kutuya **Terminal** yazıp **Enter**'a basın.
2. Aşağıdaki satırı **olduğu gibi kopyalayın**, Terminal penceresine **yapıştırıp**
   **Enter**'a basın:
   ```
   xattr -dr com.apple.quarantine "/Applications/Uyap Doküman Editörü.app"
   ```
3. Komut bir şey yazmadan biter; bu normaldir. Artık uygulamayı çift tıklayarak
   açabilirsiniz. Bundan sonra `.udf` dosyalarına da çift tıklayıp açabilirsiniz.

> E-imza kullanacaksanız, akıllı kart / e-imza sürücünüzün de Apple Silicon (arm64)
> sürümünün kurulu olması gerekir.

---

# 🛠️ Mühendisler için — Kaynaktan derleme

Hazır sürüme güvenmek yerine dönüşümü kendiniz çalıştırıp imzalayabilirsiniz.

## Neyi nasıl çözüyor

Resmî paket x86_64. Native arm64 için:

1. **Launcher** → `jpackage` ile arm64 **Java 11** runtime **gömülü**, gerçek native
   launcher üretilir. Kullanıcı Java kurmaz; macOS çift-tık (dosya açma) çalışır.
2. **Retina/keskin metin** → arm64 **Java 8** Swing'i Retina'da bulanık render ediyordu.
   **Java 11** (JEP 263 otomatik HiDPI) ile metin keskin. UDE'nin Java 8 bytecode'u
   Java 11'de çalışır (WebLaF illegal-access uyarıyla geçer).
3. **eawt-shim** → UDE'nin kullandığı eski `com.apple.eawt` API'si Java 11'de kaldırılmış.
   `scripts/eawt-shim` ile bu sınıflar `--patch-module java.desktop` üzerinden sağlanır;
   dosya açma, Java 11'in native dispatcher'ına reflection ile köprülenir → çift-tık korunur.
4. **sqlite-jdbc 3.7.2** (arm64 native'i yok) → **3.46.x** ile değiştirilir.
5. **JNA** → uygulama JNA'yı hiç çağırmıyor (bytecode taramasıyla doğrulandı), dokunulmaz.
6. **Modern ikonlar** (`ICONS=1`) → UDE'nin ~324 toolbar/aksiyon ikonu modern **Material**
   setiyle değiştirilir ve **Retina-keskin** yapılır. UDE ikonları düz `ImageIcon` olarak
   yüklenip HiDPI-farkında olmadığından, yükleyici (`Utils.b`) Javassist ile
   `BaseMultiResolutionImage`'a (1x + `@2x`) köprülenir. Override görseller
   `scripts/icons/overrides`, yama `scripts/icons/IconLoaderPatch.java`. Yayınlanan
   sürümler bu modu açık derlenir.

E-imza, JDK'nın `javax.smartcardio` + `sun.security.pkcs11` API'leriyle çalışır (JNA değil).
Gömülü Java'nın `javax.smartcardio` katmanı macOS'ta PCSC native kütüphanesini varsayılan
yolda bulamadığından kart okuyucu görünmüyordu; `jpackage`'a
`-Dsun.security.smartcardio.library=/System/Library/Frameworks/PCSC.framework/Versions/Current/PCSC`
java-option'ı gömülerek çözüldü (`5.4.17_3`+).

> Not: macOS codesign, `.app` adındaki Türkçe karakterlerle imzayı bozuyor; bu yüzden
> executable ASCII (`UyapDokumanEditoru`) tutulur, görünen ad sonradan Türkçe yapılır.

## Gereksinimler (yalnızca build için)

- Apple Silicon Mac
- **arm64 Java 11** (gömülecek runtime) — yoksa `make jdk` Azul Zulu 11'i kurar
- **jpackage'lı 17+ JDK** (jpackage + shim derlemesi) — yoksa `make jpackage-jdk` Azul Zulu 21'i kurar
- `curl`, `unzip`, `zip`, `codesign`, `plutil` (macOS'ta hazır gelir)

## Kullanım

```bash
make jdk           # gömülecek arm64 Java 11 yoksa kur
make jpackage-jdk  # jpackage'lı 17+ JDK yoksa kur
make all           # build/Uyap Doküman Editörü.app üret
ICONS=1 make all   # + modern Material/Retina ikonlar (yayın sürümleri böyle)
```

### Diğer hedefler

```
make help        # tüm hedefler
make check-deps  # araç + arm64 Java 11 + jpackage denetimi
make download    # paketi indir + kaynağı aç
make deps        # sqlite-jdbc indir + arm64 dylib doğrula
make shim        # eawt-shim derle (Java 11 com.apple.eawt yerine)
make patch       # editor-app.jar yamala (sqlite swap + eawt çıkar)
make package     # jpackage ile .app üret (Java 11 + shim, .udf ilişkilendirmeli)
make sign        # ad-hoc codesign
make clean / distclean
```

### Yeni UDE sürümü çıkınca

```bash
make distclean
UDE_URL="https://rayp.adalet.gov.tr/.../yeni-paket.zip" make all
```

> Uyarı: İleride paket yapısı (jar/sqlite sürümü) değişirse betiklerin güncellenmesi gerekebilir.

## Otomatik release

`.github/workflows/release.yml` (elle tetiklenir): macOS arm64 runner'da `make all`
çalıştırır, `.app`'i imzayı bozmadan zip'ler ve **Release**'e ekler. Sürüm,
UDE sürümünden türetilir: `<ude_surumu>_<N>` (ör. `5.4.17_1`).

---

## Teşekkür

Bu çalışmaya ilham veren ve sorunun çözüm yolunu ortaya koyan
[**tosbaha**](https://github.com/tosbaha) kullanıcısına ([tosbaha/uyap-arm](https://github.com/tosbaha/uyap-arm))
teşekkürler.
