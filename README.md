# UDE — Apple Silicon (arm64) Native

[Uyap Doküman Editörü](https://uyap.gov.tr)'nü (UDE) Apple Silicon (M1/M2/M3/M4)
Mac'lerde **Rosetta olmadan** native çalıştırır. Rosetta çeviri katmanı olmadığı için
**daha hızlı** açılır ve çalışır. Java gömülü gelir; ayrıca bir şey kurmaya gerek yoktur
ve `.udf` dosyalarına **çift tıklayarak** açabilirsiniz.

> 💻 **Intel Mac'iniz mi var?** Aşağıdaki tüm mimari-bağımsız iyileştirmeler (keskin metin,
> modern ikonlar, Mac kısayolları, native pencereler, e-imza, PDF Türkçe harf) Intel'de de
> geçerli. Kurulum komutu için bkz. **[Intel işlemcili Mac'ler için](#-intel-işlemcili-macler-için)**.

![UDE — modern Material ikonlar ve Retina'da keskin metin](assets/ekran-goruntusu.jpeg)

> Modern Material ikonlar + Java 11 HiDPI ile **Retina'da keskin** metin ve arayüz.

> ⚠️ **Bu depo UYAP Doküman Editörü'nün kaynak kodunu içermez.** Tamamen bağımsız,
> **gayriresmî** bir Mac **yamasıdır**: hiçbir kamu kurumu tarafından
> geliştirilmemiş/onaylanmamıştır. Burada bulunan yalnızca yama ve build betikleridir;
> resmî UDE paketi build sırasında uyap.gov.tr'den **siz** indirir ve yamayı uygulamanın
> üstüne **siz** çalıştırırsınız. "Olduğu gibi" sunulur.

> ✅ **E-imza çalışıyor:** Akıllı kart okuyucu algılaması (`5.4.17_4`+) çözüldü —
> gömülü Java artık PCSC üzerinden kartı görüyor ve imzalama akışı baştan sona
> çalışıyor. Belge açma/düzenleme de sorunsuz.

> 🗂️ **Native macOS dosya pencereleri:** Aç / Kaydet / Farklı Kaydet artık eski
> görünümlü Java penceresi yerine macOS'un **kendi** dosya penceresini kullanır —
> Finder kenar çubuğu, iCloud Drive, son kullanılanlar ve `.udf` filtresiyle.

![UDE — native macOS Aç/Kaydet penceresi](assets/native-dosya-penceresi.png)

> Aç/Kaydet pencereleri artık macOS'un native dosya seçicisi.

> 📦 **Hazır (paketlenmiş) uygulama dağıtılmaz.** İşgüzarlarla uğraşmak istemediğim
> için paketlenmiş hâlini dağıtmıyorum; uygulamayı **kendiniz derleyip paketlersiniz**.
> Bu sayfada bir "Releases" / hazır indirme bağlantısı **bulmazsınız**. Aşağıdaki adımlar
> derlemeyi olabildiğince kolaylaştırır — komutları kopyala-yapıştır ile çalıştırmanız yeterli.

---

# 👩‍⚖️ Kolay kurulum — tek satır

Programcı olmanıza gerek yok. **Terminal** uygulamasını açın (klavyede
`Command (⌘) + Boşluk`'a basıp açılan kutuya **Terminal** yazın ve **Enter**'a basın),
ardından aşağıdaki **tek satırı** kopyalayıp yapıştırın ve **Enter**'a basın:

```bash
arch -arm64 bash -c "$(curl -fsSL https://raw.githubusercontent.com/saidsurucu/ude-mac-arm64/main/kur.sh)"
```

Hepsi bu kadar. Manuel indirme, klasöre girme, Java kurma gibi adımlar **yok**. Bu komut
gerisini sizin için yapar:

- Gerekiyorsa **geliştirici araçlarını** (Xcode komut satırı araçları) kurar — bir pencere
  açılırsa yalnızca **"Yükle"**ye basıp bitmesini bekleyin, betik kendiliğinden devam eder.
- **Kaynak kodu** `~/ude-mac-arm64` klasörüne indirir (zaten varsa en güncel sürüme günceller).
- Gereken **Java** sürümlerini otomatik indirir.
- Uygulamayı modern ikonlarla **derler + imzalar** ve doğrudan **/Applications** klasörüne kurar.

İlk derleme internet hızınıza göre birkaç dakika sürebilir.

Bittiğinde uygulama **Launchpad** ve **Applications** klasöründe hazırdır; çift tıklayarak
ya da `.udf` dosyalarına çift tıklayarak açabilirsiniz. (Kendiniz derleyip imzaladığınız
için macOS "geliştirici doğrulanamadı" uyarısı **çıkmaz**; `xattr` ile uğraşmanıza gerek
yoktur.)

**Yeni Editör sürümü çıktığında yukarıdaki tek satırı yeniden çalıştırmanız yeterli. En
güncel sürüm otomatik inecek ve yamalanacak.**

## 💻 Intel işlemcili Mac'ler için

Mac'iniz Intel (x86_64) işlemcili ise — yani Apple Silicon (M1/M2/M3/M4) **değilse** —
aynı kurulumu şu **tek satırla** yapın (Apple Silicon'dakinin aksine `arch -arm64`
**yoktur**; Intel zaten yalnız x86_64'tür):

```bash
bash -c "$(curl -fsSL https://raw.githubusercontent.com/saidsurucu/ude-mac-arm64/main/kur.sh)"
```

> Mimarinizden emin değil misiniz? Sol üstteki **Apple () menüsü → Bu Mac Hakkında**'ya
> bakın: **"Apple M…"** yazıyorsa üstteki Apple Silicon komutunu, **"Intel"** yazıyorsa
> bu komutu kullanın. (Betik mimariyi kendisi de algılar; yanlış komutu çalıştırırsanız
> sizi uyarır.)

Intel'de resmî UDE paketi zaten x86_64 olduğu için burada kazanç "Rosetta'sız native hız"
**değildir**; kazanç şunlardır: **Java 11 ile Retina'da keskin metin**, **modern Material
ikonlar**, **Mac klavye kısayolları**, **native macOS Aç/Kaydet pencereleri**, **trackpad /
⌘ ile yakınlaştırma**, **e-imza (akıllı kart) düzeltmesi** ve **PDF dışa aktarımında Türkçe
harfler**. Yani mimariye bağlı olmayan tüm iyileştirmeler Intel'de de geçerlidir.

### E-imza kullanacaksanız — AKİS sürücüsü (zorunlu)

Akıllı kart / e-imza sürücünüzün de Mac'inizin mimarisine uygun sürümünün kurulu olması
gerekir. TÜBİTAK BİLGEM AKİS — Destek/İndirme sayfasından
(<https://akiskart.bilgem.tubitak.gov.tr/destek/>):

- **Apple Silicon (M1/M2/M3/M4):** **"Mac OS Arm (Apple Silicon)"** başlığı altındaki
  güncel paketi indirin (ör. `Akia_macos_arm_6_8_9.pkg`).
- **Intel:** **"Mac OS Intel"** başlığı altındaki paketi indirin.

İndirilen `.pkg`'a çift tıklayıp kurulumu tamamlayın (yönetici şifresi ister). Mac'inizin
mimarisine **uymayan** paketi seçmeyin.

### ⌨️ Klavye kısayolları

Bu derlemede kısayollar Mac'e uyarlanmıştır (kalın `⌘B`, kaydet `⌘S`, hizalama
`⌘L/E/R/J`, yazı boyutu `⌘⇧.`/`⌘⇧,` …). Tam liste için bkz. **[KISAYOLLAR.md](KISAYOLLAR.md)**.

---

# 🛠️ Mühendisler için — Teknik ayrıntı

Yukarıdaki adımlar derlemek için yeterlidir. Bu bölüm, dönüşümün **neyi nasıl** çözdüğünü
ve tek tek build hedeflerini açıklar.

## Neyi nasıl çözüyor

Build, çalıştığı Mac'in mimarisini `uname -m` ile **otomatik algılar** (Apple Silicon=arm64,
Intel=x86_64) ve gömülecek Java ile launcher'ı o mimari için üretir. Aşağıdaki çözümler
arm64'ün özgün gerekçesiyle anlatılır; **2, 3, 6, 7, 8, 9** maddeleri mimariden bağımsızdır
ve Intel'de de aynen geçerlidir. Madde 4 (sqlite) arm64'te zorunluydu (3.7.2'nin arm64
native'i yoktu); Intel'de 3.7.2 zaten x86_64 native içerir ama tek tip build için 3.46.x'e
geçiş orada da uygulanır (jar hem `Mac/aarch64` hem `Mac/x86_64` dylib taşır).

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

7. **E-imza** (`5.4.17_4`+) → JDK'nın `javax.smartcardio` + `sun.security.pkcs11`
   API'leriyle çalışır (JNA değil). Gömülü Java'nın `javax.smartcardio` katmanı macOS'ta
   PCSC native kütüphanesini varsayılan yolda bulamadığından kart okuyucu görünmüyordu.
   Çözüm `-Dsun.security.smartcardio.library=/System/Library/Frameworks/PCSC.framework/Versions/A/PCSC`
   ile bu yolu vermek; ancak `jpackage`'ın `.cfg` java-option'ları çift-tıkla açılan
   launcher'da bu JVM'e ulaşmıyordu (kullanıcı `lsof` ile doğruladı: framework yüklenmiyordu).
   Bu yüzden parametre, JVM'in her zaman okuduğu `JAVA_TOOL_OPTIONS` ortam değişkenine,
   `.app`'in `Info.plist`'indeki `LSEnvironment` (Launch Services) anahtarıyla gömülür →
   çift-tık açılışta garanti uygulanır. (`Versions/Current` symlink yerine kanıtlanmış
   `Versions/A` yolu kullanılır.)

8. **Trackpad ile yakınlaştırma** → UDE'de zoom yalnızca durum çubuğundaki kaydırıcıyla
   yapılabiliyordu; trackpad jesti yoktu. `scripts/macos-zoom` javaagent'ı **⌘ + iki parmak
   kaydırma** jestini bu kaydırıcıya bağlar: Cmd basılıyken gelen `MouseWheelEvent` yakalanıp
   yutulur (belge kaymaz) ve uygulamanın zoom kaydırıcısı sürülür → belge büyür/küçülür.
   Gerçek iki-parmak *pinch* jesti modern Java'ya iletilmediğinden ⌘ tuşu gerekir; ⌘'süz
   kaydırma belgeyi normal kaydırır. Olayı yutabilmek için sistem `EventQueue`'su **ilk odak
   olayında** devralınır (premain'de erken devralma WebLaF tarafından baypas edilir).
   Ayrıca **`⌘+` / `⌘−`** klavye kısayolları da aynı kaydırıcıyı sürer (bir `KeyEventDispatcher`
   ile); macOS'un standart yakınlaştır/uzaklaştır tuşları beklendiği gibi çalışır.

9. **Standart Mac kısayolları** → UDE'nin editörü Windows kökenli; kısayollar Ctrl tabanlı ve
   bir kısmı alışılmadık tuşlarda (kalın `Ctrl+K`, italik `Ctrl+T`, altı çizili `Ctrl+Shift+A`,
   bul `Ctrl+B`). Üstelik macOS, metin bileşenlerine yerleşik **Emacs imleç bağlamaları** ekler
   (`Ctrl+A` satır başı, `Ctrl+B` harf geri, `Ctrl+N/P/O/F`, `Ctrl+V` sayfa aşağı…) ve bunlar
   uygulamanın komutlarından önce çalışır → sentetik bir `Ctrl+A` "tümünü seç" değil "satır başı"
   yapar. `scripts/macos-textkeys`'teki `MacShortcutRemap` (bir `KeyEventDispatcher`) bu yüzden
   üç katmanlı çalışır:
   - **Menü-etiketi**: Menüde karşılığı olan komutlar için odaktaki pencerenin menü ağacında
     etiketle eşleşen etkin `JMenuItem` bulunup `doClick()` edilir. Bu, uygulamanın **gerçek**
     eylemini (zengin-metin yapıştırma vb.) çağırır ve odak bileşenini kullanmadığından Emacs
     gölgesini tamamen baypas eder. (Menü öğelerinin hızlandırıcısı yoktur; eşleme etiketledir.)
   - **Metin API'si**: Seç/kopyala/kes/yapıştır için yedek olarak doğrudan `JTextComponent`.
   - **Sentetik Ctrl**: Menüde olmayan biçimlendirme (kalın/italik/altı çizili) için odaktaki
     bileşene sentetik Ctrl gönderilir (uygulama bu tuşları Emacs'in üzerine ezdiğinden çalışır).

   Eşlemeler: `⌘B/⌘I/⌘U`→kalın/italik/altı çizili, `⌘C/⌘V/⌘X/⌘A`→kopyala/yapıştır/kes/tümünü seç,
   `⌘Z/⌘⇧Z`→geri/ileri al, `⌘N/⌘O/⌘S/⌘⇧S`→yeni/aç/kaydet/farklı kaydet, `⌘P/⌘⇧P`→yazdır/önizleme,
   `⌘F`→bul, `⌘T`→yazı özellikleri. Mevcut Ctrl kısayolları aynen çalışır; `⌘Q/⌘W/⌘H/⌘M` gibi
   gerçek macOS kısayollarına dokunulmaz.

10. **Modern düz görünüm** (`SKIN=1`) → Menüler, paneller, şerit ve durum çubuğu degradesiz
    nötr açık renklere geçer; arayüz yazı tipi modernleşir; belge çevresindeki eski turkuaz
    arka plan nötr griye düzeltilir. Şerit düzeni ve belge içeriği değişmez. Varsayılan
    olarak kapalıdır; yayın sürümlerinde isteğe bağlı kullanılır.

> Not: macOS codesign, `.app` adındaki Türkçe karakterlerle imzayı bozuyor; bu yüzden
> executable ASCII (`UyapDokumanEditoru`) tutulur, görünen ad sonradan Türkçe yapılır.

## Gereksinimler (yalnızca build için)

- macOS Mac (Apple Silicon **veya** Intel — mimari `uname -m` ile otomatik algılanır)
- **Java 11** (gömülecek runtime, Mac'inizin mimarisi için) — yoksa `make jdk` Azul Zulu 11'i kurar
- **jpackage'lı 17+ JDK** (jpackage + shim derlemesi) — yoksa `make jpackage-jdk` Azul Zulu 21'i kurar
- `curl`, `unzip`, `zip`, `codesign`, `plutil` (macOS'ta hazır gelir)

> Build, host mimarisi için derler: Apple Silicon'da arm64, Intel'de x86_64 (çapraz
> derleme yapılmaz; gömülecek Java ve jpackage host mimarisiyle eşleşir).

## Kullanım

```bash
make jdk           # gömülecek Java 11 yoksa kur (host mimarisi)
make jpackage-jdk  # jpackage'lı 17+ JDK yoksa kur
make all           # build/Uyap Doküman Editörü.app üret
ICONS=1 make all            # + modern Material/Retina ikonlar (yayın sürümleri böyle)
SKIN=1 ICONS=1 make all    # + modern düz görünüm (SKIN=1 opsiyonel)
```

### Diğer hedefler

```
make help        # tüm hedefler
make check-deps  # araç + Java 11 (host mimarisi) + jpackage denetimi
make download    # paketi indir + kaynağı aç
make deps        # sqlite-jdbc indir + host mimarisi dylib doğrula
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

## CI build (isteğe bağlı)

`.github/workflows/release.yml` (elle tetiklenir): macOS arm64 runner'da `make all`
çalıştırır ve `.app`'i imzayı bozmadan zip'ler. Sürüm, UDE sürümünden türetilir:
`<ude_surumu>_<N>` (ör. `5.4.17_1`). Bu yalnızca derlemenin doğrulanması/kişisel kullanım
içindir; bu depo **hazır paket dağıtmaz** (bkz. en üstteki not).

> Not: CI yalnızca arm64 runner kullanır; Intel (x86_64) build'i yerelde bir Intel Mac'te
> (`bash kur.sh` veya `make all`) üretilir. İstenirse `macos-13` Intel runner ile ayrı bir
> CI işi eklenebilir.

---

## Teşekkür

Bu çalışmaya ilham veren ve sorunun çözüm yolunu ortaya koyan
[**tosbaha**](https://github.com/tosbaha) kullanıcısına ([tosbaha/uyap-arm](https://github.com/tosbaha/uyap-arm))
teşekkürler.

## Lisans

Bu depodaki paketleme betikleri, yamalar ve belgeler [MIT Lisansı](LICENSE) ile sunulur.
Uyap Doküman Editörü'nün kendisi UYAP / T.C. Adalet Bakanlığı'na ait olup bu lisansın
kapsamı dışındadır; ilgili kullanım koşullarına tabidir.
