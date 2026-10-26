package heycam.feature.scan.review

import heycam.core.model.ImageInfo
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.Rotation
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage

/** The filters Review offers in MVP, in strip order (Whiteboard arrives in v1.1). */
internal val ReviewFilters = listOf(PageFilter.ORIGINAL, PageFilter.AUTO, PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE)

/** Applies Settings' default filter to freshly staged pages; "as scanned" leaves them untouched. */
internal fun ScanSession.withDefaultFilter(filter: PageFilter): ScanSession =
    if (filter == PageFilter.ORIGINAL) this else copy(pages = pages.map { it.copy(edits = it.edits.copy(filter = filter)) })

internal fun List<StagedImage>.asPages(filter: PageFilter): List<StagedPage> =
    map { StagedPage(it).let { p -> if (filter == PageFilter.ORIGINAL) p else p.copy(edits = p.edits.copy(filter = filter)) } }

/** A staged file seen as the renderer's input. */
internal fun StagedImage.asImageInfo(): ImageInfo = ImageInfo(file.absolutePath, widthPx, heightPx, file.length(), mime)

/** Pixel size after rotation, for the "2480 × 3508" figure. */
internal fun StagedPage.displaySize(): Pair<Int, Int> =
    if (edits.rotation == Rotation.R90 || edits.rotation == Rotation.R270) image.heightPx to image.widthPx
    else image.widthPx to image.heightPx

/** Photos and shared images are never auto-cropped, so Review points at the scanner's gallery import. */
internal val ScanSession.hasPhotos: Boolean
    get() = pages.any { it.image.origin == PageOrigin.PHOTO_PICKER || it.image.origin == PageOrigin.SHARE }
