#!/usr/bin/env bash
# kur.sh — UDE'yi Apple Silicon Mac'te tek komutla derleyip kuran yardımcı betik.
#
# README'deki adımları (geliştirici araçları + Java'ların indirilmesi + derleme +
# paketleme + Applications'a taşıma) sizin için sırayla yapar. Programcı olmanıza
# gerek yok: bu dosyanın bulunduğu klasörde Terminal'de  ./kur.sh  yazıp Enter'a basın.
#
# Asıl derleme mantığı scripts/build.sh içindedir; bu betik onu sarmalar.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ----- Renkli, anlaşılır mesajlar -----
if [ -t 1 ]; then
	BOLD=$'\033[1m'; GRN=$'\033[32m'; YLW=$'\033[33m'; RED=$'\033[31m'; BLU=$'\033[34m'; RST=$'\033[0m'
else
	BOLD=""; GRN=""; YLW=""; RED=""; BLU=""; RST=""
fi
say()  { printf '%s\n' "${BLU}›${RST} $*"; }
ok()   { printf '%s\n' "${GRN}✓${RST} $*"; }
warn() { printf '%s\n' "${YLW}!${RST} $*"; }
die()  { printf '%s\n' "${RED}✗ $*${RST}" >&2; exit 1; }
step() { printf '\n%s\n' "${BOLD}== $* ==${RST}"; }

APP_NAME="Uyap Doküman Editörü.app"
BUILT_APP="$SCRIPT_DIR/build/$APP_NAME"
DEST_APP="/Applications/$APP_NAME"

# ----- 0) Ortam kontrolü -----
step "Ortam denetimi"
[ "$(uname -s)" = "Darwin" ] || die "Bu betik yalnızca macOS içindir."
if [ "$(uname -m)" != "arm64" ]; then
	die "Bu betik Apple Silicon (M1/M2/M3/M4) içindir. Mevcut mimari: $(uname -m)"
fi
ok "Apple Silicon Mac algılandı"
[ -f "$SCRIPT_DIR/scripts/build.sh" ] || die "scripts/build.sh bulunamadı. Betiği depo klasörünün içinden çalıştırın."

# ----- 1) Xcode komut satırı araçları (make, codesign vb.) -----
step "Geliştirici araçları (bir kez)"
if xcode-select -p >/dev/null 2>&1; then
	ok "Komut satırı araçları zaten kurulu"
else
	warn "Komut satırı araçları yok; kurulum penceresi açılıyor…"
	xcode-select --install >/dev/null 2>&1 || true
	say "Açılan pencerede ${BOLD}\"Yükle\"${RST}ye basıp bitmesini bekleyin."
	say "Kurulum tamamlanınca bu betik kendiliğinden devam edecek…"
	# Kullanıcı kurulumu bitirene kadar bekle (iptal ederse Ctrl+C ile çıkabilir).
	until xcode-select -p >/dev/null 2>&1; do
		printf '.'
		sleep 5
	done
	printf '\n'
	ok "Komut satırı araçları kuruldu"
fi
command -v make >/dev/null 2>&1 || die "make bulunamadı (komut satırı araçları eksik olabilir)."

# ----- 2) Gömülecek arm64 Java 11 -----
step "arm64 Java 11 (gömülecek çalışma zamanı)"
make jdk

# ----- 3) Paketleyici JDK (jpackage'lı 17+) -----
step "Paketleyici JDK (jpackage)"
make jpackage-jdk

# ----- 4) Derle + modern ikonlarla paketle + imzala -----
step "Derleme + paketleme (birkaç dakika sürebilir)"
ICONS=1 make all
[ -d "$BUILT_APP" ] || die "Beklenen uygulama üretilemedi: $BUILT_APP"
ok "Uygulama hazır: $BUILT_APP"

# ----- 5) /Applications'a taşı (gerekirse eskisini değiştir) -----
step "Applications'a kurulum"
if pgrep -f "$APP_NAME/Contents/MacOS" >/dev/null 2>&1; then
	warn "Uygulama açık görünüyor; kapatılıyor…"
	osascript -e 'tell application "Uyap Doküman Editörü" to quit' >/dev/null 2>&1 || true
	sleep 2
fi
if [ -e "$DEST_APP" ]; then
	say "Eski sürüm bulundu, değiştiriliyor…"
	rm -rf "$DEST_APP" 2>/dev/null || sudo rm -rf "$DEST_APP"
fi
if mv "$BUILT_APP" "$DEST_APP" 2>/dev/null; then
	ok "Kuruldu: $DEST_APP"
else
	warn "/Applications yazılamadı; yönetici izniyle taşınıyor…"
	sudo mv "$BUILT_APP" "$DEST_APP"
	ok "Kuruldu: $DEST_APP"
fi

# ----- Bitti -----
printf '\n'
ok "${BOLD}BİTTİ.${RST} UDE artık Launchpad ve Applications'ta. .udf dosyalarına çift tıklayarak da açabilirsiniz."
say "Açmak için: ${BOLD}open \"$DEST_APP\"${RST}"
printf '\n'
warn "E-imza kullanacaksanız: TÜBİTAK AKİS'in ${BOLD}Apple Silicon (Arm)${RST} sürücüsünü kurun"
say "  https://akiskart.bilgem.tubitak.gov.tr/destek/  → \"Mac OS Arm (Apple Silicon)\""
printf '\n'
say "Yeni UDE sürümü çıktığında bu betiği yeniden çalıştırmanız yeterli (en güncel sürüm otomatik iner)."
