#!/bin/sh
# ============================================================
# Coding for Android - 一键构建脚本（手动工具链版）
# 运行环境: Linux（验证于 Ubuntu proot；见 tools/BUILD.md）
# 依赖（默认位于 /opt，可用环境变量覆盖）:
#   /opt/kotlinc    kotlin-compiler-embeddable-1.9.22 / kotlin-stdlib / trove4j / annotations-13.0
#   /opt/cfa-tools  android.jar / r8-d8-8.3.37.jar
# 用法: cd <项目根> && sh tools/build-apk.sh
# 路径均可用环境变量覆盖（CFA_SRC / CFA_KOTLIN / CFA_TOOLS / CFA_WORK / CFA_OUT）。
# ============================================================
BASE=$(cd "$(dirname "$0")/.." && pwd)
SRC="${CFA_SRC:-$BASE}"
K="${CFA_KOTLIN:-/opt/kotlinc}"
T="${CFA_TOOLS:-/opt/cfa-tools}"
W="${CFA_WORK:-/opt/cfa-build}"
OUT="${CFA_OUT:-$SRC/dist}"
APKNAME=Coding-for-Android-v1.0-mvp.apk

cd "$W" || { echo "FATAL: workdir missing"; exit 1; }
mkdir -p src/app/src/main gen classes dexout "$OUT"

echo '==[1/9] sync sources=='
rm -fr "src/app/src/main/java"
cp -rf "$SRC/app/src/main/java" src/app/src/main/
cp -f  "$SRC/app/src/main/AndroidManifest.xml" src/app/src/main/AndroidManifest.xml
rm -fr "src/app/src/main/res"
cp -rf "$SRC/app/src/main/res" src/app/src/main/
rm -fr "src/app/src/main/assets"
cp -rf "$SRC/app/src/main/assets" src/app/src/main/

if ! grep -q 'package="com.AROAN110.CodingAndroid"' src/app/src/main/AndroidManifest.xml; then
  sed -i 's|xmlns:tools="http://schemas.android.com/tools">|xmlns:tools="http://schemas.android.com/tools"\n    package="com.AROAN110.CodingAndroid">|' src/app/src/main/AndroidManifest.xml
  if ! grep -q 'package="com.AROAN110.CodingAndroid"' src/app/src/main/AndroidManifest.xml; then
    sed -i 's|<manifest |<manifest package="com.AROAN110.CodingAndroid" |' src/app/src/main/AndroidManifest.xml
  fi
  echo '  manifest patched'
fi

echo '==[2/9] clean=='
find gen -name '*.java' -delete
find classes -name '*.class' -delete
find dexout -name '*.dex' -delete
rm -f res.zip base.apk app-unsigned.apk app-aligned.apk app-signed.apk

echo '==[3/9] aapt2 compile=='
aapt2 compile --dir src/app/src/main/res -o res.zip || { echo 'FATAL: aapt2 compile'; exit 1; }

echo '==[4/9] aapt2 link=='
aapt2 link -o base.apk -I "$T/android.jar" --manifest src/app/src/main/AndroidManifest.xml --java gen --min-sdk-version 26 --target-sdk-version 28 -A src/app/src/main/assets res.zip || { echo 'FATAL: aapt2 link'; exit 1; }

echo '==[5/9] javac R.java=='
javac -encoding UTF-8 -source 17 -target 17 -classpath "$T/android.jar" -d classes $(find gen -name '*.java') || { echo 'FATAL: javac'; exit 1; }

echo '==[6/9] kotlinc=='
java -cp "$K/kotlin-compiler-embeddable-1.9.22.jar:$K/kotlin-stdlib-1.9.22.jar:$K/trove4j-1.0.20200330.jar:$K/annotations-13.0.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$T/android.jar:$K/kotlin-stdlib-1.9.22.jar:classes" -d classes $(find src/app/src/main/java -name '*.kt') || { echo 'FATAL: kotlinc'; exit 1; }

echo '==[7/9] d8=='
java -cp "$T/r8-d8-8.3.37.jar" com.android.tools.r8.D8 --min-api 26 --release --output dexout --lib "$T/android.jar" $(find classes -name '*.class' | sort) "$K/kotlin-stdlib-1.9.22.jar" || { echo 'FATAL: d8'; exit 1; }

echo '==[8/9] assemble + align=='
cp -f base.apk app-unsigned.apk
zip -j -X app-unsigned.apk dexout/classes.dex > /dev/null || { echo 'FATAL: zip'; exit 1; }
zipalign -f 4 app-unsigned.apk app-aligned.apk || { echo 'FATAL: zipalign'; exit 1; }

echo '==[9/9] sign + verify=='
# 签名信息（可用环境变量覆盖）：CFA_KEYSTORE / CFA_KS_ALIAS / CFA_KS_PASS
KS_PATH="${CFA_KEYSTORE:-$W/cfa-release.keystore}"
KS_ALIAS="${CFA_KS_ALIAS:-cfa}"
KS_PASS="${CFA_KS_PASS:-changeit}"
if [ ! -f "$KS_PATH" ]; then
  keytool -genkeypair -keystore "$KS_PATH" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Coding for Android" || { echo 'FATAL: keytool'; exit 1; }
  echo "  已生成测试签名（发布请替换为你自己的 keystore）: $KS_PATH"
fi
apksigner sign --ks "$KS_PATH" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out app-signed.apk app-aligned.apk || { echo 'FATAL: apksigner sign'; exit 1; }
apksigner verify --print-certs app-signed.apk || { echo 'FATAL: apksigner verify'; exit 1; }

cp -f app-signed.apk "$OUT/$APKNAME"
echo '==DONE=='
ls -la "$OUT/$APKNAME"