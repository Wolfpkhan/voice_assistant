#!/data/data/com.termux/files/usr/bin/bash
# 安装 debug APK 到本机（每个项目自带一份）
#
# vivo / 严格 SELinux 环境下，PackageInstaller 不接受 file:// URI
# （Android 7+ 强制 content:// FileProvider）。绕开方案：
#   1. 先用 cmd package install 尝试（部分 vivo ROM 已放开）
#   2. 失败则用 am start 调起系统 PackageInstaller，用户手动确认
#      （弹出系统对话框时仍需用户点「安装」按钮）
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

# 方式1: cmd package install (vivo 上对 system_server fuse: 路径有 SELinux 限制)
#        先尝试复制到应用私有目录绕开 fuse:
if cp "$APK" "$DEST" 2>/dev/null; then
    # 先尝试直接 install（adb 风格，部分 ROM 允许）
    if cmd package install -r -d "$DEST" 2>/dev/null; then
        echo "✅ cmd package install 成功"
        exit 0
    fi
    # 失败则调起系统 PackageInstaller 用户手动确认
    am start -W -a android.intent.action.VIEW \
        -d "file://$DEST" \
        -t "application/vnd.android.package-archive" \
        --grant-read-uri-permission 2>&1 | tail -2
    echo ""
    echo "⚠️  系统安装界面已调起，请在手机上点「安装」（vivo 上 file:// 可能被拒 → 走到方式2）"
fi

# 方式2: termux-open（尝试调起系统 PackageInstaller）
if command -v termux-open >/dev/null 2>&1; then
    if termux-open "$DEST" 2>&1; then
        echo "✅ termux-open 已调起（请在手机上点「安装」）"
        exit 0
    fi
fi

# 方式3: 手动复制提示
echo ""
echo "💡 自动安装失败。请手动安装："
echo "   1. 打开手机文件管理器，进入 Download 目录"
echo "   2. 找到 $DEST（文件名：${APP_NAME}-debug.apk）"
echo "   3. 点击该 APK，系统会弹安装界面"
echo "   4. 允许「安装未知应用」（一次性授权）"
echo "   5. 点「安装」完成"