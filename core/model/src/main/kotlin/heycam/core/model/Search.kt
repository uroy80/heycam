package heycam.core.model

data class SearchQuery(
    val text: String,
    val folderId: FolderId? = null,   // null = everywhere (trash excluded)
    val inText: Boolean = true,
    val limit: Int = 50,
)

sealed interface SearchHit {
    val document: DocumentSummary

    data class TitleHit(override val document: DocumentSummary, val match: IntRange) : SearchHit
    data class TextHit(
        override val document: DocumentSummary,
        val pageId: PageId,
        val pageIndex: Int,
        val snippet: Snippet,
    ) : SearchHit
}

data class Snippet(val text: String, val matches: List<IntRange>)
