#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# build-qnn-jni.sh — 在 Termux 原生编译含 QNN backend 的 libsherpa-onnx-jni.so
#                     并替换 app/libs AAR 内的同名库
#
# 背景：官方标准 AAR 的 libsherpa-onnx-jni.so 不含 QNN backend 代码
#      （sherpa-onnx/csrc/qnn/*），必须以 SHERPA_ONNX_ENABLE_QNN=ON 重编。
#      Termux clang 目标即 Android arm64/bionic，可原生编译出 App 可用的 .so。
#
# 依赖：clang/cmake/ninja（pkg install clang cmake ninja）
# 产物：
#   - libsherpa-onnx-jni.so（含 QNN，替换 AAR 内旧文件）
#   - libonnxruntime.so（ORT 1.27.1 Android 官方预编译，替换 AAR 内旧文件以匹配版本）
# ============================================================
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AAR="$PROJ_DIR/app/libs/sherpa-onnx-1.13.4.aar"
WORK="$HOME/.sherpa-qnn-build"
SRC="$WORK/sherpa-onnx"
BUILD="$SRC/build-tx"
SHERPA_VER="v1.13.4"
QNN_VER="2.40.0.251030"
ORT_VER="1.27.1"

# 1. 源码
if [ ! -d "$SRC" ]; then
    mkdir -p "$WORK"
    git clone -q --depth 1 --branch "$SHERPA_VER" https://github.com/k2-fsa/sherpa-onnx "$SRC"
fi

# 2. QNN 头文件 + Android ORT 预编译库
if [ ! -f "$WORK/qnn-include-${QNN_VER}/include/QNN/QnnInterface.h" ]; then
    wget -q -O "$WORK/qnn-include.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-include-${QNN_VER}.tar.bz2"
    tar xjf "$WORK/qnn-include.tar.bz2" -C "$WORK"
fi
if [ ! -f "$WORK/ort-android/jni/arm64-v8a/libonnxruntime.so" ]; then
    wget -q -O "$WORK/ort-android.zip" \
      "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ORT_VER}/onnxruntime-android-${ORT_VER}.zip"
    unzip -o -q "$WORK/ort-android.zip" -d "$WORK/ort-android-extract"
    mkdir -p "$WORK/ort-android"
    mv "$WORK/ort-android-extract"/* "$WORK/ort-android/"
fi

# 3. liblog 链接桩（Termux 无 liblog.so，链接期直接用系统的）
[ -e "$PREFIX/lib/liblog.so" ] || ln -sf /system/lib64/liblog.so "$PREFIX/lib/liblog.so"

# 3.5 libc++_shared.so（Termux 动态链接的 C++ 运行时需随 APK 打包）
mkdir -p "$PROJ_DIR/app/src/main/jniLibs/arm64-v8a"
cp -v "$PREFIX/lib/libc++_shared.so" "$PROJ_DIR/app/src/main/jniLibs/arm64-v8a/"

# 4. 配置 + 编译（仅 libsherpa-onnx-jni.so 目标，省时间）
mkdir -p "$BUILD"
cd "$BUILD"
export SHERPA_ONNXRUNTIME_LIB_DIR="$WORK/ort-android/jni/arm64-v8a"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$WORK/ort-android/headers"
export QNN_SDK_ROOT="$WORK/qnn-include-${QNN_VER}"
if [ ! -f build.ninja ]; then
    CC=clang CXX=clang++ cmake "$SRC" -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=ON \
      -DSHERPA_ONNX_ENABLE_JNI=ON \
      -DSHERPA_ONNX_ENABLE_QNN=ON \
      -DSHERPA_ONNX_ENABLE_C_API=OFF \
      -DSHERPA_ONNX_ENABLE_BINARY=OFF \
      -DSHERPA_ONNX_ENABLE_TTS=ON \
      -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
      -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
      -DCMAKE_SHARED_LINKER_FLAGS="-landroid -llog"   # jni 代码用 AAssetManager/__android_log
fi
ninja -j"$(nproc)" sherpa-onnx-jni

JNI_SO="$BUILD/lib/libsherpa-onnx-jni.so"
[ -f "$JNI_SO" ] || { echo "✗ 编译产物缺失: $JNI_SO"; exit 1; }

# 5. 替换 AAR 内的 jni 库（jni.so 换 QNN 版；libonnxruntime.so 换 1.27.1 匹配）
echo "📦 重打包 AAR..."
AAR_DIR="$WORK/aar-unpack"
rm -rf "$AAR_DIR" && mkdir -p "$AAR_DIR"
unzip -q "$AAR" -d "$AAR_DIR"
cp -v "$JNI_SO" "$AAR_DIR/jni/arm64-v8a/libsherpa-onnx-jni.so"
cp -v "$WORK/ort-android/jni/arm64-v8a/libonnxruntime.so" "$AAR_DIR/jni/arm64-v8a/libonnxruntime.so"
# c-api/cxx-api 库 App 未使用，删除以对齐构建配置（OFF）
rm -f "$AAR_DIR/jni/arm64-v8a/libsherpa-onnx-c-api.so" "$AAR_DIR/jni/arm64-v8a/libsherpa-onnx-cxx-api.so"
cd "$AAR_DIR" && rm -f "$AAR" && zip -q -r "$AAR" . && cd "$PROJ_DIR"

# 6. 校验
echo "✅ AAR 已更新: $AAR"
ls -lh "$AAR"
unzip -l "$AAR" | grep -E "jni/arm64-v8a"
echo ""
echo "下一步: ./gradlew assembleDebug（设置页选「QNN」即可）"
