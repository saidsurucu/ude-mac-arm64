#!/bin/bash
#
# build.sh — UDE (Uyap Doküman Editörü) için native macOS .app üretir.
# Mimari otomatik algılanır: Apple Silicon (arm64) ve Intel (x86_64). Aşağıdaki
# açıklamalar arm64'ün özgün gerekçesini anlatır; Intel'de mimari-bağımsız tüm
# düzeltmeler (Java 11/keskin metin, ikonlar, kısayollar, native pencereler, e-imza,
# PDF Türkçe) aynen geçerlidir.
#
# Yaklaşım: jpackage ile arm64 **Java 11** runtime'ı GÖMÜLEREK paketlenir.
#   - Java 11 = otomatik HiDPI (JEP 263) → Retina'da KESKİN metin.
#   - Java 8'de arm64 Swing Retina render etmiyordu (bulanık); Java 11 çözer.
#   - UDE Java 8 bytecode'u Java 11'de çalışır (WebLaF illegal-access uyarıyla geçer).
#   - UDE'nin kullandığı eski `com.apple.eawt` API'si Java 11'de kaldırıldı → küçük bir
#     "eawt-shim" (scripts/eawt-shim) ile sağlanır; dosya-açma Java 11'in native
#     dispatcher'ına reflection ile köprülenir → çift-tık ile .udf açma korunur.
#
# Engeller ve çözümleri (detay: claudedocs/ARM-derleme-plani.md):
#   1) x64 launcher        -> jpackage native arm64 launcher (in-process JVM)
#   2) Java 8 Retina yok    -> Java 11 runtime gömülür
#   3) com.apple.eawt yok   -> eawt-shim (--patch-module java.desktop)
#   4) sqlite-jdbc 3.7.2    -> 3.46.x (arm64 dylib)
#   5) e-imza kart görünmüyor -> javax.smartcardio macOS'ta PCSC native'i varsayılan
#      yolda bulamıyor; jpackage'a -Dsun.security.smartcardio.library=<PCSC.framework>
#      java-option'ı gömülür (cfg'ye yansır, ad-hoc imzayla tutarlı).
#   + ASCII executable adı (codesign Türkçe karakterle bozuluyor) + ad-hoc imza
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD="$ROOT/build"
VENDOR="$ROOT/vendor"
DOWNLOADS="$ROOT/downloads"
SRC_APP_DIR="$BUILD/_src"
SHIM_SRC="$SCRIPT_DIR/eawt-shim"
ICONS_SRC="$SCRIPT_DIR/icons"
TEXTKEYS_SRC="$SCRIPT_DIR/macos-textkeys"
ZOOM_SRC="$SCRIPT_DIR/macos-zoom"
FOP_SRC="$SCRIPT_DIR/macos-fop"
FD_SRC="$SCRIPT_DIR/macos-filedialog"   # native macOS dosya pencereleri yaması
IMG_SRC="$SCRIPT_DIR/macos-imagefull"   # satır-içi imaj tam-çözünürlük (bulanıklık) yaması
SKIN_SRC="$SCRIPT_DIR/skin"             # modern düz skin + Flamingo şerit paint yaması
PASTE_SRC="$SCRIPT_DIR/macos-pasteimage" # panodan imaj yapıştırma (macOS cast) yaması
PASTERICH_SRC="$SCRIPT_DIR/macos-pasterich" # harici stilli yapıştırma (Word/tarayıcı/PDF→UDE) yaması
RESIZE_SRC="$SCRIPT_DIR/macos-imgresize" # fare ile imaj boyutlandırma yaması
LIVETOGGLE_SRC="$SCRIPT_DIR/macos-livetoggle" # otomatik düzeltme seçenekleri anında etkin
FOP_SUP="/System/Library/Fonts/Supplemental"   # macOS Arial/Times New Roman (tam Unicode)
ICONS="${ICONS:-1}"           # 1=açık (varsayılan; modern ikon override + HiDPI yükleyici yaması) | 0=kapalı
FOPFONTS="${FOPFONTS:-1}"     # 1=açık (varsayılan; PDF Türkçe harf düzeltmesi) | 0=kapalı
FILEDIALOG="${FILEDIALOG:-1}" # 1=açık (varsayılan; native macOS dosya pencereleri) | 0=kapalı
IMGFULL="${IMGFULL:-1}"  # 1=açık (varsayılan; satır-içi imaj tam-çözünürlük, bulanıklık yaması) | 0=kapalı
PASTEIMG="${PASTEIMG:-1}" # 1=açık (varsayılan; panodan imaj yapıştırma macOS düzeltmesi) | 0=kapalı
IMGRESIZE="${IMGRESIZE:-1}" # 1=açık (varsayılan; imajı köşe tutamaçlarıyla boyutlandırma) | 0=kapalı
SKIN="${SKIN:-1}"       # 1=açık (varsayılan; modern düz Substance skin + Flamingo şerit + font + teal arka plan nötr gri) | 0=kapalı
LIVETOGGLE="${LIVETOGGLE:-1}" # 1=açık (varsayılan; Otomatik Büyük Harf vb. toggle'lar restart'sız etkin) | 0=kapalı
TEXTREPLACE="${TEXTREPLACE:-1}" # 1=açık (varsayılan; macOS Metin Değiştirme kısayolları UDE'de) | 0=kapalı
PASTERICH="${PASTERICH:-1}" # 1=açık (varsayılan; harici stilli yapıştırma — Word/tarayıcı/PDF→UDE) | 0=kapalı

APP_NAME="Uyap Doküman Editörü"     # görünen ad
APP="$BUILD/$APP_NAME.app"
ASCII_NAME="UyapDokumanEditoru"     # executable/CFBundleExecutable (ASCII şart, codesign)
BUNDLE_ID="tr.gov.uyap.editor"
MAIN_CLASS="tr.com.havelsan.uyap.system.editor.common.WPAppManager"

# UDE paketi SABİT bir URL değildir: her sürümde dosya adı değişir (tarih kodlu).
# Bu yüzden URL'yi resmî indirme sayfasından dinamik çözeriz (resolve_ude_url).
# UDE_URL elle verilirse (env) o kullanılır, sayfa hiç sorgulanmaz.
UDE_URL="${UDE_URL:-}"
UDE_ZIP="${UDE_ZIP:-$DOWNLOADS/ude.zip}"
# MAC paketinin (uyapdokumaneditoru*.zip) listelendiği resmî sayfa.
UDE_DOWNLOAD_PAGE="${UDE_DOWNLOAD_PAGE:-https://www.uyap.gov.tr/Uyap-Editor}"
# UDE'nin kendisinin sürüm kontrolü yaptığı uç nokta. Yanıtın
# 'Content-Disposition: filename="X.Y.Z.release"' header'ından güncel sürümü verir.
UDE_UPDATE_ENDPOINT="${UDE_UPDATE_ENDPOINT:-http://editor.uyap.gov.tr/editorUpdaterYeni}"

SQLITE_VER="${SQLITE_VER:-3.46.1.3}"
SQLITE_JAR="$VENDOR/sqlite-jdbc-$SQLITE_VER.jar"
SQLITE_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$SQLITE_VER/sqlite-jdbc-$SQLITE_VER.jar"

