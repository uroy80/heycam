package heycam.core.imaging

import heycam.core.model.Adjustments
import heycam.core.model.PageFilter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The page filters of PLAN 3.11 on ARGB_8888 pixels, in place. Integer arithmetic wherever it matters, so the same
 * input gives the same output on every device and on the JVM.
 *
 * - ORIGINAL: identity.
 * - AUTO: per-channel stretch from the 1st percentile to the white point of the brightest 5% (which also white-balances
 *   the paper), then saturation x1.1. When the brightest 5% is clearly coloured it is not paper, so the balance fades
 *   out ([castCorrection]) and colour pages keep their hues.
 * - GRAYSCALE: Rec.709 luminance, then a 1%/99% stretch.
 * - BLACK_WHITE: luminance, background normalisation, then Sauvola thresholding from block integral images.
 * - WHITEBOARD (v1.1): background normalisation per channel, then saturation x1.4.
 *
 * Every stretch is gain-limited ([MIN_SPAN]) so a blank or dark page does not turn into amplified noise.
 */
class DocumentPixelFilters @Inject constructor() : PixelFilters {

    override fun apply(argb: IntArray, width: Int, height: Int, filter: PageFilter, adjust: Adjustments) {
        require(width > 0 && height > 0) { "empty image" }
        require(argb.size == width * height) { "pixel count does not match the size" }
        when (filter) {
            PageFilter.ORIGINAL -> Unit
            PageFilter.AUTO -> auto(argb)
            PageFilter.GRAYSCALE -> grayscale(argb)
            PageFilter.BLACK_WHITE -> blackWhite(argb, width, height)
            PageFilter.WHITEBOARD -> whiteboard(argb, width, height)
        }
        if (adjust != Adjustments.NONE) adjust(argb, adjust)
    }

