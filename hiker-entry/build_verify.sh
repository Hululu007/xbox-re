#!/usr/bin/env bash
# 构建前置 · 独立模块真实编译验证
# 用 ecj(Eclipse JDT) + android-all.jar 编译 hiker 包 4 个类，再用 r8/d8 生成 dex。
# 目的：在沙箱无 Android SDK / 无 JDK21 / 无 Gradle 9.5.1 的条件下，
#       尽量接近真实地验证「新增 App 源码可编译、无缺失符号、桥注解正确」。
set -u
B=/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d6c-6c38ebd20000/hk_verify/build_v390/build
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/app/src/main/java"
OUT="$HERE/build_verify"
ECJ="$B/ecj.jar"
R8="$B/r8.jar"
ANDROID="$B/android-all.jar"

rm -rf "$OUT"; mkdir -p "$OUT/classes"
echo "== 工具 =="
[ -f "$ECJ" ] && echo "ecj:        OK" || { echo "ecj 缺失"; exit 1; }
[ -f "$ANDROID" ] && echo "android-all: OK ($(du -h "$ANDROID" | cut -f1))" || { echo "android-all 缺失"; exit 1; }

echo "== 源码 =="
SRCS=$(find "$SRC/com/fongmi/android/tv/hiker" -name '*.java' | sort)
echo "$SRCS" | sed 's|^|  |'
N=$(echo "$SRCS" | wc -l | tr -d ' ')

echo "== ecj 编译 (Java 8 语法，JDK 提供 java.*，android-all 补 Android API) =="
# 注意：不能用 -bootclasspath，否则会屏蔽 JDK 自身的 java.lang.String。
# 与阶段四 v390 真实编译一致：-8 -cp android-all.jar。
java -Xmx2g -jar "$ECJ" -8 -nowarn -encoding UTF-8 -proc:none \
  -cp "$ANDROID" -d "$OUT/classes" $SRCS 2>&1 | tee "$OUT/ecj.log"
RC=${PIPESTATUS[0]}
echo "ecj exit=$RC"
if [ "$RC" != "0" ]; then echo "编译失败"; exit 2; fi
CLASSES=$(find "$OUT/classes" -name '*.class' | wc -l | tr -d ' ')
echo "产物 .class: $CLASSES 个"
find "$OUT/classes" -name '*.class' | sed "s|$OUT/classes/||" | sort | sed 's|^|  |'

echo "== 桥注解核验 =="
javap -classpath "$OUT/classes:$ANDROID" -p com.fongmi.android.tv.hiker.HikerBridge 2>/dev/null \
  | grep -E "public|Javascript" | head -30 || echo "(javap 不可用，跳过)"

echo "== d8 -> dex =="
if [ -f "$R8" ]; then
  java -cp "$R8" com.android.tools.r8.D8 \
    --lib "$ANDROID" --min-api 24 --output "$OUT" $(find "$OUT/classes" -name '*.class') 2>&1 | tee "$OUT/d8.log"
  DRC=${PIPESTATUS[0]}
  echo "d8 exit=$DRC"
  [ -f "$OUT/classes.dex" ] && echo "classes.dex: $(stat -c%s "$OUT/classes.dex") 字节"
else
  echo "r8.jar 缺失，跳过 dex（ecj 编译已过）"
fi

echo "== 结果 =="
printf '{"ecj_exit":%s,"java_sources":%s,"class_files":%s,"dex":%s}\n' \
  "$RC" "$N" "$CLASSES" "$([ -f "$OUT/classes.dex" ] && echo true || echo false)" > "$OUT/result.json"
cat "$OUT/result.json"
