package heycam.core.designsystem.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** How a sample page looks under each of the four MVP filters (Original, Auto, Grey, B&W). */
enum class SampleLook(val paper: Color, val ink: Color, val text: Color, val signature: Color) {
    Original(Color(0xFFE8E1CF), Color(0xFF3A3530), Color(0xFF8C857A), Color(0xFF34509A)),
    Auto(Color(0xFFFCFBF8), Color(0xFF1B1A18), Color(0xFF6E6A63), Color(0xFF2A49A8)),
    Grey(Color(0xFFF2F2F2), Color(0xFF222222), Color(0xFF7A7A7A), Color(0xFF555555)),
    BlackWhite(Color.White, Color.Black, Color.Black, Color.Black),
}

/**
 * A stand-in for a scanned page in previews and screenshots: a heading bar, ruled text lines whose lengths vary with
 * [seed], and a signature on the first page. It is picture content, so it does not follow the theme.
 */
@Composable
fun SamplePage(modifier: Modifier = Modifier, seed: Int = 1, look: SampleLook = SampleLook.Auto) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(look.paper)
        val pad = size.width * 0.12f
        val lineGap = size.height * 0.045f
        val stroke = (size.height * 0.008f).coerceAtLeast(1f)
        drawRect(look.ink, Offset(pad, pad), Size(size.width * 0.5f, size.height * 0.035f))
        drawRect(look.text, Offset(pad, pad + size.height * 0.055f), Size(size.width * 0.32f, size.height * 0.012f))
        var y = pad + size.height * 0.12f
        var i = 0
        while (y < size.height - pad * 1.6f) {
            val paragraphEnd = (i + seed) % 6 == 5
            val length = if (paragraphEnd) 0.35f + ((i * 7 + seed * 3) % 4) * 0.08f else 0.92f - ((i * 5 + seed) % 3) * 0.04f
            drawLine(look.text, Offset(pad, y), Offset(pad + (size.width - 2 * pad) * length, y), stroke)
            y += if (paragraphEnd) lineGap * 1.6f else lineGap
            i++
        }
        if (seed == 1) {
            val sx = size.width * 0.55f
            val sy = size.height - pad
            val path = Path().apply {
                moveTo(sx, sy)
                cubicTo(sx + size.width * 0.05f, sy - size.height * 0.04f, sx + size.width * 0.08f, sy + size.height * 0.03f, sx + size.width * 0.13f, sy - size.height * 0.01f)
                cubicTo(sx + size.width * 0.17f, sy - size.height * 0.04f, sx + size.width * 0.2f, sy + size.height * 0.02f, sx + size.width * 0.26f, sy - size.height * 0.015f)
            }
            drawPath(path, look.signature, style = Stroke(stroke * 1.4f, cap = StrokeCap.Round))
        }
    }
}