# Hedef mimari otomatik algılanır (Apple Silicon=arm64, Intel=x86_64). Build çalıştığı
# Mac'in mimarisi için üretir. JH_ARCH=java_home -a, AZUL_ARCH=Azul metadata arch,
# SQLITE_ARCH=jar içindeki Mac/<arch>/libsqlitejdbc.dylib alt yolu. (die() henüz
# tanımlı değil; bu yüzden hatayı inline basıyoruz.)
HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
	arm64)  AZUL_ARCH=aarch64; JH_ARCH=arm64;  SQLITE_ARCH=aarch64 ;;
	x86_64) AZUL_ARCH=x64;     JH_ARCH=x86_64; SQLITE_ARCH=x86_64 ;;
	*) printf '\033[31m✗ Desteklenmeyen mimari: %s\033[0m\n' "$HOST_ARCH" >&2; exit 1 ;;
esac

# Gömülecek Java 11 (runtime). 'jdk' hedefi Azul Zulu 11'i kurar.
JDK11_DEST="$HOME/Library/Java/JavaVirtualMachines/zulu-11-$JH_ARCH.jdk"
# jpackage + shim derlemesi için 17+ JDK. 'jpackage-jdk' Zulu 21 kurar.
JDK21_DEST="$HOME/Library/Java/JavaVirtualMachines/zulu-21-$JH_ARCH.jdk"

# Ağ indirmeleri için sağlamlaştırma. UYAP/adalet devlet sunucuları ve bazı CDN'ler
# arada HTTP/2 akışını yarıda kapatıyor ("HTTP/2 stream not closed cleanly:
# INTERNAL_ERROR"). --http1.1 bu hatayı kökten engeller; --retry geçici kesintileri
# toparlar. Tüm curl indirmelerinde "${CURL_NET[@]}" kullanılır.
CURL_NET=(--http1.1 --retry 5 --retry-delay 2 --connect-timeout 30)

c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; }
die()    { c_err "$*"; exit 1; }

# Gerçekten istenen major sürüm mü (java_home yanlış sürüm döndürebiliyor)
jhome() {  # $1=major  $2=hedef .jdk
	if [ -x "$2/Contents/Home/bin/java" ]; then echo "$2/Contents/Home"; return 0; fi
	local h; h="$(/usr/libexec/java_home -v "$1" -a "$JH_ARCH" 2>/dev/null || true)"
	if [ -n "$h" ] && "$h/bin/java" -version 2>&1 | grep -q "version \"$1"; then echo "$h"; fi
	return 0
}
jdk11_home() { jhome 11 "$JDK11_DEST"; }

find_jpackage() {
	local v jh
	for v in 25 24 23 22 21 20 19 18 17; do
		jh="$(/usr/libexec/java_home -v "$v" -a "$JH_ARCH" 2>/dev/null || true)"
		[ -n "$jh" ] && [ -x "$jh/bin/jpackage" ] && { echo "$jh/bin/jpackage"; return 0; }
	done
	local home
	for home in $(/usr/libexec/java_home -V 2>&1 | grep -oE '/[^ ]+/Contents/Home' | sort -u); do
		[ -x "$home/bin/jpackage" ] && { echo "$home/bin/jpackage"; return 0; }
	done
	return 1
}
javac17() {  # jpackage JDK'sının javac'ı (shim'i --release 11 derler)
	local jp; jp="$(find_jpackage)" || return 1
	echo "$(dirname "$jp")/javac"
}
java17() {  # jpackage JDK'sının java'sı (build-time patcher'ları çalıştırır)
	local jp; jp="$(find_jpackage)" || return 1
	echo "$(dirname "$jp")/java"
}
icon_deps() {  # Javassist (build-time ikon yükleyici yaması için)
	mkdir -p "$SCRIPT_DIR/lib"
	local jvs="$SCRIPT_DIR/lib/javassist-3.30.2-GA.jar"
	[ -s "$jvs" ] || curl -fsSL "${CURL_NET[@]}" -o "$jvs" https://repo1.maven.org/maven2/org/javassist/javassist/3.30.2-GA/javassist-3.30.2-GA.jar || die "javassist indirilemedi."
	echo "$jvs"
}

install_zulu() {  # $1=java_version  $2=hedef .jdk
	c_info "Azul Zulu $1 ($AZUL_ARCH) indiriliyor…"
	local url
	url="$(curl -s "${CURL_NET[@]}" "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$1&os=macos&arch=$AZUL_ARCH&archive_type=tar.gz&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga&availability_types=CA&page=1&page_size=1" \
		| /usr/bin/python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["download_url"])')"
	[ -n "$url" ] || die "Zulu $1 URL'si alınamadı."
	mkdir -p "$DOWNLOADS"; local tmp="$DOWNLOADS/zulu$1.tgz"
	curl -fSL "${CURL_NET[@]}" -o "$tmp" "$url"
	gzip -t "$tmp" 2>/dev/null || die "Zulu $1 indirme bozuk."
	local stage; stage="$(mktemp -d)"; tar xzf "$tmp" -C "$stage"
	local b; b="$(find "$stage" -maxdepth 1 -type d -name 'zulu*' | head -1)"
	[ -n "$b" ] || die "Zulu $1 arşiv yapısı farklı."
	mkdir -p "$(dirname "$2")"; rm -rf "$2"; mv "$b" "$2"; rm -rf "$stage"
}

# ----- Hedefler -----

check_deps() {
	c_info "Ön koşullar denetleniyor…"
	local t
	for t in curl unzip zip codesign plutil; do
		command -v "$t" >/dev/null 2>&1 || die "Gerekli araç yok: $t"
	done
	c_ok "Araçlar mevcut"
	local ok=0
	[ -n "$(jdk11_home)" ] && c_ok "$JH_ARCH Java 11 (runtime): $(jdk11_home)" || { c_warn "$JH_ARCH Java 11 YOK → scripts/build.sh jdk"; ok=1; }
	if jp="$(find_jpackage)"; then c_ok "jpackage: $jp"; else c_warn "jpackage'lı 17+ JDK YOK → scripts/build.sh jpackage-jdk"; ok=1; fi
	return $ok
}

jdk() {
	[ -n "$(jdk11_home)" ] && { c_ok "$JH_ARCH Java 11 zaten kurulu."; return 0; }
	install_zulu 11 "$JDK11_DEST"
	[ -n "$(jdk11_home)" ] && c_ok "Kuruldu: $JDK11_DEST" || die "Java 11 kurulum sonrası görünmüyor."
}

jpackage_jdk() {
	find_jpackage >/dev/null 2>&1 && { c_ok "jpackage zaten var."; return 0; }
	install_zulu 21 "$JDK21_DEST"
	find_jpackage >/dev/null 2>&1 && c_ok "jpackage hazır." || die "jpackage bulunamadı."
}

# UDE'nin kendi sürüm kontrol uç noktasından güncel sürümü okur (ör. 5.4.17).
# Sadece bilgi/çapraz-doğrulama amaçlıdır; başarısız olursa boş döner (build durmaz).
latest_ude_version() {
	curl -fsSL -m 20 -I "$UDE_UPDATE_ENDPOINT" 2>/dev/null \
		| tr -d '\r' \
		| sed -n 's/.*filename="\{0,1\}\([0-9][0-9.]*\)\.release"\{0,1\}.*/\1/p' \
		| head -1
}

