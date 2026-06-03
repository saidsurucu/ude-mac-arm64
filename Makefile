# UDE (Uyap Doküman Editörü) — native Apple Silicon (arm64) build
# Asıl mantık scripts/build.sh içinde (boşluk/Türkçe karakterli .app adı Make'te sorunlu).
# Bu Makefile ince bir sarmalayıcıdır.

SH := bash scripts/build.sh

.PHONY: all check-deps jdk download deps launcher patch sign clean distclean help

all: ## ARM64 .app'i üret (varsayılan)
	@$(SH) all

check-deps: ## Araçları ve arm64 JDK 8'i denetle
	@$(SH) check-deps

jdk: ## arm64 JDK 8 yoksa Azul Zulu 8 kur
	@$(SH) jdk

download: ## Kaynak paketi indir ve build/'e aç
	@$(SH) download

deps: ## sqlite-jdbc'yi indir + arm64 dylib doğrula
	@$(SH) deps

launcher: ## arm64 launcher'ı bundle'a kur
	@$(SH) launcher

patch: ## editor-app.jar içinde sqlite swap
	@$(SH) patch

sign: ## ad-hoc codesign
	@$(SH) sign

clean: ## build/ sil (indirilenleri korur)
	@$(SH) clean

distclean: ## build/ + indirilenler + vendor jar sil
	@$(SH) distclean

help: ## Bu yardım
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort
