package heycam.core.data

import heycam.core.model.Adjustments
import heycam.core.model.BoxPx
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PointN
import heycam.core.model.Quad
import heycam.core.model.Rotation
import heycam.core.model.TextLine
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val StorageJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

/** Enum lookup that survives values written by a newer app version (downgrade, device transfer). */
internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

internal fun rotationOf(degrees: Int): Rotation = Rotation.entries.firstOrNull { it.degrees == degrees } ?: Rotation.R0

@Serializable
internal data class QuadJson(
    val tlX: Float, val tlY: Float, val trX: Float, val trY: Float,
    val brX: Float, val brY: Float, val blX: Float, val blY: Float,
) {
    fun toQuad() = Quad(PointN(tlX, tlY), PointN(trX, trY), PointN(brX, brY), PointN(blX, blY))

    companion object {
        fun of(q: Quad) = QuadJson(
            q.topLeft.x, q.topLeft.y, q.topRight.x, q.topRight.y,
            q.bottomRight.x, q.bottomRight.y, q.bottomLeft.x, q.bottomLeft.y,
        )
    }
}

@Serializable
internal data class EditsJson(
    val rotation: Int = 0,
    val filter: String = PageFilter.ORIGINAL.name,
    val crop: QuadJson? = null,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
) {
    fun toEdits() = PageEdits(
        rotation = rotationOf(rotation),
        filter = enumOr(filter, PageFilter.ORIGINAL),
        crop = crop?.toQuad(),
        adjust = Adjustments(brightness, contrast),
    )

    companion object {
        fun of(e: PageEdits) = EditsJson(e.rotation.degrees, e.filter.name, e.crop?.let(QuadJson::of), e.adjust.brightness, e.adjust.contrast)
    }
}

@Serializable
internal data class LineJson(
    val t: String,
    val l: Float,
    val tp: Float,
    val r: Float,
    val b: Float,
    val a: Float = 0f,
    val c: Float? = null,
) {
    fun toLine() = TextLine(t, BoxPx(l, tp, r, b), a, c)

    companion object {
        fun of(line: TextLine) = LineJson(line.text.nfc(), line.box.left, line.box.top, line.box.right, line.box.bottom, line.angleDeg, line.confidence)
    }
}

internal object Codecs {
    private val lines = ListSerializer(LineJson.serializer())

    fun encodeLines(value: List<TextLine>): String = StorageJson.encodeToString(lines, value.map(LineJson::of))

    fun decodeLines(json: String): List<TextLine> =
        runCatching { StorageJson.decodeFromString(lines, json) }.getOrDefault(emptyList()).map(LineJson::toLine)

    fun encodeCrop(crop: Quad?): String? = crop?.let { StorageJson.encodeToString(QuadJson.serializer(), QuadJson.of(it)) }

    fun decodeCrop(json: String?): Quad? =
        json?.let { runCatching { StorageJson.decodeFromString(QuadJson.serializer(), it).toQuad() }.getOrNull() }
}
