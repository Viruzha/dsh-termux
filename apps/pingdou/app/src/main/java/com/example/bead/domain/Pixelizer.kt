package com.example.bead.domain

import android.graphics.Bitmap
import com.example.bead.data.BeadColor
import kotlin.math.sqrt

object Pixelizer {

    data class UsageEntry(
        val color: String,
        val count: Int,
        val substitutedFrom: String? = null,
    )

    data class Allocation(
        val indices: IntArray,
        val entries: List<UsageEntry>,
        val shortages: Map<String, Int>,
        val remainingAfter: Map<String, Int>,
    )

    data class ConvertResult(
        val width: Int,
        val height: Int,
        val indices: IntArray,
        val preview: Bitmap,
        val entries: List<UsageEntry>,
        val shortages: Map<String, Int>,
    )

    // ---------------------------------------------------------
    // ① 面积平均下采样（比直接缩放的色彩更准确）
    // ---------------------------------------------------------
    fun downsample(src: Bitmap, dstW: Int, dstH: Int): IntArray {
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)

        val out = IntArray(dstW * dstH)
        val xr = sw.toDouble() / dstW
        val yr = sh.toDouble() / dstH

        for (dy in 0 until dstH) {
            var sy0 = (dy * yr).toInt()
            var sy1 = ((dy + 1) * yr).toInt()
            if (sy1 <= sy0) sy1 = sy0 + 1
            sy0 = sy0.coerceIn(0, sh - 1)
            sy1 = sy1.coerceIn(sy0 + 1, sh)

            for (dx in 0 until dstW) {
                var sx0 = (dx * xr).toInt()
                var sx1 = ((dx + 1) * xr).toInt()
                if (sx1 <= sx0) sx1 = sx0 + 1
                sx0 = sx0.coerceIn(0, sw - 1)
                sx1 = sx1.coerceIn(sx0 + 1, sw)

                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in sy0 until sy1) {
                    var idx = y * sw + sx0
                    for (x in sx0 until sx1) {
                        val p = srcPx[idx++]
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                if (n == 0) n = 1
                out[dy * dstW + dx] = (0xFF shl 24) or
                        ((r / n).toInt() shl 16) or
                        ((g / n).toInt() shl 8) or
                        (b / n).toInt()
            }
        }
        return out
    }

    // ---------------------------------------------------------
    // ② redmean 加权距离（比欧氏距离更贴近人眼感知）
    // ---------------------------------------------------------
    fun colorDist(a: BeadColor, b: BeadColor): Double {
        val rmean = (a.r + b.r) * 0.5
        val dr = (a.r - b.r).toDouble()
        val dg = (a.g - b.g).toDouble()
        val db = (a.b - b.b).toDouble()
        return sqrt(
            (2 + rmean / 256.0) * dr * dr +
                    4 * dg * dg +
                    (2 + (255 - rmean) / 256.0) * db * db
        )
    }

