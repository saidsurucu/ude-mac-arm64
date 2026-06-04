# UDE — Apple Silicon (arm64) Native

[Uyap Doküman Editörü](https://uyap.gov.tr)'nü (UDE) Apple Silicon (M1/M2/M3/M4)
Mac'lerde **Rosetta olmadan** native çalıştırır. Java gömülü gelir; ayrıca bir şey
kurmaya gerek yoktur ve `.udf` dosyalarına **çift tıklayarak** açabilirsiniz.

> Resmî değildir; hiçbir kamu kurumu tarafından geliştirilmemiş/onaylanmamıştır.
> "Olduğu gibi" sunulur.

> ⚠️ **E-imza henüz gerçek kartla test EDİLMEDİ.** Belge açma/düzenleme çalışıyor;
> imzalama doğrulanmadı.

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

1. **Launcher** → `jpackage` ile arm64 Java 8 JRE **gömülü**, gerçek native launcher
   üretilir. Böylece kullanıcı Java kurmaz **ve** macOS dosya açma (çift-tık) çalışır
   (kabuk-script launcher Apple Event'i JVM'e iletemiyordu).
2. **sqlite-jdbc 3.7.2** (arm64 native'i yok) → **3.46.x** ile değiştirilir.
3. **JNA** → uygulama JNA'yı hiç çağırmıyor (bytecode taramasıyla doğrulandı), dokunulmaz.

E-imza, JDK'nın `javax.smartcardio` + `sun.security.pkcs11` API'leriyle çalışır (JNA değil).

> Not: macOS codesign, `.app` adındaki Türkçe karakterlerle imzayı bozuyor; bu yüzden
> executable ASCII (`UyapDokumanEditoru`) tutulur, görünen ad sonradan Türkçe yapılır.

## Gereksinimler (yalnızca build için)

- Apple Silicon Mac
- **arm64 Java 8** (gömülecek runtime) — yoksa `make jdk` Azul Zulu 8'i kurar
- **jpackage'lı 17+ JDK** — yoksa `make jpackage-jdk` Azul Zulu 21'i kurar
- `curl`, `unzip`, `zip`, `codesign`, `plutil` (macOS'ta hazır gelir)

## Kullanım

```bash
make jdk           # gömülecek arm64 Java 8 yoksa kur
make jpackage-jdk  # jpackage'lı 17+ JDK yoksa kur
make all           # build/Uyap Doküman Editörü.app üret
```

### Diğer hedefler

```
make help        # tüm hedefler
make check-deps  # araç + arm64 Java 8 + jpackage denetimi
make download    # paketi indir + kaynağı aç
make deps        # sqlite-jdbc indir + arm64 dylib doğrula
make patch       # editor-app.jar içinde sqlite swap
make package     # jpackage ile .app üret (.udf ilişkilendirmeli, JRE gömülü)
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
