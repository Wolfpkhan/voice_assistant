package com.sherva.voiceassistant.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ★ 图片附件处理：压缩 + base64 编码为 OpenAI vision 的 data URL。
 *
 * 策略：
 *  - 长边缩到 1024px（vision 模型的常用分辨率，再大识别率不升 token 猛涨）
 *  - JPEG 80% 质量
 *  - 实测 3-5MB 原图 → 150-250KB → base64 后 ~200-330KB（请求体安全范围）
 */
object ImageAttachment {

    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 80

    /** 本地图片扩展名白名单（read 工具/pi 视觉链路支持的格式） */
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    /** 判断字符串是否像本地文件路径（供发送前扫描消息文本用）。 */
    private val pathRegex = Regex("""(/(?:storage|sdcard)/\S+\.\w{2,5})""")

    /** 从消息文本中提取所有本地文件路径（不区分图片/其它）。 */
    fun extractPaths(text: String): List<String> =
        pathRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** 提取文本中的本地图片路径。 */
    fun extractImagePaths(text: String): List<String> =
        extractPaths(text).filter { isImagePath(it) && File(it).exists() }

    /**
     * 图片文件 → 压缩 → base64 data URL。
     * 失败返回 null（文件不存在/解码失败/超限）。
     */
    fun toDataUrl(path: String, maxBytes: Int = 8 * 1024 * 1024): String? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() > maxBytes) return null

            // 解码 + 按长边缩放
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sample = 1
            var w = opts.outWidth; var h = opts.outHeight
            while (maxOf(w, h) / 2 >= MAX_EDGE) { sample *= 2; w /= 2; h /= 2 }

            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample
            }) ?: return null

            // 精确缩放到长边 ≤ MAX_EDGE（inSampleSize 是 2 的幂，还差一点）
            val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp

            // JPEG 压缩
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            if (bmp !== scaled) bmp.recycle()
            if (scaled !== bmp) scaled.recycle()

            "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            null
        }
    }
}