    fun nearestIndex(r: Int, g: Int, b: Int, palette: List<BeadColor>): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val rmean = (r + c.r) * 0.5
            val dr = (r - c.r).toDouble()
            val dg = (g - c.g).toDouble()
            val db = (b - c.b).toDouble()
            val d = (2 + rmean / 256.0) * dr * dr +
                    4 * dg * dg +
                    (2 + (255 - rmean) / 256.0) * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun quantize(pixels: IntArray, palette: List<BeadColor>): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = nearestIndex((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF, palette)
        }
        return out
    }

    // ---------------------------------------------------------
    // ③ Floyd–Steinberg 抖动（渐变自然）
    // ---------------------------------------------------------
    fun quantizeWithDither(
        pixels: IntArray, w: Int, h: Int, palette: List<BeadColor>
    ): IntArray {
        val buf = FloatArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            buf[i * 3] = ((p ushr 16) and 0xFF).toFloat()
            buf[i * 3 + 1] = ((p ushr 8) and 0xFF).toFloat()
            buf[i * 3 + 2] = (p and 0xFF).toFloat()
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val r = buf[idx * 3].toInt().coerceIn(0, 255)
                val g = buf[idx * 3 + 1].toInt().coerceIn(0, 255)
                val b = buf[idx * 3 + 2].toInt().coerceIn(0, 255)

                val pi = nearestIndex(r, g, b, palette)
                out[idx] = pi
                val c = palette[pi]
                val er = buf[idx * 3] - c.r
                val eg = buf[idx * 3 + 1] - c.g
                val eb = buf[idx * 3 + 2] - c.b

                if (x + 1 < w) addErr(buf, y * w + x + 1, er * 7 / 16, eg * 7 / 16, eb * 7 / 16)
                if (y + 1 < h) {
                    if (x > 0) addErr(buf, (y + 1) * w + x - 1, er * 3 / 16, eg * 3 / 16, eb * 3 / 16)
                    addErr(buf, (y + 1) * w + x, er * 5 / 16, eg * 5 / 16, eb * 5 / 16)
                    if (x + 1 < w) addErr(buf, (y + 1) * w + x + 1, er / 16, eg / 16, eb / 16)
                }
            }
        }
        return out
    }

    private fun addErr(buf: FloatArray, idx: Int, dr: Float, dg: Float, db: Float) {
        buf[idx * 3] += dr
        buf[idx * 3 + 1] += dg
        buf[idx * 3 + 2] += db
    }

    // ---------------------------------------------------------
    // ④ 按库存分配颜色（核心：不够就找相近色替换）
    // ---------------------------------------------------------
    fun allocate(
        indices: IntArray,
        palette: List<BeadColor>,
        remaining: Map<String, Int>,
        allowSub: Boolean,
        maxDist: Double,
    ): Allocation {
        val rem = remaining.toMutableMap()
        val counts = IntArray(palette.size)
        for (i in indices) counts[i]++

        // 用量大的颜色优先分配
        val order = counts.indices.sortedByDescending { counts[it] }

        val result = indices.copyOf()
        val entries = mutableListOf<UsageEntry>()
        val shortages = mutableMapOf<String, Int>()

        for (ci in order) {
            val need = counts[ci]
            if (need == 0) continue
            val name = palette[ci].name

            // --- 先用原色 ---
            val avail = rem[name] ?: 0
            val take = minOf(avail, need)
            if (take > 0) {
                rem[name] = avail - take
                entries += UsageEntry(name, take, null)
            }

            var gap = need - take
            if (gap <= 0) continue

            // --- 原色不够 ---
            if (!allowSub) {
                shortages[name] = gap
                continue
            }

            val target = palette[ci]
            // 收集该颜色的所有像素位置
            val pos = IntArray(need)
            var k = 0
            for (i in indices.indices) if (indices[i] == ci) pos[k++] = i

            var offset = take
            val tried = mutableSetOf<Int>()

            while (gap > 0) {
                // 在所有还有库存的颜色里，找距离最近的一个
                var bestJ = -1
                var bestD = Double.MAX_VALUE
                var bestRem = 0
                for (j in palette.indices) {
                    if (j == ci || j in tried) continue
                    val r = rem[palette[j].name] ?: 0
                    if (r <= 0) continue
                    val d = colorDist(target, palette[j])
                    if (d > maxDist) continue
                    if (d < bestD - 1e-6 || (d < bestD + 1e-6 && r > bestRem)) {
                        bestJ = j; bestD = d; bestRem = r
                    }
                }
                if (bestJ < 0) break

                val subName = palette[bestJ].name
                val r = rem[subName] ?: 0
                val use = minOf(r, gap)

                for (p in offset until offset + use) result[pos[p]] = bestJ
                rem[subName] = r - use
                entries += UsageEntry(subName, use, name)

                tried += bestJ
                offset += use
                gap -= use
            }

            if (gap > 0) shortages[name] = gap
        }

        return Allocation(result, entries, shortages, rem)
    }

    // ---------------------------------------------------------
    // ⑤ 渲染预览图（带网格）
    // ---------------------------------------------------------
    fun render(
        indices: IntArray, w: Int, h: Int,
        palette: List<BeadColor>, scale: Int, drawGrid: Boolean
    ): Bitmap {
        val W = w * scale
        val H = h * scale
        val px = IntArray(W * H)
        val gridColor = 0xFFB4B4B4.toInt()

        for (y in 0 until H) {
            val gy = y / scale
            for (x in 0 until W) {
                val gx = x / scale
                val isGrid = drawGrid && scale >= 4 && (x % scale == 0 || y % scale == 0)
                px[y * W + x] =
                    if (isGrid) gridColor
                    else palette[indices[gy * w + gx]].argb()
            }
        }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        return bmp
    }
}
