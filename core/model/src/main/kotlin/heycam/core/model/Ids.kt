package heycam.core.model

/** Room row ids, wrapped so they cannot be mixed up. Routes carry the raw Long. */
@JvmInline value class DocumentId(val value: Long)
@JvmInline value class PageId(val value: Long)
@JvmInline value class FolderId(val value: Long)
