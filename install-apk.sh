#!/data/data/com.termux/files/usr/bin/bash
# 安装 debug APK 到本机（每个项目自带一份）
set -e
APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
    echo "❌ APK 不存在，请先运行: ./gradlew assembleDebug"
    exit 1
fi

# 读取应用名
APP_NAME=$(grep "rootProject.name" settings.gradle 2>/dev/null | sed "s/.*= *['\"]//;s/['\"].*//" || echo "app")
[ -z "$APP_NAME" ] && APP_NAME="app"

echo "📦 安装: $APK"
DEST="/storage/emulated/0/Download/${APP_NAME}-debug.apk"

# 方式1: 复制到 Download + am 调起系统安装界面
if cp "$APK" "$DEST" 2>/dev/null; then
    am start -a android.intent.action.VIEW \
        -d "file://$DEST" \
        -t "application/vnd.android.package-archive" 2>/dev/null && \
        echo "✅ 已调起安装界面" && exit 0
fi

# 方式2: termux-open
termux-open "$APK" 2>/dev/null && echo "✅ 已打开" && exit 0

echo "⚠️  请手动安装: $APK"
