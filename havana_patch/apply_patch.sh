#!/usr/bin/env bash
#
# Merges the resumable-download / skip-existing improvements into an existing
# HavanaRP APK template extracted by apktool.
#
# Usage:
#   ./apply_patch.sh <path-to-HavanaRp-pack-root>
#
# Expects the canonical layout produced by the HavanaRp_v23.1_FINAL_pack:
#   <root>/
#     02_signing/havana.keystore
#     03_apktool_template/apk_full/...
#     04_tools/apktool_*.jar
#     04_tools/build_apk.sh
#
# Required tooling on PATH:
#   - javac (JDK 17 is fine, source level 8)
#   - d8       (Android SDK build-tools)
#   - baksmali (downloaded automatically if missing)
#   - apktool, apksigner, zipalign (already in the pack's 04_tools/)
#
# Required env:
#   - ANDROID_HOME pointing at an Android SDK with platforms/android-35

set -euo pipefail

PACK_ROOT="${1:-}"
if [[ -z "$PACK_ROOT" || ! -d "$PACK_ROOT" ]]; then
    echo "usage: $0 <path-to-HavanaRp-pack-root>" >&2
    exit 1
fi

PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$PACK_ROOT/03_apktool_template/apk_full"
SMALI_OUT="$TEMPLATE/smali/com/luxury/mobile"
TOOLS="$PATCH_DIR/.tools"
WORK="$PATCH_DIR/.work"

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo "ANDROID_HOME must point at an Android SDK with platforms/android-35 installed" >&2
    exit 1
fi

if [[ ! -d "$TEMPLATE" ]]; then
    echo "Expected $TEMPLATE — run apktool d on the base APK first" >&2
    exit 1
fi

mkdir -p "$TOOLS" "$WORK"

# --- 1. Ensure baksmali is available ---
if [[ ! -f "$TOOLS/baksmali.jar" ]]; then
    echo "[setup] downloading baksmali..."
    curl -sSL -o "$TOOLS/baksmali.jar" \
        https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
fi

# --- 2. Compile Java -> .class ---
echo "[1/4] compiling Java helper classes..."
rm -rf "$WORK/out"
mkdir -p "$WORK/out"
javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_HOME/platforms/android-35/android.jar" \
    -d "$WORK/out" \
    -sourcepath "$PATCH_DIR/stubs:$PATCH_DIR/src" \
    "$PATCH_DIR/src/com/luxury/mobile/gui/HavanaDownloadRunner.java" \
    "$PATCH_DIR/src/com/luxury/mobile/util/HavanaSmartDownload.java" \
    "$PATCH_DIR/src/com/luxury/mobile/util/HavanaInstallCheck.java"

# Discard the stub class — only the real Java sources go into the APK.
rm -f "$WORK/out/com/luxury/mobile/gui/InstallActivity.class"

# --- 3. .class -> .dex -> .smali ---
echo "[2/4] running d8..."
( cd "$WORK/out" && jar cf "$WORK/patch.jar" . )
rm -rf "$WORK/dex"
mkdir -p "$WORK/dex"
d8 --min-api 21 --output "$WORK/dex/" "$WORK/patch.jar"

echo "[3/4] running baksmali..."
rm -rf "$WORK/smali"
java -jar "$TOOLS/baksmali.jar" d "$WORK/dex/classes.dex" -o "$WORK/smali"

# --- 4. Copy generated smali into the apk_full template ---
echo "[4/4] installing smali..."
mkdir -p "$SMALI_OUT/util" "$SMALI_OUT/gui"
cp -v "$WORK/smali/com/luxury/mobile/util/"Havana*.smali "$SMALI_OUT/util/"
cp -v "$WORK/smali/com/luxury/mobile/gui/"HavanaDownloadRunner*.smali "$SMALI_OUT/gui/"

# --- 5. Patch InstallActivity.smali ---
INSTALL_ACT="$SMALI_OUT/gui/InstallActivity.smali"

# Replace InstallActivity$1 with HavanaDownloadRunner in getFileSize().
if grep -q "Lcom/luxury/mobile/gui/InstallActivity\$1;" "$INSTALL_ACT"; then
    sed -i 's|new-instance v1, Lcom/luxury/mobile/gui/InstallActivity$1;|new-instance v1, Lcom/luxury/mobile/gui/HavanaDownloadRunner;|; s|Lcom/luxury/mobile/gui/InstallActivity$1;-><init>(Lcom/luxury/mobile/gui/InstallActivity;Ljava/lang/String;)V|Lcom/luxury/mobile/gui/HavanaDownloadRunner;-><init>(Lcom/luxury/mobile/gui/InstallActivity;Ljava/lang/String;)V|' "$INSTALL_ACT"
    echo "patched: getFileSize() now spawns HavanaDownloadRunner"
fi

# Remove the unconditional `/sdcard/LuxuryMobile/` delete in onCreate().
python3 - "$INSTALL_ACT" <<'PY'
import re
import sys

path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

pattern = re.compile(
    r'    \.line 43\n'
    r'    new-instance v0, Ljava/io/File;\n'
    r'.*?'
    r'    invoke-virtual \{v0\}, Ljava/io/File;->delete\(\)Z\n\n'
    r'    \.line 47\n'
    r'    :cond_1\n',
    re.DOTALL,
)
new_text, n = pattern.subn(
    '    .line 43\n'
    '    # Patched: skip unconditional delete of /sdcard/LuxuryMobile.\n'
    '    # HavanaDownloadRunner will check the dir and decide.\n'
    '    nop\n\n'
    '    .line 47\n'
    '    :cond_1\n',
    text,
)
with open(path, 'w', encoding='utf-8') as f:
    f.write(new_text)
print(f"patched: removed unconditional dir delete ({n} match)")
PY

# --- 6. Rebuild and sign ---
echo "rebuilding APK..."
( cd "$PACK_ROOT" && bash 04_tools/build_apk.sh )

echo
echo "Done -> $PACK_ROOT/03_apktool_template/HavanaRp.apk"