# Resmî indirme sayfasından güncel MAC paketinin (uyapdokumaneditoru*.zip) URL'sini çözer.
resolve_ude_url() {
	[ -n "$UDE_URL" ] && { echo "$UDE_URL"; return 0; }
	c_info "Güncel MAC paketi çözülüyor: $UDE_DOWNLOAD_PAGE" >&2
	local href
	href="$(curl -fsSL "${CURL_NET[@]}" -m 30 "$UDE_DOWNLOAD_PAGE" 2>/dev/null \
		| grep -aoE '(https?:)?//rayp\.adalet\.gov\.tr/[^"'"'"' >]*uyapdokumaneditoru[^"'"'"' >]*\.zip' \
		| head -1)"
	[ -n "$href" ] || die "Güncel MAC paketi indirme sayfasında bulunamadı (sayfa yapısı değişmiş olabilir). UDE_URL ile elle verebilirsiniz."
	case "$href" in
		//*)  href="https:$href" ;;
		http*) ;;
	esac
	echo "$href"
}

download() {
	c_info "Kaynak paket hazırlanıyor…"
	mkdir -p "$DOWNLOADS" "$BUILD"
	if [ -s "$UDE_ZIP" ]; then
		c_ok "Önbellekten: $UDE_ZIP ($(du -h "$UDE_ZIP" | cut -f1))"
	else
		local lv; lv="$(latest_ude_version)"
		[ -n "$lv" ] && c_info "UDE'nin bildirdiği güncel sürüm: $lv"
		UDE_URL="$(resolve_ude_url)"
		c_info "İndiriliyor: $UDE_URL"
		# Önce .part'a indir, zip bütünlüğünü doğrula, sonra taşı. Böylece HTTP/2 akış
		# kesilmesi yarım bir dosya bırakırsa, sonraki çalıştırma onu "önbellek" sanıp
		# bozuk zip ile devam etmez.
		local tmp="$UDE_ZIP.part"; rm -f "$tmp"
		curl -fL "${CURL_NET[@]}" -o "$tmp" "$UDE_URL" \
			|| die "MAC paketi indirilemedi (ağ/sunucu hatası olabilir). Komutu tekrar çalıştırın."
		unzip -tq "$tmp" >/dev/null 2>&1 \
			|| { rm -f "$tmp"; die "İndirilen paket bozuk (indirme yarıda kalmış olabilir). Komutu tekrar çalıştırın."; }
		mv -f "$tmp" "$UDE_ZIP"
	fi
	rm -rf "$SRC_APP_DIR"; mkdir -p "$SRC_APP_DIR"
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -q "$UDE_ZIP" )
	local src; src="$(find "$stage" -maxdepth 1 -type d -name '*.app' | head -1)"
	[ -n "$src" ] || die "Zip içinde .app yok."
	cp -R "$src" "$SRC_APP_DIR/app"; rm -rf "$stage"
	find "$SRC_APP_DIR" -name '._*' -delete 2>/dev/null || true
	[ -s "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ] || die "editor-app.jar yok."
	c_ok "Kaynak açıldı."
}

deps() {
	c_info "sqlite-jdbc $SQLITE_VER hazırlanıyor…"
	mkdir -p "$VENDOR"
	[ -s "$SQLITE_JAR" ] || curl -fsSL "${CURL_NET[@]}" -o "$SQLITE_JAR" "$SQLITE_URL"
	unzip -l "$SQLITE_JAR" | grep "Mac/$SQLITE_ARCH/libsqlitejdbc.dylib" >/dev/null || die "$SQLITE_ARCH dylib yok."
	c_ok "sqlite-jdbc hazır"
}

shim() {
	c_info "eawt-shim derleniyor (Java 11 com.apple.eawt yerine geçer)…"
	local jc; jc="$(javac17)" || die "Derleyici (jpackage JDK) yok → scripts/build.sh jpackage-jdk"
	rm -rf "$BUILD/_shim"; mkdir -p "$BUILD/_shim"
	"$jc" --release 11 -d "$BUILD/_shim" $(find "$SHIM_SRC" -name '*.java') \
		|| die "eawt-shim derlenemedi."
	c_ok "eawt-shim derlendi ($(find "$BUILD/_shim" -name '*.class' | wc -l | tr -d ' ') sınıf)"
}

textkeys() {
	c_info "macOS metin kısayolları javaagent'ı derleniyor (Option+Delete vb.)…"
	local jc; jc="$(javac17)" || die "Derleyici (jpackage JDK) yok → scripts/build.sh jpackage-jdk"
	rm -rf "$BUILD/_textkeys"; mkdir -p "$BUILD/_textkeys"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_textkeys" $(find "$TEXTKEYS_SRC" -name '*.java') \
		|| die "macos-textkeys derlenemedi."
	c_ok "macos-textkeys derlendi ($(find "$BUILD/_textkeys" -name '*.class' | wc -l | tr -d ' ') sınıf)"
	# Native diyalog (NSSavePanel) pano kısayolları: dosya adı kutusunda Cmd+V.
	# Panel AWT'nin dışında → Java'dan çözülemez; agent bu dylib'i System.load eder.
	c_info "native diyalog kısayolları dylib'i derleniyor (NSSavePanel Cmd+V)…"
	command -v clang >/dev/null 2>&1 || die "clang yok (xcode-select --install)."
	clang -dynamiclib -framework Cocoa -o "$BUILD/_textkeys/libnativedialogkeys.dylib" \
		"$TEXTKEYS_SRC/native/NativeDialogKeys.m" || die "NativeDialogKeys derlenemedi."
	c_ok "libnativedialogkeys.dylib derlendi"
}

zoom() {
	c_info "macOS trackpad zoom javaagent'ı derleniyor (Cmd+iki parmak)…"
	local jc; jc="$(javac17)" || die "Derleyici (jpackage JDK) yok → scripts/build.sh jpackage-jdk"
	rm -rf "$BUILD/_zoom"; mkdir -p "$BUILD/_zoom"
	"$jc" --release 11 -d "$BUILD/_zoom" $(find "$ZOOM_SRC" -name '*.java') \
		|| die "macos-zoom derlenemedi."
	c_ok "macos-zoom derlendi ($(find "$BUILD/_zoom" -name '*.class' | wc -l | tr -d ' ') sınıf)"
}

lookagent() {  # SKIN=1 görünüm agent'ı: bütünleşik başlık çubuğu + durum çubuğu temizliği
	[ "$SKIN" = "1" ] || { c_info "[lookagent] SKIN=0, atlandı."; rm -rf "$BUILD/_lookagent"; return 0; }
	c_info "macOS görünüm javaagent'ı derleniyor (bütünleşik başlık + WebMemoryBar)…"
	local jc; jc="$(javac17)" || die "Derleyici (jpackage JDK) yok → scripts/build.sh jpackage-jdk"
	rm -rf "$BUILD/_lookagent"; mkdir -p "$BUILD/_lookagent"
	"$jc" --release 11 -d "$BUILD/_lookagent" "$SKIN_SRC/agent/macoslook/MacLook.java" \
		|| die "macoslook derlenemedi."
	c_ok "macoslook derlendi."
}

