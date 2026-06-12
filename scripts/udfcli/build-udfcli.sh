#!/usr/bin/env bash
# udf-cli html2udf yolunu self-contained arm64 ikiliye derler (bun --compile).
# Kullanım: build-udfcli.sh <çıktı-ikili-yolu>
#   UDFCLI_SRC ayarlıysa o yerel udf-cli reposu kullanılır; değilse public repo klonlanır.
# Çıkış kodu 3 = bun/udf-cli yok (PASTERICH atlanmalı, build düşmemeli).
set -euo pipefail

OUT="${1:?çıktı ikili yolu gerekli}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUN="$(command -v bun || true)"
[ -n "$BUN" ] || BUN="$HOME/.bun/bin/bun"
[ -x "$BUN" ] || { echo "[udfcli] bun bulunamadı; PASTERICH atlanacak" >&2; exit 3; }

SRC="${UDFCLI_SRC:-}"
CLONED=0
if [ -z "$SRC" ]; then
	SRC="${TMPDIR:-/tmp}/udf-cli-src"
	rm -rf "$SRC"
	git clone --depth 1 https://github.com/saidsurucu/udf-cli.git "$SRC" >/dev/null 2>&1 \
		|| { echo "[udfcli] udf-cli klonlanamadı; PASTERICH atlanacak" >&2; exit 3; }
	CLONED=1
fi

[ -f "$SRC/src/converters/html-to-udf.ts" ] \
	|| { echo "[udfcli] udf-cli kaynağı beklenen yapıda değil: $SRC" >&2; exit 3; }

cp "$HERE/html2udf-entry.ts" "$SRC/html2udf-entry.ts"

# Bağımlılıkları kur (node_modules yoksa).
if [ ! -d "$SRC/node_modules" ]; then
	( cd "$SRC" && { "$BUN" install --frozen-lockfile >/dev/null 2>&1 || "$BUN" install >/dev/null 2>&1; } ) \
		|| { echo "[udfcli] bun install başarısız" >&2; exit 3; }
fi

mkdir -p "$(dirname "$OUT")"
( cd "$SRC" && "$BUN" build --compile --target=bun-darwin-arm64 \
	./html2udf-entry.ts --outfile "$OUT" >/dev/null ) \
	|| { echo "[udfcli] bun --compile başarısız" >&2; exit 3; }
chmod +x "$OUT"

[ "$CLONED" = "1" ] && rm -rf "$SRC" || true
echo "[udfcli] ikili üretildi: $OUT"
