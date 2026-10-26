package heycam.feature.scan.entry

import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImportTarget
import heycam.core.model.PageId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** ImportTarget as a string for SavedStateHandle, so a pending scan survives process death while Google's scanner is open. */
internal object ScanTargets {
    @Serializable
    private data class Dto(
        val kind: String,
        val title: String? = null,
        val folderId: Long? = null,
        val documentId: Long? = null,
        val atIndex: Int? = null,
        val pageId: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(target: ImportTarget): String = json.encodeToString(
        Dto.serializer(),
        when (target) {
            is ImportTarget.NewDocument -> Dto(NEW, title = target.title, folderId = target.folderId?.value)
            is ImportTarget.AppendTo -> Dto(APPEND, documentId = target.documentId.value, atIndex = target.atIndex)
            is ImportTarget.Replace -> Dto(REPLACE, pageId = target.pageId.value)
        },
    )

    fun decode(text: String?): ImportTarget? {
        if (text == null) return null
        val dto = try {
            json.decodeFromString(Dto.serializer(), text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return when (dto.kind) {
            NEW -> ImportTarget.NewDocument(dto.title, dto.folderId?.let(::FolderId))
            APPEND -> dto.documentId?.let { ImportTarget.AppendTo(DocumentId(it), dto.atIndex) }
            REPLACE -> dto.pageId?.let { ImportTarget.Replace(PageId(it)) }
            else -> null
        }
    }

    private const val NEW = "new"
    private const val APPEND = "append"
    private const val REPLACE = "replace"
}
