#!/usr/bin/env bash
# 对「已构建好的」MRSS APK 做 Android 15+ 最小补丁：
# 只把硬编码的 transaction code 50 改成 51，其余所有内容原样保留，然后重新签名。
#
# 依赖：java、apktool.jar（借用其内置的 smali/baksmali）、apksigner、python3
# 用法：./patch_apk.sh <原始.apk> <输出.apk> <keystore> <store-pass> <key-pass> [api]
#
# apktool.jar 下载：
#   curl -sSLo apktool.jar https://repo1.maven.org/maven2/org/apktool/apktool-cli/3.0.3/apktool-cli-3.0.3.jar
set -euo pipefail

APK_IN=${1:?原始 APK}; APK_OUT=${2:?输出 APK}; KS=${3:?keystore}
KS_PASS=${4:?store 密码}; KEY_PASS=${5:?key 密码}; API=${6:-34}
APKTOOL=${APKTOOL_JAR:-apktool.jar}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "[1/5] 提取 classes.dex 并反编译"
unzip -o -q "$APK_IN" classes.dex -d "$WORK"
java -cp "$APKTOOL" com.android.tools.smali.baksmali.Main d "$WORK/classes.dex" -o "$WORK/smali"

echo "[2/5] 替换 transaction code 50 -> 51"
mapfile -t FILES < <(grep -rl 'activity_task 50 i32' "$WORK/smali" || true)
[ ${#FILES[@]} -gt 0 ] || { echo "未找到 'activity_task 50 i32'，APK 可能已打过补丁"; exit 1; }
for f in "${FILES[@]}"; do
  sed -i 's/activity_task 50 i32 /activity_task 51 i32 /g' "$f"
  echo "    patched ${f#"$WORK"/}"
done

echo "[3/5] 汇编回 dex"
java -cp "$APKTOOL" com.android.tools.smali.smali.Main a "$WORK/smali" -o "$WORK/classes_patched.dex" -a "$API"

echo "[4/5] 重建 APK 并做 4K 对齐"
python3 "$HERE/build_android15_patch.py" "$APK_IN" "$WORK/classes_patched.dex" "$WORK/unsigned.apk"

echo "[5/5] 签名"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$APK_OUT" "$WORK/unsigned.apk"
apksigner verify --verbose "$APK_OUT" | head -5
echo "完成: $APK_OUT"