    private fun auto(px: IntArray) {
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        val hy = IntArray(256)
        for (c in px) {
            hr[red(c)]++
            hg[green(c)]++
            hb[blue(c)]++
            hy[luma(c)]++
        }
        val n = px.size.toLong()
        val brightFrom = percentile(hy, n, BRIGHT_SHARE)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var count = 0L
        for (c in px) {
            if (luma(c) >= brightFrom) {
                sr += red(c)
                sg += green(c)
                sb += blue(c)
                count++
            }
        }
        val white = intArrayOf((sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt())
        val black = intArrayOf(percentile(hr, n, LOW), percentile(hg, n, LOW), percentile(hb, n, LOW))
        // Per-channel points white-balance the page on the assumption that its brightest area is paper. Toward a
        // clearly coloured brightest area (a yellow sky, a coloured card) every channel moves to the same points,
        // which stretches contrast but keeps each colour's hue.
        val strength = castCorrection(white.min(), white.max())
        val sharedWhite = white.max()
        val sharedBlack = black.min()
        val (lutR, lutG, lutB) = Array(3) { ch ->
            stretch(
                sharedBlack + ((black[ch] - sharedBlack) * strength shr 8),
                sharedWhite - ((sharedWhite - white[ch]) * strength shr 8),
            )
        }
        for (i in px.indices) {
            val c = px[i]
            px[i] = saturate(alpha(c), lutR[red(c)], lutG[green(c)], lutB[blue(c)], SATURATION_AUTO)
        }
    }

    private fun grayscale(px: IntArray) {
        val hy = IntArray(256)
        for (i in px.indices) {
            val y = luma(px[i])
            hy[y]++
            px[i] = (px[i] and ALPHA_MASK) or y
        }
        val n = px.size.toLong()
        val lut = stretch(percentile(hy, n, LOW), percentile(hy, n, HIGH))
        for (i in px.indices) {
            val c = px[i]
            val v = lut[c and 0xFF]
            px[i] = (c and ALPHA_MASK) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun blackWhite(px: IntArray, width: Int, height: Int) {
        val hy = IntArray(256)
        for (i in px.indices) {
            val y = luma(px[i])
            hy[y]++
            px[i] = gray(y)
        }
        val floor = backgroundFloor(hy, px.size.toLong())
        val background = Background.estimate(px, width, height)
        val row = IntArray(width)
        for (y in 0 until height) {
            background.row(y, row)
            val base = y * width
            for (x in 0 until width) {
                px[base + x] = gray(min(255, (px[base + x] and 0xFF) * 255 / max(row[x], floor)))
            }
        }
        Sauvola.binarize(px, width, height)
    }

    private fun whiteboard(px: IntArray, width: Int, height: Int) {
        val hy = IntArray(256)
        for (c in px) hy[luma(c)]++
        val floor = backgroundFloor(hy, px.size.toLong())
        val background = Background.estimate(px, width, height)
        val row = IntArray(width)
        for (y in 0 until height) {
            background.row(y, row)
            val base = y * width
            for (x in 0 until width) {
                val c = px[base + x]
                val bg = max(row[x], floor)
                px[base + x] = saturate(
                    alpha(c),
                    min(255, red(c) * 255 / bg),
                    min(255, green(c) * 255 / bg),
                    min(255, blue(c) * 255 / bg),
                    SATURATION_WHITEBOARD,
                )
            }
        }
    }

    private fun adjust(px: IntArray, adjust: Adjustments) {
        val factor = 1f + adjust.contrast.coerceIn(-1f, 1f)
        val offset = adjust.brightness.coerceIn(-1f, 1f) * 128f
        val lut = IntArray(256) { v -> ((v - 128) * factor + 128f + offset).roundToInt().coerceIn(0, 255) }
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and ALPHA_MASK) or (lut[red(c)] shl 16) or (lut[green(c)] shl 8) or lut[blue(c)]
        }
    }

    internal companion object {
        const val LOW = 0.01
        const val HIGH = 0.99
        const val BRIGHT_SHARE = 0.95

        /** Smallest input span a stretch maps onto 0..255: at most x2 gain. */
        const val MIN_SPAN = 128

        /**
         * Neutrality of the brightest area, in 1/256: its weakest channel mean over its strongest. Tinted paper scores
         * about 0.85 (the test page's 236/228/205), a yellow sky or a coloured card 0.5 and below.
         */
        const val NEUTRAL_FULL = 192
        const val NEUTRAL_NONE = 128

        /**
         * How much of the colour cast AUTO removes, in 1/256, from the brightest area's [weakest] and [strongest]
         * channel means: all of it at [NEUTRAL_FULL] neutrality and above, none at [NEUTRAL_NONE] and below, and a
         * linear share in between so similar pages get similar results.
         */
        fun castCorrection(weakest: Int, strongest: Int): Int {
            if (strongest <= 0) return 256
            val neutral = weakest * 256 / strongest
            return ((neutral - NEUTRAL_NONE) * 256 / (NEUTRAL_FULL - NEUTRAL_NONE)).coerceIn(0, 256)
        }

        /**
         * The normalisation never divides by less than this share (in 1/256) of the page's paper level (its 90th
         * percentile luminance): shadows are evened out, but a large dark area (a photo, a black bar) is not lifted to
         * white.
         */
        const val BACKGROUND_FLOOR_SHARE = 154
        const val PAPER_SHARE = 0.90

        fun backgroundFloor(lumaHistogram: IntArray, total: Long): Int =
            max(1, percentile(lumaHistogram, total, PAPER_SHARE) * BACKGROUND_FLOOR_SHARE shr 8)

        /** Saturation factors in 1/256 units: 1.1 and 1.4. */
        const val SATURATION_AUTO = 282
        const val SATURATION_WHITEBOARD = 358

        const val ALPHA_MASK = -0x1000000

        /** Smallest v whose cumulative count reaches [share] of [total]. */
        fun percentile(histogram: IntArray, total: Long, share: Double): Int {
            val target = max(1L, kotlin.math.ceil(share * total).toLong())
            var cumulative = 0L
            for (v in 0..255) {
                cumulative += histogram[v]
                if (cumulative >= target) return v
            }
            return 255
        }

        /** Maps [low]..[high] onto 0..255, with the span widened (downwards) to at least [MIN_SPAN]. */
        fun stretch(low: Int, high: Int): IntArray {
            val top = max(high, MIN_SPAN)
            val bottom = max(0, min(low, top - MIN_SPAN))
            val span = top - bottom
            return IntArray(256) { v -> (((v - bottom) * 255 + span / 2) / span).coerceIn(0, 255) }
        }

        fun saturate(a: Int, r: Int, g: Int, b: Int, factor256: Int): Int {
            val y = luma(r, g, b)
            val rs = (y + (((r - y) * factor256) shr 8)).coerceIn(0, 255)
            val gs = (y + (((g - y) * factor256) shr 8)).coerceIn(0, 255)
            val bs = (y + (((b - y) * factor256) shr 8)).coerceIn(0, 255)
            return (a shl 24) or (rs shl 16) or (gs shl 8) or bs
        }
    }
}

internal fun alpha(c: Int) = c ushr 24
internal fun red(c: Int) = (c shr 16) and 0xFF
internal fun green(c: Int) = (c shr 8) and 0xFF
internal fun blue(c: Int) = c and 0xFF

/** Rec.709 luminance in integer weights that sum to 256. */
internal fun luma(r: Int, g: Int, b: Int) = (54 * r + 183 * g + 19 * b + 128) shr 8
internal fun luma(c: Int) = luma(red(c), green(c), blue(c))

/** Opaque grey; its [luma] is [v] again. */
internal fun gray(v: Int) = -0x1000000 or (v shl 16) or (v shl 8) or v

/**
 * Paper brightness across the page: the 90th-percentile luminance of each cell (text is a minority of a cell's
 * pixels), dilated by one cell, box-blurred twice, then read back bilinearly between cell centres.
 */
internal class Background private constructor(
    private val values: IntArray,
    private val cols: Int,
    private val rows: Int,
    private val cell: Int,
    width: Int,
) {
    private val c0 = IntArray(width)
    private val c1 = IntArray(width)
    private val wx = IntArray(width)

    init {
        for (x in 0 until width) {
            val (i0, i1, t) = axis(x, cols)
            c0[x] = i0
            c1[x] = i1
            wx[x] = t
        }
    }

    /** Fills [out] with the background of pixel row [y]. */
    fun row(y: Int, out: IntArray) {
        val (r0, r1, wy) = axis(y, rows)
        val top = r0 * cols
        val bottom = r1 * cols
        for (x in out.indices) {
            val w = wx[x]
            val upper = values[top + c0[x]] * (256 - w) + values[top + c1[x]] * w
            val lower = values[bottom + c0[x]] * (256 - w) + values[bottom + c1[x]] * w
            out[x] = (upper * (256 - wy) + lower * wy + (1 shl 15)) shr 16
        }
    }

    /** Neighbouring cell indices and the weight (0..255) of the second, for a pixel centre on one axis. */
    private fun axis(p: Int, count: Int): Triple<Int, Int, Int> {
        val pos = (2 * p + 1) * 128 / cell - 128
        if (pos <= 0) return Triple(0, 0, 0)
        val i0 = pos shr 8
        if (i0 >= count - 1) return Triple(count - 1, count - 1, 0)
        return Triple(i0, i0 + 1, pos and 0xFF)
    }

    companion object {
        private const val GRID = 64
        private const val MIN_CELL = 8
        private const val SHARE = 0.90

        fun estimate(px: IntArray, width: Int, height: Int): Background {
            val cell = max(MIN_CELL, (max(width, height) + GRID - 1) / GRID)
            val cols = (width + cell - 1) / cell
            val rows = (height + cell - 1) / cell
            val values = IntArray(cols * rows)
            val hist = IntArray(cols * 256)
            for (r in 0 until rows) {
                hist.fill(0)
                val y1 = min(height, (r + 1) * cell)
                for (y in r * cell until y1) {
                    val base = y * width
                    for (x in 0 until width) hist[(x / cell) * 256 + luma(px[base + x])]++
                }
                for (c in 0 until cols) {
                    val count = (min(width, (c + 1) * cell) - c * cell).toLong() * (y1 - r * cell)
                    values[r * cols + c] = percentileAt(hist, c * 256, count)
                }
            }
            val dilated = dilate(values, cols, rows)
            val blurred = boxBlur(boxBlur(dilated, cols, rows), cols, rows)
            return Background(blurred, cols, rows, cell, width)
        }

        private fun percentileAt(hist: IntArray, offset: Int, total: Long): Int {
            val target = max(1L, kotlin.math.ceil(SHARE * total).toLong())
            var cumulative = 0L
            for (v in 0..255) {
                cumulative += hist[offset + v]
                if (cumulative >= target) return v
            }
            return 255
        }

        private fun dilate(v: IntArray, cols: Int, rows: Int) = neighbourhood(v, cols, rows) { values, _ -> values.max() }

        private fun boxBlur(v: IntArray, cols: Int, rows: Int) =
            neighbourhood(v, cols, rows) { values, count -> (values.sum() + count / 2) / count }

        /** Applies [combine] to each cell's 3x3 neighbourhood (clipped at the edges). */
        private inline fun neighbourhood(v: IntArray, cols: Int, rows: Int, combine: (IntArray, Int) -> Int): IntArray {
            val out = IntArray(v.size)
            val window = IntArray(9)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    var n = 0
                    for (dr in -1..1) {
                        val rr = r + dr
                        if (rr !in 0 until rows) continue
                        for (dc in -1..1) {
                            val cc = c + dc
                            if (cc !in 0 until cols) continue
                            window[n++] = v[rr * cols + cc]
                        }
                    }
                    out[r * cols + c] = combine(window.copyOf(n), n)
                }
            }
            return out
        }
    }
}