apply_icons() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$ICONS" = "1" ] || return 0
	c_info "[icons] modern ikon override + HiDPI yükleyici yaması…"
	# 1) override asset'leri jar'a enjekte et (UDE resource yoluyla)
	if [ -d "$ICONS_SRC/overrides" ] && [ -n "$(find "$ICONS_SRC/overrides" -type f ! -name '.*' 2>/dev/null)" ]; then
		( cd "$ICONS_SRC/overrides" && zip -q -r "$JAR" . -x '.*' )
		c_ok "[icons] override asset'leri enjekte edildi ($(find "$ICONS_SRC/overrides" -name '*.png' | wc -l | tr -d ' ') png)."
	fi
	# 2) Utils.b(String) yükleyicisini multi-resolution'a çevir (Javassist)
	local jvs jc jr; jvs="$(icon_deps)"; jc="$(javac17)" || die "[icons] javac yok"; jr="$(java17)" || die "[icons] java yok"
	rm -rf "$BUILD/_iconpatch"; mkdir -p "$BUILD/_iconpatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_iconpatch" "$ICONS_SRC/IconLoaderPatch.java" || die "[icons] patcher derlenemedi."
	"$jr" -cp "$BUILD/_iconpatch:$jvs" IconLoaderPatch "$JAR" "$BUILD/_iconpatch/out" || die "[icons] patcher çalışmadı."
	( cd "$BUILD/_iconpatch/out" && zip -q -r "$JAR" . -x '.*' )
	c_ok "[icons] yamalar uygulandı (Utils.b multi-res + disabled soluklaştırma)."
}

apply_fop_fonts() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$FOPFONTS" = "1" ] || return 0
	# İdempotans: zaten yamalıysa tekrar sarma (b/a newInstance çift sarılmasın)
	unzip -l "$JAR" 2>/dev/null | grep -q 'macosfop/FopFonts.class' && { c_ok "[fop] zaten yamalı, atlandı."; return 0; }
	c_info "[fop] PDF Türkçe harf yaması (FOP setUserConfig + gömülü Arial/Times)…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[fop] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[fop] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (ikon yamasıyla ortak)

	# 1) macOS sistem fontlarından FOP metrik XML'leri üret (TTFReader jar içinde)
	local fdir="$BUILD/_fopfonts"; rm -rf "$fdir"; mkdir -p "$fdir"
	#       çıktı.xml                 Supplemental TTF adı
	local map="
arial.xml:Arial.ttf
arial-bold.xml:Arial Bold.ttf
arial-italic.xml:Arial Italic.ttf
arial-bolditalic.xml:Arial Bold Italic.ttf
times.xml:Times New Roman.ttf
times-bold.xml:Times New Roman Bold.ttf
times-italic.xml:Times New Roman Italic.ttf
times-bolditalic.xml:Times New Roman Bold Italic.ttf"
	local line out ttf
	while IFS= read -r line; do
		[ -z "$line" ] && continue
		out="${line%%:*}"; ttf="$FOP_SUP/${line#*:}"
		[ -f "$ttf" ] || { c_warn "[fop] sistem fontu yok: $ttf (atlandı)"; continue; }
		"$jr" -cp "$JAR" org.apache.fop.fonts.apps.TTFReader "$ttf" "$fdir/$out" >/dev/null 2>&1 \
			|| c_warn "[fop] metrik üretilemedi: $out"
	done <<< "$map"
	if [ -z "$(ls "$fdir"/*.xml 2>/dev/null)" ]; then
		# Build makinesinde sistem fontları yoksa (bazı CI runner'ları) repodaki
		# hazır metrikleri kullan. Gömme yine çalışma zamanında KULLANICININ
		# /System/Library/Fonts/Supplemental fontlarıyla yapılır; metrik yalnız genişlik içindir.
		if [ -n "$(ls "$FOP_SRC/fopfonts/"*.xml 2>/dev/null)" ]; then
			cp "$FOP_SRC/fopfonts/"*.xml "$fdir/" && c_warn "[fop] sistem fontu üretilemedi; repodaki hazır metrikler kullanıldı."
		else
			c_warn "[fop] metrik yok (ne üretildi ne repoda); yama atlandı."; return 0
		fi
	fi
	c_ok "[fop] $(ls "$fdir"/*.xml | wc -l | tr -d ' ') sistem font metriği hazır."

	# 1b) Gömülü yedek font: Liberation Serif/Sans (OFL; Times/Arial metrik-uyumlu, tam Türkçe).
	#     Sistemde Times/Arial OLMAYAN Mac'lerde kullanılır (hibrit). TTF'ler pakete konur,
	#     metrikleri buradan üretilir → metrik gömülen TTF ile birebir eşleşir.
	local libmap="
LiberationSerif-Regular.ttf:libserif.xml
LiberationSerif-Bold.ttf:libserif-bold.xml
LiberationSerif-Italic.ttf:libserif-italic.xml
LiberationSerif-BoldItalic.ttf:libserif-bolditalic.xml
LiberationSans-Regular.ttf:libsans.xml
LiberationSans-Bold.ttf:libsans-bold.xml
LiberationSans-Italic.ttf:libsans-italic.xml
LiberationSans-BoldItalic.ttf:libsans-bolditalic.xml"
	local lttf lout lsrc
	while IFS= read -r line; do
		[ -z "$line" ] && continue
		lttf="${line%%:*}"; lout="${line#*:}"; lsrc="$FOP_SRC/fonts/$lttf"
		[ -f "$lsrc" ] || { c_warn "[fop] gömülü font yok: $lsrc (atlandı)"; continue; }
		cp "$lsrc" "$fdir/$lttf"
		"$jr" -cp "$JAR" org.apache.fop.fonts.apps.TTFReader "$lsrc" "$fdir/$lout" >/dev/null 2>&1 \
			|| c_warn "[fop] Liberation metriği üretilemedi: $lout"
	done <<< "$libmap"
	c_ok "[fop] $(ls "$fdir"/Liberation*.ttf 2>/dev/null | wc -l | tr -d ' ') gömülü yedek font (Liberation) pakete eklendi."

	# 2) Runtime yardımcı sınıfları (macosfop.FopFonts + macosfop.ITextFonts) derle + jar'a enjekte et
	#    (FopFactory/BaseFont için derleme classpath'i = JAR)
	rm -rf "$BUILD/_fophelper"; mkdir -p "$BUILD/_fophelper"
	"$jc" --release 11 -cp "$JAR" -d "$BUILD/_fophelper" \
		"$FOP_SRC/macosfop/FopFonts.java" "$FOP_SRC/macosfop/ITextFonts.java" "$FOP_SRC/macosfop/PageFix.java" \
		|| { c_warn "[fop] yardımcı sınıflar derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_fophelper" && zip -q -r "$JAR" macosfop )

	# 3) Sürücüleri Javassist ile yamala: FOP (b/a) newInstance→FopFonts.apply ve
	#    iText FontMapper (b/c) awtToPdf→ITextFonts.map (yardımcılar 2. adımda eklendi)
	rm -rf "$BUILD/_foppatch"; mkdir -p "$BUILD/_foppatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_foppatch" "$FOP_SRC/FopConfigPatch.java" \
		|| { c_warn "[fop] FopConfigPatch derlenemedi; yama atlandı."; return 0; }
	"$jr" -cp "$BUILD/_foppatch:$jvs" FopConfigPatch "$JAR" "$BUILD/_foppatch/out" \
		|| die "[fop] b/a yamalanamadı (UDE sürümü değişmiş olabilir)."
	( cd "$BUILD/_foppatch/out" && zip -q -r "$JAR" tr )
	c_ok "[fop] PDF Türkçe harf yaması uygulandı (metrikler package'da pakete konur)."
}

