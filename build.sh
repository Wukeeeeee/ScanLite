#!/usr/bin/env bash
# ============================================================
#  ScanLite 一键编译（Git Bash）
#  用法： ./build.sh
#  产物： 仓库根目录 ScanLite-<版本号>.apk
#  依赖： D:\jdk17  +  D:\AndroidSdk（路径写在 local.properties）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-D:\\jdk17}"
if [ ! -f "$JAVA_HOME/bin/java.exe" ] && [ ! -f "$JAVA_HOME/bin/java" ]; then
  echo "[错误] 找不到 JDK：$JAVA_HOME"
  echo "       请把 build.sh 里的 JAVA_HOME 改成你本机 JDK 17 的路径。"
  exit 1
fi

./gradlew testDebugUnitTest assembleRelease --no-daemon

VN=$(grep -E 'versionName *= *"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
cp -f app/build/outputs/apk/release/app-release.apk "ScanLite-${VN}.apk"

echo
echo "============================================================"
echo " 编译成功： ScanLite-${VN}.apk"
echo " 安装到手机： adb install -r ScanLite-${VN}.apk"
echo "============================================================"