/**
 * Sauvola thresholding, T = m * (1 + k * (s / R - 1)), with local mean m and deviation s taken over a window about a
 * twentieth of the short edge. Statistics come from integral images over small blocks, so memory stays a few MB
 * even at 16 MP. Expects opaque grey pixels; writes pure black and white.
 */
internal object Sauvola {
    private const val K = 0.2
    private const val R = 128.0
    private const val MIN_WINDOW = 15
    private const val WINDOW_DIVISOR = 20
    private const val BLOCK_DIVISOR = 512

    /** Normalised pixels darker than this are ink even inside a large uniform dark area, where Sauvola alone says paper. */
    private const val INK = 128

    private const val BLACK = -0x1000000
    private const val WHITE = -0x1

    fun binarize(px: IntArray, width: Int, height: Int) {
        val shortEdge = min(width, height)
        val block = max(1, shortEdge / BLOCK_DIVISOR)
        val gw = (width + block - 1) / block
        val gh = (height + block - 1) / block
        val stride = gw + 1
        val sum = LongArray(stride * (gh + 1))
        val sumSq = LongArray(stride * (gh + 1))
        val blockSum = LongArray(gw)
        val blockSq = LongArray(gw)
        for (by in 0 until gh) {
            blockSum.fill(0)
            blockSq.fill(0)
            for (y in by * block until min(height, (by + 1) * block)) {
                val base = y * width
                for (x in 0 until width) {
                    val v = (px[base + x] and 0xFF).toLong()
                    blockSum[x / block] += v
                    blockSq[x / block] += v * v
                }
            }
            var rowSum = 0L
            var rowSq = 0L
            for (bx in 0 until gw) {
                rowSum += blockSum[bx]
                rowSq += blockSq[bx]
                val i = (by + 1) * stride + bx + 1
                sum[i] = sum[i - stride] + rowSum
                sumSq[i] = sumSq[i - stride] + rowSq
            }
        }
        val radius = max(1, max(MIN_WINDOW, shortEdge / WINDOW_DIVISOR) / 2 / block)
        val threshold = FloatArray(gw * gh)
        for (by in 0 until gh) {
            val y0 = max(0, by - radius)
            val y1 = min(gh, by + radius + 1)
            val pixelRows = (min(height, y1 * block) - y0 * block).toLong()
            for (bx in 0 until gw) {
                val x0 = max(0, bx - radius)
                val x1 = min(gw, bx + radius + 1)
                val count = (min(width, x1 * block) - x0 * block) * pixelRows
                val s1 = sum[y1 * stride + x1] - sum[y0 * stride + x1] - sum[y1 * stride + x0] + sum[y0 * stride + x0]
                val s2 = sumSq[y1 * stride + x1] - sumSq[y0 * stride + x1] - sumSq[y1 * stride + x0] + sumSq[y0 * stride + x0]
                val mean = s1.toDouble() / count
                val deviation = sqrt(max(0.0, s2.toDouble() / count - mean * mean))
                threshold[by * gw + bx] = (mean * (1 + K * (deviation / R - 1))).toFloat()
            }
        }
        for (y in 0 until height) {
            val base = y * width
            val rowBase = (y / block) * gw
            for (x in 0 until width) {
                val v = px[base + x] and 0xFF
                px[base + x] = if (v < INK || v <= threshold[rowBase + x / block]) BLACK else WHITE
            }
        }
    }
}
