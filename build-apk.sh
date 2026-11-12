#!/usr/bin/env bash
#
# build-apk.sh —— 一条命令把海阔小程序版的安装包编出来
#
#   ./build-apk.sh              # 手机 + TV，arm64 + armv7a，共 4 个 debug 包
#   ./build-apk.sh arm64        # 只编 arm64
#   ./build-apk.sh mobile       # 只编手机
#   ./build-apk.sh leanback     # 只编 TV
#   ./build-apk.sh release      # 编 release（需要 local.properties 里有签名配置）
#
# 环境要求：JDK 17+（推荐 21）、Android SDK（platform 37 / build-tools 37.0.0 /
#           ndk 29.0.14206865 / cmake 3.22.1）
# 找不到 SDK 时会提示你怎么设 ANDROID_SDK_ROOT。
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT_DIR"

MODE="${1:-all}"
OUT_DIR="$ROOT_DIR/dist"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[错误] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- JDK
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  die "找不到 java。装一个 JDK 17+（推荐 21），或设 JAVA_HOME。"
fi
JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1)"
say "JDK: $JAVA_VER"

# ---------------------------------------------------------------- SDK
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ]; then
  for cand in "$HOME/Android/Sdk" "$HOME/android-sdk" /usr/lib/android-sdk /opt/android-sdk; do
    [ -d "$cand/platforms" ] && SDK_DIR="$cand" && break
  done
fi
[ -n "$SDK_DIR" ] || die "找不到 Android SDK。设 export ANDROID_SDK_ROOT=/你的/sdk 再跑。"
[ -d "$SDK_DIR/platforms" ] || die "SDK 目录里没有 platforms：$SDK_DIR"
say "SDK: $SDK_DIR"

# 缺哪个组件就直接说清楚，不要让 gradle 报一堆看不懂的错
need_pkg() {
  [ -d "$1" ] || printf '  缺少：%s（用 sdkmanager "%s" 安装）\n' "$2" "$2"
}
MISSING=0
P37="$(ls -d "$SDK_DIR"/platforms/android-37* 2>/dev/null | head -1 || true)"
[ -n "$P37" ] || { need_pkg "" "platforms;android-37.0"; MISSING=1; }
BT="$(ls -d "$SDK_DIR"/build-tools/37* 2>/dev/null | head -1 || true)"
[ -n "$BT" ] || { need_pkg "" "build-tools;37.0.0"; MISSING=1; }
NDK="$(ls -d "$SDK_DIR"/ndk/29.0.14206865 2>/dev/null | head -1 || true)"
[ -n "$NDK" ] || { need_pkg "" "ndk;29.0.14206865"; MISSING=1; }
CM="$(ls -d "$SDK_DIR"/cmake/3.22.1 2>/dev/null | head -1 || true)"
[ -n "$CM" ] || { need_pkg "" "cmake;3.22.1"; MISSING=1; }
[ "$MISSING" = "0" ] || die "SDK 组件不全，按上面提示装完再跑。"

# local.properties（已存在就不覆盖，免得冲掉你自己的签名配置）
if [ ! -f local.properties ]; then
  echo "sdk.dir=$SDK_DIR" > local.properties
  say "已生成 local.properties: sdk.dir=$SDK_DIR"
fi

# ---------------------------------------------------------------- Gradle
if [ -x ./gradlew ]; then
  GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  die "找不到 gradle。用 ./gradlew（会自动下载），或安装 gradle 9.5.1。"
fi
say "Gradle: $GRADLE"

case "$MODE" in
  all)      TASKS=":app:assembleMobileArm64_v8aDebug :app:assembleLeanbackArm64_v8aDebug :app:assembleMobileArmeabi_v7aDebug :app:assembleLeanbackArmeabi_v7aDebug" ;;
  arm64)    TASKS=":app:assembleMobileArm64_v8aDebug :app:assembleLeanbackArm64_v8aDebug" ;;
  mobile)   TASKS=":app:assembleMobileArm64_v8aDebug :app:assembleMobileArmeabi_v7aDebug" ;;
  leanback) TASKS=":app:assembleLeanbackArm64_v8aDebug :app:assembleLeanbackArmeabi_v7aDebug" ;;
  release)  TASKS=":app:assembleMobileArm64_v8aRelease :app:assembleLeanbackArm64_v8aRelease" ;;
  *)        die "不认识的参数：$MODE（可选 all / arm64 / mobile / leanback / release）" ;;
esac

say "开始编译：$TASKS"
# shellcheck disable=SC2086
"$GRADLE" $TASKS --console=plain

# ---------------------------------------------------------------- 收集产物
mkdir -p "$OUT_DIR"
FOUND=0
while IFS= read -r apk; do
  cp -f "$apk" "$OUT_DIR"/
  FOUND=$((FOUND + 1))
done < <(find app/build/outputs/apk -name "*.apk")

if [ "$FOUND" = "0" ]; then
  die "gradle 说成功了，但没找到 apk。看一眼 app/build/outputs/apk 目录。"
fi

say "编完了，产物在 $OUT_DIR"
ls -lh "$OUT_DIR" | sed '1d'
printf '\n装哪个：手机装 mobile 那个，电视盒子装 leanback 那个；机器是 32 位的就挑 armeabi_v7a。\n'
