package com.example.wechat_senior_helper.ocr

import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs

object OcrLineMatcher {

    /** 匹配阈值：Levenshtein 相似度低于此值视为不匹配。误判多→提高至 0.82；漏判多→降至 0.75 */
    const val MATCH_THRESHOLD = 0.78f

    fun findBestContactLine(text: Text, target: String, threshold: Float = MATCH_THRESHOLD): OcrHit? {
        val normalizedTarget = normalize(target)
        var best: OcrHit? = null

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val raw = line.text ?: continue
                val rect = line.boundingBox ?: line.cornerPoints?.toRect() ?: continue
                val score = similarity(normalize(raw), normalizedTarget)

                if (score > (best?.score ?: 0f)) {
                    best = OcrHit(raw, rect, score)
                }
            }
        }

        return best?.takeIf { it.score >= threshold }
    }

    fun extractLines(text: Text): List<OcrLine> {
        val lines = mutableListOf<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val raw = line.text ?: continue
                val rect = line.boundingBox ?: line.cornerPoints?.toRect() ?: continue
                lines += OcrLine(raw, rect)
            }
        }
        return lines.sortedBy { it.rect.top }
    }

    private fun normalize(s: String): String {
        return s.trim()
            .replace("\\s+".toRegex(), "")
            .replace("：", "")
            .replace(":", "")
            .lowercase(Locale.ROOT)
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        if (a.contains(b) || b.contains(a)) return 0.95f

        val dist = levenshtein(a, b).toFloat()
        val maxLen = maxOf(a.length, b.length).toFloat()
        return 1f - (dist / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var prev = dp[0]
            dp[0] = i + 1
            for (j in b.indices) {
                val temp = dp[j + 1]
                val cost = if (a[i] == b[j]) 0 else 1
                dp[j + 1] = minOf(
                    dp[j + 1] + 1,
                    dp[j] + 1,
                    prev + cost
                )
                prev = temp
            }
        }
        return dp[b.length]
    }

    private fun Array<Point>.toRect(): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (p in this) {
            left = minOf(left, p.x)
            top = minOf(top, p.y)
            right = maxOf(right, p.x)
            bottom = maxOf(bottom, p.y)
        }

        return Rect(left, top, right, bottom)
    }
}
