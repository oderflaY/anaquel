#!/usr/bin/env bash
# Publica una versión de «Anaquel Mercado» en los Releases de GitHub.
#
#   1. Sube versionCode y versionName en app/androidApp/build.gradle.kts.
#   2. Escribe las notas en app/releases-mercado/<versión>.md (Markdown).
#   3. Ejecuta: scripts/publicar-mercado.sh
#
# Crea el release «mercado-v<versión>» sobre el commit actual (ya subido) y le
# adjunta el APK. La app lo encuentra sola al abrirse (ver Actualizaciones.kt).
# Usa la sesión de GitHub guardada en git (no guarda ni imprime el token).
# Importante: compila siempre con la misma llave de firma; Android rechaza una
# actualización firmada con otra llave.
set -euo pipefail
REPO="oderflaY/anaquel"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$RAIZ/app/androidApp/build.gradle.kts")
TAG="mercado-v$VER"
NOTAS="$RAIZ/app/releases-mercado/$VER.md"
[ -f "$NOTAS" ] || { echo "Faltan las notas: $NOTAS"; exit 1; }
git -C "$RAIZ" diff --quiet HEAD || { echo "Hay cambios sin commit; súbelos primero."; exit 1; }
COMMIT=$(git -C "$RAIZ" rev-parse HEAD)
git -C "$RAIZ" fetch -q origin
git -C "$RAIZ" branch -r --contains "$COMMIT" | grep -q . || { echo "El commit $COMMIT no está en GitHub; haz push primero."; exit 1; }

echo "Compilando $TAG…"
(cd "$RAIZ/app" && ./gradlew :androidApp:assembleDebug -q)
APK="$RAIZ/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
[ -n "$TOKEN" ] || { echo "No hay sesión de GitHub guardada en git."; exit 1; }
API=(-sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "User-Agent: publicar-mercado")

CUERPO=$(python -c 'import json,sys; print(json.dumps({"tag_name":sys.argv[1],"target_commitish":sys.argv[2],"name":"Anaquel Mercado "+sys.argv[3],"body":open(sys.argv[4],encoding="utf-8").read(),"draft":False,"prerelease":False}))' "$TAG" "$COMMIT" "$VER" "$NOTAS")
RESP=$(curl "${API[@]}" -X POST "https://api.github.com/repos/$REPO/releases" -d "$CUERPO")
ID=$(printf '%s' "$RESP" | python -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')
[ -n "$ID" ] || { echo "No se pudo crear el release:"; printf '%s\n' "$RESP" | head -c 400; exit 1; }

curl "${API[@]}" -X POST -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=anaquel-mercado-$VER.apk" -o /dev/null -w "APK subido (%{http_code})\n"
echo "Listo: https://github.com/$REPO/releases/tag/$TAG"