apply_filedialog() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$FILEDIALOG" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla (çağrı yeniden yazımı tek seferlik)
	if unzip -l "$JAR" 2>/dev/null | grep 'macosfiledialog/MacFileDialog.class' >/dev/null 2>&1; then
		c_ok "[filedialog] zaten yamalı, atlandı."; return 0
	fi
	c_info "[filedialog] native macOS dosya pencereleri yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[filedialog] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[filedialog] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (ikon/fop yamasıyla ortak)
	# 1) köprü sınıfını jar'a karşı derle + enjekte et (patcher'dan ÖNCE)
	rm -rf "$BUILD/_fdhelper"; mkdir -p "$BUILD/_fdhelper"
	"$jc" --release 11 -cp "$JAR" -d "$BUILD/_fdhelper" "$FD_SRC/macosfiledialog/MacFileDialog.java" \
		|| { c_warn "[filedialog] köprü sınıfı derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_fdhelper" && zip -q -r "$JAR" macosfiledialog )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_fdpatch"; mkdir -p "$BUILD/_fdpatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_fdpatch" "$FD_SRC/FileDialogPatch.java" \
		|| { c_warn "[filedialog] FileDialogPatch derlenemedi; yama atlandı."; return 0; }
	"$jr" -cp "$BUILD/_fdpatch:$jvs" FileDialogPatch "$JAR" "$BUILD/_fdpatch/out" \
		|| die "[filedialog] çağrılar yamalanamadı (UDE sürümü değişmiş olabilir)."
	( cd "$BUILD/_fdpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[filedialog] native dosya pencereleri yaması uygulandı."
}

apply_imagefull() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$IMGFULL" = "1" ] || return 0
	c_info "[imagefull] satır-içi imaj tam-çözünürlük yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[imagefull] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[imagefull] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (ikon/fop/filedialog yamasıyla ortak)
	rm -rf "$BUILD/_imgpatch"; mkdir -p "$BUILD/_imgpatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_imgpatch" "$IMG_SRC/ImageInsertPatch.java" \
		|| { c_warn "[imagefull] ImageInsertPatch derlenemedi; yama atlandı."; return 0; }
	"$jr" -cp "$BUILD/_imgpatch:$jvs" ImageInsertPatch "$JAR" "$BUILD/_imgpatch/out" \
		|| die "[imagefull] çekirdek yama uygulanamadı (UDE sürümü değişmiş olabilir)."
	( cd "$BUILD/_imgpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[imagefull] tam-çözünürlük yaması uygulandı."
}

apply_pasteimage() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$PASTEIMG" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla (cast yeniden yazımı tek seferlik).
	# grep -q DEĞİL (SIGPIPE/pipefail tuzağı): grep tüm girdiyi okuyup >/dev/null'a yazar.
	if unzip -l "$JAR" 2>/dev/null | grep 'macospasteimage/Conv.class' >/dev/null 2>&1; then
		c_ok "[pasteimage] zaten yamalı, atlandı."; return 0
	fi
	c_info "[pasteimage] panodan imaj yapıştırma (macOS Retina cast) yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[pasteimage] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[pasteimage] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) dönüştürücü helper'ı derle + jar'a enjekte et (patcher'dan ÖNCE; replace derlemesi
	#    Conv'u jar classpath'inden çözer)
	rm -rf "$BUILD/_pastehelper"; mkdir -p "$BUILD/_pastehelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_pastehelper" "$PASTE_SRC/macospasteimage/Conv.java" \
		|| { c_warn "[pasteimage] Conv derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_pastehelper" && zip -q -r "$JAR" macospasteimage )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_pastepatch"; mkdir -p "$BUILD/_pastepatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_pastepatch" "$PASTE_SRC/PasteImagePatch.java" \
		|| { c_warn "[pasteimage] PasteImagePatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_pastepatch:$jvs" PasteImagePatch "$JAR" "$BUILD/_pastepatch/out"; then
		# Yarım-yama bırakma: helper'ı geri çıkar ki idempotans kontrolü yanılmasın.
		zip -q -d "$JAR" 'macospasteimage/*' >/dev/null 2>&1 || true
		c_warn "[pasteimage] cast yaması uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_pastepatch/out" && zip -q -r "$JAR" tr )
	c_ok "[pasteimage] panodan imaj yapıştırma yaması uygulandı."
}

apply_pasterich() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$PASTERICH" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla (grep -q DEĞİL — SIGPIPE/pipefail tuzağı).
	if unzip -l "$JAR" 2>/dev/null | grep 'macospasterich/RichPaste.class' >/dev/null 2>&1; then
		c_ok "[pasterich] zaten yamalı, atlandı."; return 0
	fi
	c_info "[pasterich] harici stilli yapıştırma yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[pasterich] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[pasterich] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) Saf-Java HTML→content.xml dönüştürücü sınıflarını derle + jar'a enjekte et
	#    (patcher'dan ÖNCE; insertBefore derlemesi RichPaste.docOf/fromClipboardHtml'i
	#    jar classpath'inden çözer). Tüm macospasterich/*.java derlenir — harici bağımlılık yok.
	rm -rf "$BUILD/_prhelper"; mkdir -p "$BUILD/_prhelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_prhelper" \
		"$PASTERICH_SRC"/macospasterich/*.java \
		|| { c_warn "[pasterich] dönüştürücü derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_prhelper" && zip -q -r "$JAR" macospasterich )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_prpatch"; mkdir -p "$BUILD/_prpatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_prpatch" "$PASTERICH_SRC/PasteRichPatch.java" \
		|| { c_warn "[pasterich] PasteRichPatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_prpatch:$jvs" PasteRichPatch "$JAR" "$BUILD/_prpatch/out"; then
		zip -q -d "$JAR" 'macospasterich/*' >/dev/null 2>&1 || true
		c_warn "[pasterich] kanca uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_prpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[pasterich] harici stilli yapıştırma yaması uygulandı."
}

apply_imgresize() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$IMGRESIZE" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla.
	# grep -q DEĞİL (SIGPIPE/pipefail tuzağı): grep tüm girdiyi okuyup >/dev/null'a yazar.
	if unzip -l "$JAR" 2>/dev/null | grep 'macosimgresize/ImageResizeController.class' >/dev/null 2>&1; then
		c_ok "[imgresize] zaten yamalı, atlandı."; return 0
	fi
	c_info "[imgresize] fare ile imaj boyutlandırma yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[imgresize] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[imgresize] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) denetçi helper'ı derle + jar'a enjekte et (patcher'dan ÖNCE; intercept
	#    çağrısı derlemesi denetçiyi jar classpath'inden çözer)
	rm -rf "$BUILD/_resizehelper"; mkdir -p "$BUILD/_resizehelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_resizehelper" "$RESIZE_SRC/macosimgresize/ImageResizeController.java" \
		|| { c_warn "[imgresize] ImageResizeController derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_resizehelper" && zip -q -r "$JAR" macosimgresize )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_resizepatch"; mkdir -p "$BUILD/_resizepatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_resizepatch" "$RESIZE_SRC/ImageResizePatch.java" \
		|| { c_warn "[imgresize] ImageResizePatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_resizepatch:$jvs" ImageResizePatch "$JAR" "$BUILD/_resizepatch/out"; then
		# Yarım-yama bırakma: helper'ı geri çıkar ki idempotans kontrolü yanılmasın.
		zip -q -d "$JAR" 'macosimgresize/*' >/dev/null 2>&1 || true
		c_warn "[imgresize] hj yaması uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_resizepatch/out" && zip -q -r "$JAR" tr )
	c_ok "[imgresize] fare ile imaj boyutlandırma yaması uygulandı."
}

apply_livetoggle() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$LIVETOGGLE" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla.
	# grep -q DEĞİL (SIGPIPE/pipefail tuzağı): grep tüm girdiyi okuyup >/dev/null'a yazar.
	if unzip -l "$JAR" 2>/dev/null | grep 'macoslivetoggle/LiveToggle.class' >/dev/null 2>&1; then
		c_ok "[livetoggle] zaten yamalı, atlandı."; return 0
	fi
	c_info "[livetoggle] otomatik düzeltme seçenekleri anında-etkin yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[livetoggle] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[livetoggle] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) helper'ı derle + jar'a enjekte et (patcher'dan ÖNCE; insertBefore/After
	#    derlemesi LiveToggle'ı jar classpath'inden çözer)
	rm -rf "$BUILD/_ltghelper"; mkdir -p "$BUILD/_ltghelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_ltghelper" "$LIVETOGGLE_SRC/macoslivetoggle/LiveToggle.java" \
		|| { c_warn "[livetoggle] LiveToggle derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_ltghelper" && zip -q -r "$JAR" macoslivetoggle )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_ltgpatch"; mkdir -p "$BUILD/_ltgpatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_ltgpatch" "$LIVETOGGLE_SRC/LiveTogglePatch.java" \
		|| { c_warn "[livetoggle] LiveTogglePatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_ltgpatch:$jvs" LiveTogglePatch "$JAR" "$BUILD/_ltgpatch/out"; then
		# Yarım-yama bırakma: helper'ı geri çıkar ki idempotans kontrolü yanılmasın.
		zip -q -d "$JAR" 'macoslivetoggle/*' >/dev/null 2>&1 || true
		c_warn "[livetoggle] toggle yaması uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_ltgpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[livetoggle] seçenekler artık anında etkin (restart gerekmez)."
}

apply_skin() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$SKIN" = "1" ] || return 0
	if unzip -l "$JAR" 2>/dev/null | grep 'macosskin/.skin-patched' >/dev/null 2>&1; then
		c_ok "[skin] zaten yamalı, atlandı."; return 0
	fi
	c_info "[skin] modern düz skin + Flamingo şerit yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[skin] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[skin] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) FlatUdeSkin'i jar'a karşı derle + enjekte et (patcher'dan ÖNCE)
	rm -rf "$BUILD/_skinhelper"; mkdir -p "$BUILD/_skinhelper"
	"$jc" --release 11 -cp "$JAR" -d "$BUILD/_skinhelper" \
		"$SKIN_SRC/macosskin/DarkMode.java" "$SKIN_SRC/macosskin/IconDarken.java" \
		"$SKIN_SRC/macosskin/ModeAwareImage.java" \
		"$SKIN_SRC/macosskin/ModeSwitch.java" \
		"$SKIN_SRC/macosskin/FlatUdeSkin.java" "$SKIN_SRC/macosskin/FlatUdeDarkSkin.java" \
		"$SKIN_SRC/macosskin/FlatFontPolicy.java" \
		"$SKIN_SRC/macosskin/WordTooltip.java" \
		"$SKIN_SRC/macosskin/WordCombo.java" \
		"$SKIN_SRC/macosskin/PopupRemap.java" \
		"$SKIN_SRC/macosskin/MenuMarks.java" \
		"$SKIN_SRC/macosskin/WordCheck.java" \
		"$SKIN_SRC/macosskin/WordButton.java" \
		"$SKIN_SRC/macosskin/WordTabs.java" \
		"$SKIN_SRC/macosskin/WordField.java" \
		"$SKIN_SRC/macosskin/FlatEtchedBorder.java" \
		"$SKIN_SRC/macosskin/DarkPage.java" \
		|| { c_warn "[skin] skin helper'ları derlenemedi; yama atlandı."; return 0; }
	# colorschemes resource'larını helper ağacına kopyala
	if [ -f "$SKIN_SRC/macosskin/flatude.colorschemes" ]; then
		cp "$SKIN_SRC/macosskin/flatude.colorschemes" "$BUILD/_skinhelper/macosskin/"
	fi
	if [ -f "$SKIN_SRC/macosskin/flatude-dark.colorschemes" ]; then
		cp "$SKIN_SRC/macosskin/flatude-dark.colorschemes" "$BUILD/_skinhelper/macosskin/"
	fi
	( cd "$BUILD/_skinhelper" && zip -q -r "$JAR" macosskin )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_skinpatch"; mkdir -p "$BUILD/_skinpatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_skinpatch" "$SKIN_SRC/SkinPatch.java" \
		|| { c_warn "[skin] SkinPatch derlenemedi; yama atlandı."; return 0; }
	"$jr" -cp "$BUILD/_skinpatch:$jvs" SkinPatch "$JAR" "$BUILD/_skinpatch/out" \
		|| die "[skin] çekirdek yama uygulanamadı (UDE/Substance sürümü değişmiş olabilir)."
	( cd "$BUILD/_skinpatch/out" && zip -q -r "$JAR" . -x '.*' )
	# başarı işareti: idempotans guard'ı yalnız patcher başarılıysa tetiklenir
	( cd "$BUILD/_skinpatch/out" && mkdir -p macosskin && : > macosskin/.skin-patched && zip -q "$JAR" macosskin/.skin-patched )
	c_ok "[skin] FlatUdeSkin kuruldu (düz painter)."
}

patch_jar() {
	local JAR="$SRC_APP_DIR/app/Contents/Java/editor-app.jar"
	[ -s "$JAR" ] || die "Önce 'download' çalıştır."
	[ -s "$SQLITE_JAR" ] || die "Önce 'deps' çalıştır."
	c_info "editor-app.jar yamalama (sqlite swap + gömülü eawt çıkar)…"
	# sqlite 3.7.2 -> 3.46
	zip -q -d "$JAR" 'org/sqlite/*' 'native/*' 'META-INF/maven/org.xerial/*' >/dev/null 2>&1 || true
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -qo "$SQLITE_JAR" -x 'META-INF/MANIFEST.MF' )
	( cd "$stage" && zip -q -r -X "$JAR" org META-INF sqlite-jdbc.properties )
	rm -rf "$stage"
	# Gömülü (Java 8 native-bağımlı) com.apple.eawt/eio sınıflarını çıkar (shim sağlayacak)
	zip -q -d "$JAR" 'com/apple/eawt/*' 'com/apple/eio/*' >/dev/null 2>&1 || true
	apply_icons "$JAR"
	apply_fop_fonts "$JAR"
	apply_filedialog "$JAR"
	apply_imagefull "$JAR"
	apply_pasteimage "$JAR"
	apply_pasterich "$JAR"
	apply_imgresize "$JAR"
	apply_livetoggle "$JAR"
	apply_skin "$JAR"
	unzip -l "$JAR" | grep "Mac/$SQLITE_ARCH/libsqlitejdbc.dylib" >/dev/null || die "sqlite swap başarısız!"
	unzip -p "$JAR" META-INF/MANIFEST.MF | grep 'WPAppManager' >/dev/null || die "Main-Class kayboldu!"
	c_ok "jar yamalandı (sqlite 3.46 + eawt çıkarıldı)"
}

package() {
	[ -d "$SRC_APP_DIR/app" ] || die "Önce 'download' çalıştır."
	[ -d "$BUILD/_shim" ] || die "Önce 'shim' çalıştır."
	[ -d "$BUILD/_textkeys" ] || die "Önce 'textkeys' çalıştır."
	[ -d "$BUILD/_zoom" ] || die "Önce 'zoom' çalıştır."
	local jp; jp="$(find_jpackage)" || die "jpackage yok → scripts/build.sh jpackage-jdk"
	local rt; rt="$(jdk11_home)"; [ -n "$rt" ] || die "Java 11 yok → scripts/build.sh jdk"
	[ -f "$rt/lib/jli/libjli.dylib" ] || die "Java 11 runtime layout farklı: $rt"

	c_info "jpackage girdisi hazırlanıyor…"
	local JAVA="$SRC_APP_DIR/app/Contents/Java"
	local in="$BUILD/_input"; rm -rf "$in"; mkdir -p "$in"
	cp "$JAVA/editor-app.jar" "$in/"
	# PDF Türkçe harf yaması: FOP metrik XML'leri Contents/app/fopfonts'a (jar yanına) →
	# çalışma zamanında FopFonts bunları CodeSource'tan bulup setUserConfig'e verir.
	[ -d "$BUILD/_fopfonts" ] && { mkdir -p "$in/fopfonts"; cp "$BUILD/_fopfonts/"* "$in/fopfonts/" 2>/dev/null || true; }
	cp "$JAVA/"*.gif "$in/" 2>/dev/null || true
	cp "$JAVA/"*.ico "$in/" 2>/dev/null || true
	cp "$JAVA/BENIOKU.txt" "$in/" 2>/dev/null || true
	# eawt-shim'i jar yapıp girdiye koy (--patch-module ile yüklenecek)
	( cd "$BUILD/_shim" && "$(dirname "$jp")/jar" cf "$in/eawt-shim.jar" com )
	# macOS metin kısayolları agent'ını jar yap (-javaagent ile yüklenecek)
	printf 'Premain-Class: macostextkeys.MacTextKeys\nAgent-Class: macostextkeys.MacTextKeys\n' > "$BUILD/_textkeys/MANIFEST.MF"
	( cd "$BUILD/_textkeys" && "$(dirname "$jp")/jar" cfm "$in/macos-textkeys.jar" MANIFEST.MF macostextkeys )
	# Native diyalog kısayolları dylib'i agent jar'ın yanına ($APPDIR) — agent System.load eder.
	cp "$BUILD/_textkeys/libnativedialogkeys.dylib" "$in/" || die "libnativedialogkeys.dylib eksik (textkeys yeniden çalıştır)."
	printf 'Premain-Class: macoszoom.MacZoom\nAgent-Class: macoszoom.MacZoom\n' > "$BUILD/_zoom/MANIFEST.MF"
	( cd "$BUILD/_zoom" && "$(dirname "$jp")/jar" cfm "$in/macos-zoom.jar" MANIFEST.MF macoszoom )
	# SKIN=1 görünüm agent'ı (bütünleşik başlık + WebMemoryBar); SKIN=0'da jar üretilmez,
	# -javaagent satırı da eklenmez (eksik jar'da JVM hiç başlamaz).
	local lookopts=()
	if [ -d "$BUILD/_lookagent/macoslook" ]; then
		printf 'Premain-Class: macoslook.MacLook\nAgent-Class: macoslook.MacLook\n' > "$BUILD/_lookagent/MANIFEST.MF"
		( cd "$BUILD/_lookagent" && "$(dirname "$jp")/jar" cfm "$in/macos-look.jar" MANIFEST.MF macoslook )
		lookopts=(--java-options '-javaagent:$APPDIR/macos-look.jar' \
			--java-options '-Dapple.awt.application.appearance=system')
	fi
	# TEXTREPLACE=0: macOS Metin Değiştirme genişleticisi kapalı (agent kuruluma hiç girmez).
	local tropts=()
	[ "$TEXTREPLACE" = "1" ] || tropts=(--java-options '-Dmacostextreplace.off=1')
	local icns; icns="$(ls "$SRC_APP_DIR/app/Contents/Resources/"*.icns 2>/dev/null | head -1)"
	local ude_ver; ude_ver="$(plutil -extract CFBundleVersion raw "$SRC_APP_DIR/app/Contents/Info.plist" 2>/dev/null || echo 1.0)"
	local assoc="$BUILD/_udf.properties"
	printf 'extension=udf\nmime-type=application/x-uyap-udf\ndescription=Uyap Doküman\n' > "$assoc"

	c_info "jpackage ile .app paketleniyor (Java 11 + eawt-shim, v$ude_ver)…"
	rm -rf "$APP" "$BUILD/$ASCII_NAME.app"
	"$jp" --type app-image --name "$ASCII_NAME" --app-version "$ude_ver" \
		--input "$in" --main-jar editor-app.jar --main-class "$MAIN_CLASS" \
		--arguments getNewWPInstance --arguments EDITOR_TYPE_DOCUMENT \
		--runtime-image "$rt" \
		--java-options '--patch-module=java.desktop=$APPDIR/eawt-shim.jar' \
		--java-options '--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED' \
		--java-options '--add-exports=java.desktop/com.apple.eio=ALL-UNNAMED' \
		--java-options '--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED' \
		--java-options '-javaagent:$APPDIR/macos-textkeys.jar' \
		--java-options '-javaagent:$APPDIR/macos-zoom.jar' \
		${lookopts[@]+"${lookopts[@]}"} \
		${tropts[@]+"${tropts[@]}"} \
		--java-options '-Dsun.security.smartcardio.library=/System/Library/Frameworks/PCSC.framework/Versions/A/PCSC' \
		--java-options -Xms128M --java-options -Xmx4096M \
		--java-options -XX:+UnlockExperimentalVMOptions \
		--java-options -XX:+UseShenandoahGC \
		--java-options -XX:ShenandoahUncommitDelay=10000 \
		--java-options -XX:ShenandoahGuaranteedGCInterval=120000 \
		--java-options '-splash:$APPDIR/dokuman_editor_splash_screen_animated.gif' \
		${icns:+--icon "$icns"} \
		--mac-package-identifier "$BUNDLE_ID" --file-associations "$assoc" \
		--dest "$BUILD" 2>&1 | grep -viE 'NoSuchElement|No value' || true
	rm -f "$assoc"
	[ -d "$BUILD/$ASCII_NAME.app" ] || die "jpackage .app üretemedi."

	local plist="$BUILD/$ASCII_NAME.app/Contents/Info.plist"
	plutil -replace CFBundleName -string "$APP_NAME" "$plist"
	plutil -replace CFBundleDisplayName -string "$APP_NAME" "$plist" 2>/dev/null \
		|| plutil -insert CFBundleDisplayName -string "$APP_NAME" "$plist"
	plutil -replace NSHighResolutionCapable -bool true "$plist"
	# E-imza: PCSC kütüphane yolu jpackage launcher'a .cfg java-options ile geçmiyor;
	# JVM'in her zaman okuduğu JAVA_TOOL_OPTIONS'ı Launch Services (LSEnvironment) ile ver →
	# çift-tıkla açılışta -D garanti uygulanır (yol Versions/A; Current symlink kullanılmaz).
	plutil -insert LSEnvironment -json \
		'{"JAVA_TOOL_OPTIONS":"-Dsun.security.smartcardio.library=/System/Library/Frameworks/PCSC.framework/Versions/A/PCSC"}' \
		"$plist" 2>/dev/null || c_warn "LSEnvironment eklenemedi (plutil -json desteklemiyor?)"
	mv "$BUILD/$ASCII_NAME.app" "$APP"
	c_ok "Paketlendi: $APP ($(du -sh "$APP" | cut -f1))"
}

sign() {
	[ -d "$APP" ] || die "Önce 'package' çalıştır."
	c_info "ad-hoc imzalanıyor…"
	find "$APP" -name '._*' -delete 2>/dev/null || true
	codesign --force -s - --identifier "$BUNDLE_ID" "$APP"
	codesign --verify --strict "$APP" 2>/dev/null && c_ok "İmza geçerli (adhoc, strict)" || die "İmza doğrulanamadı."
}

# Sürükle-bırak yerleşimli .dmg üret (arka plan: assets/dmg-background.tiff).
# İkon konumları arka plandaki boş yuvalarla eşleşir: uygulama (170,220), Applications (490,220).
# DMG_OUT ile çıktı yolu özelleştirilebilir (CI sürüm etiketli ad verir).
dmg() {
	[ -d "$APP" ] || die "Önce 'package' (+ 'sign') çalıştır."
	command -v create-dmg >/dev/null || die "create-dmg yok → brew install create-dmg"
	local bg="$ROOT/assets/dmg-background.tiff"
	[ -f "$bg" ] || die "DMG arka planı yok: $bg (assets/dmg-background.svg'den üret)"
	local out="${DMG_OUT:-$BUILD/$ASCII_NAME-$JH_ARCH.dmg}"
	rm -f "$out"
	c_info "DMG üretiliyor (sürükle-bırak yerleşimi)…"
	create-dmg \
		--volname "$APP_NAME" \
		--background "$bg" \
		--window-pos 200 120 \
		--window-size 660 440 \
		--icon-size 120 \
		--icon "$APP_NAME.app" 170 220 \
		--app-drop-link 490 220 \
		--hide-extension "$APP_NAME.app" \
		--no-internet-enable \
		"$out" "$APP" \
		|| die "create-dmg başarısız."
	[ -f "$out" ] || die "DMG üretilemedi."
	c_ok "DMG: $out ($(du -sh "$out" | cut -f1))"
}

all() {
	check_deps || die "Ön koşul eksik (jdk / jpackage-jdk)."
	download; deps; shim; textkeys; zoom; lookagent; patch_jar; package; sign
	echo
	c_ok "BİTTİ → $APP"
	c_info "Çalıştır: open \"$APP\"   |   Kur: /Applications'a sürükle (çift-tık ile .udf açılır, Retina'da keskin)"
	c_warn "E-imza ancak gerçek kart + $JH_ARCH PKCS#11 middleware ile test edilebilir."
}

clean()     { c_info "build/ temizleniyor…"; rm -rf "$BUILD"; c_ok "temiz"; }
distclean() { c_info "build/ + downloads/ + vendor jar temizleniyor…"; rm -rf "$BUILD" "$DOWNLOADS" "$SQLITE_JAR"; c_ok "temiz"; }

help() {
	cat <<EOF
build.sh — UDE native $JH_ARCH .app üretici (Java 11 gömülü + eawt-shim)

Hedefler:
  all          Tüm hattı çalıştır (varsayılan)
  check-deps   Araç + $JH_ARCH Java 11 + jpackage denetimi
  jdk          Gömülecek $JH_ARCH Java 11 yoksa Azul Zulu 11 kur
  jpackage-jdk jpackage'lı 17+ JDK yoksa Azul Zulu 21 kur
  download     Paketi indir + kaynağı aç
  deps         sqlite-jdbc indir + $JH_ARCH dylib doğrula
  shim         eawt-shim derle
  textkeys     macOS metin kısayolları javaagent'ını derle (Option+Delete vb.)
  zoom         macOS trackpad zoom javaagent'ını derle (Cmd+iki parmak)
  patch        editor-app.jar yamala (sqlite swap + eawt çıkar + native dosya pencereleri)
  package      jpackage ile .app üret (Java 11 + shim, .udf ilişkilendirmeli)
  sign         ad-hoc codesign
  dmg          sürükle-bırak yerleşimli .dmg üret (create-dmg; DMG_OUT ile ad)
  clean / distclean

Ortam: UDE_URL (boşsa indirme sayfasından güncel MAC paketi otomatik çözülür)
       UDE_DOWNLOAD_PAGE / UDE_ZIP (kaynak), SQLITE_VER (vars: $SQLITE_VER)
       ICONS (1=açık varsayılan | 0=kapalı; modern ikon override + HiDPI yükleyici yaması)
       FOPFONTS (1=açık varsayılan | 0=kapalı; PDF dışa aktarımda Türkçe harf
                 düzeltmesi — FOP'a gömülü macOS Arial/Times fontları tanıtılır)
       PASTEIMG (1=açık varsayılan | 0=kapalı; panodan imaj yapıştırma — macOS'ta
                 pano imajının Retina tipi BufferedImage cast'ini kırıyordu)
       IMGRESIZE (1=açık varsayılan | 0=kapalı; satır-içi imajı köşe
                 tutamaçlarıyla fare ile boyutlandırma — Word benzeri)
       SKIN (1=açık varsayılan | 0=kapalı; modern düz Substance skin + Flamingo
                 şerit + font + nötr kanvas; macOS koyu görünümde koyu tema)
       LIVETOGGLE (1=açık varsayılan | 0=kapalı; Otomatik Büyük Harf / Baş
                 Harfler Büyük / Kelime Denetimi toggle'ları restart'sız anında
                 etkinleşir, "yeniden başlat" diyaloğu kalkar)
EOF
}

case "${1:-all}" in
	all) all ;; check-deps) check_deps ;; jdk) jdk ;; jpackage-jdk) jpackage_jdk ;;
	download) download ;; deps) deps ;; icon-deps) icon_deps ;; shim) shim ;; textkeys) textkeys ;; zoom) zoom ;; lookagent) lookagent ;; patch) patch_jar ;;
	fop-fonts) apply_fop_fonts "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	image-full) IMGFULL=1 apply_imagefull "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	paste-image) apply_pasteimage "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	paste-rich) apply_pasterich "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	image-resize) apply_imgresize "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	live-toggle) apply_livetoggle "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	skin) SKIN=1 apply_skin "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	package) package ;; sign) sign ;; dmg) dmg ;; clean) clean ;; distclean) distclean ;;
	help|-h|--help) help ;;
	*) die "Bilinmeyen hedef: $1  (scripts/build.sh help)" ;;
esac
