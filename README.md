# UDE — Apple Silicon (arm64) Native

[Uyap Doküman Editörü](https://uyap.gov.tr)'nü (UDE) Apple Silicon (M1/M2/M3/M4)
Mac'lerde **Rosetta olmadan** native çalıştırır.

> Resmî değildir; hiçbir kamu kurumu tarafından geliştirilmemiş/onaylanmamıştır.
> "Olduğu gibi" sunulur.

> ⚠️ **E-imza henüz gerçek kartla test EDİLMEDİ.** Belge açma/düzenleme çalışıyor;
> imzalama doğrulanmadı.

---

# 👩‍⚖️ Avukatlar için — Kurulum

Teknik bilgi gerektirmez. Sırayla yapın:

### 1) Java 8'i kurun (bir kez)

UDE'nin çalışması için Apple Silicon uyumlu Java 8 gerekir.

- Şu dosyayı indirin: **[Azul Zulu 8 (Apple Silicon) JDK](https://cdn.azul.com/zulu/bin/zulu8.94.0.17-ca-jdk8.0.492-macosx_aarch64.dmg)**
- İnen `.dmg` dosyasına çift tıklayın, açılan pencerede kurulum paketine çift tıklayıp
  adımları **İleri / Devam** diyerek tamamlayın.

### 2) UDE uygulamasını indirin

1. Bu sayfanın **sağ tarafında** **"Releases"** yazan başlığı bulun ve üzerine tıklayın.
   (Doğrudan gitmek için: [**en güncel sürüm**](../../releases/latest))
2. Açılan sayfada aşağı inin; **"Assets"** bölümünün altında adı **`...arm64.zip`**
   ile biten dosyaya tıklayıp indirin.
3. İndirdiğiniz zip dosyasına çift tıklayın; içinden **`Uyap Doküman Editörü.app`** çıkar.
4. Çıkan bu uygulamayı **Uygulamalar (Applications)** klasörüne sürükleyin.

### 3) İlk açılış (bir kez)

İlk açışta macOS **"uygulama açılamıyor / hasarlı / geliştirici doğrulanamadı"**
diyebilir. Bunu bir kez aşmak için:

1. **Terminal** uygulamasını açın: klavyede `Command (⌘) + Boşluk`'a basın, açılan
   kutuya **Terminal** yazıp **Enter**'a basın.
2. Aşağıdaki satırı **olduğu gibi kopyalayın**, Terminal penceresine **yapıştırıp**
   **Enter**'a basın:
   ```
   xattr -dr com.apple.quarantine "/Applications/Uyap Doküman Editörü.app"
   ```
3. Komut bir şey yazmadan biter; bu normaldir. Artık uygulamayı çift tıklayarak
   açabilirsiniz.

> E-imza kullanacaksanız, kullandığınız akıllı kart / e-imza sürücüsünün de Apple
> Silicon (arm64) sürümünün kurulu olması gerekir.

---

# 🛠️ Mühendisler için — Kaynaktan derleme

Hazır sürüme güvenmek yerine dönüşümü kendiniz çalıştırıp imzalayabilirsiniz.

## Neyi nasıl çözüyor

Resmî paket x86_64 derlenmiş. Native arm64 için iki gerçek engel var (üçüncü "engel" aslında sorun değil):

1. **x64 launcher** → arm64'ü garantileyen küçük bir kabuk launcher ile değiştirilir (`scripts/launcher.sh`).
2. **sqlite-jdbc 3.7.2** (arm64 native'i yok) → **3.46.x** ile değiştirilir (arm64 dylib içerir).
3. **JNA** → uygulama JNA'yı hiç çağırmıyor (bytecode taramasıyla doğrulandı), dokunulmaz.

E-imza, JDK'nın `javax.smartcardio` + `sun.security.pkcs11` API'leriyle çalışır (JNA değil).

## Gereksinimler

- Apple Silicon Mac
- **arm64 JDK 8** (yoksa `make jdk` Azul Zulu 8'i kurar)
- `curl`, `unzip`, `zip`, `codesign`, `plutil` (macOS'ta hazır gelir)

## Kullanım

```bash
make jdk     # arm64 JDK 8 yoksa kur (Azul Zulu 8 aarch64)
make all     # build/Uyap Doküman Editörü.app üret
```

Sonra `build/Uyap Doküman Editörü.app`'i `/Applications`'a sürükleyip çift tıklayın.

### Diğer hedefler

```
make help        # tüm hedefler
make check-deps  # araç + arm64 JDK denetimi
make download    # paketi indir + build/'e aç
make deps        # sqlite-jdbc indir + arm64 dylib doğrula
make patch       # editor-app.jar içinde sqlite swap
make sign        # ad-hoc codesign
make clean       # build/ sil
make distclean   # build/ + indirilenler + vendor jar sil
```

### Yeni UDE sürümü çıkınca

`UDE_URL` ile yeni paketi gösterin (ya da yerel zip için `UDE_ZIP`):

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
