package heycam.core.designsystem.catalog

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.A4_PORTRAIT_RATIO
import heycam.core.designsystem.ActionBarItem
import heycam.core.designsystem.ArticleTopBar
import heycam.core.designsystem.BottomActionBar
import heycam.core.designsystem.Dateline
import heycam.core.designsystem.DocumentRow
import heycam.core.designsystem.EmptyState
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.HeycamTextField
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.IconButton
import heycam.core.designsystem.Masthead
import heycam.core.designsystem.Nameplate
import heycam.core.designsystem.Note
import heycam.core.designsystem.PrimaryButton
import heycam.core.designsystem.SecondaryButton
import heycam.core.designsystem.SectionHeader
import heycam.core.designsystem.SectionTabs
import heycam.core.designsystem.StatusBanner
import heycam.core.designsystem.TextHitRow
import heycam.core.designsystem.ValueRow

/*
 * Compose sketches for the MVP screens that have no HTML mockup (PLAN 5: S3 Search, S5 Trash, S6 scanner download,
 * S9 page viewer, S14 About / Licences / Storage). They show how the screen owners put the shared components
 * together; the owners build the real screens in their feature modules. Copy follows the COPY RULE.
 */

private val noop: () -> Unit = {}

@Preview(name = "Day paper", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Night ink", widthDp = 390, heightDp = 844, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ScreenPreviews

@Composable
private fun Screen(background: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Heycam.colors
    Column(Modifier.fillMaxSize().background(if (background) c.paper else c.canvas), content = content)
}

/** S3 Search: the query field in the top bar, then title hits and hits inside recognised text. */
@Composable
fun SearchSketch() = Screen {
    Row(Modifier.fillMaxWidth().padding(end = HeycamSize.gutter, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(HeycamIcons.Back, "Back", noop)
        HeycamTextField(
            value = "deposit",
            onValueChange = {},
            leadingIcon = HeycamIcons.Search,
            trailing = { IconButton(HeycamIcons.Close, "Clear", noop) },
            modifier = Modifier.weight(1f),
        )
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        SectionHeader("Titles", count = 1, modifier = Modifier.padding(horizontal = HeycamSize.gutter))
        DocumentRow(
            title = "Deposit receipt",
            meta = "Fri 2 Oct · 10:14 · 180 KB",
            pageCount = 1,
            onClick = noop,
            thumbnail = { SamplePage(seed = 4) },
        )
        Spacer(Modifier.height(16.dp))
        SectionHeader("In the text", count = 2, modifier = Modifier.padding(horizontal = HeycamSize.gutter))
        val snippet = "…the tenant will pay a deposit of four weeks' rent before moving in…"
        val at = snippet.indexOf("deposit")
        TextHitRow(
            title = "Lease agreement, flat 4B",
            snippet = snippet,
            match = at until at + 7,
            pageNumber = 2,
            onClick = noop,
        )
        TextHitRow(
            title = "Letter from the bank",
            snippet = "…your deposit is held in a protection scheme until the end…",
            match = 6 until 13,
            pageNumber = 1,
            onClick = noop,
        )
    }
}

/** S5 Trash: deleted documents, one selected, with Restore and Delete in the bottom bar. */
@Composable
fun TrashSketch() = Screen {
    ArticleTopBar(title = "Trash", onBack = noop, actions = { IconButton(HeycamIcons.Delete, "Empty trash", noop) })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        DocumentRow(
            title = "Old phone bill",
            meta = "Deleted Mon 19 Oct",
            pageCount = 2,
            onClick = noop,
            selected = true,
            thumbnail = { SamplePage(seed = 5) },
        )
        DocumentRow(
            title = "Parking ticket",
            meta = "Deleted Sat 17 Oct",
            pageCount = 1,
            onClick = noop,
            selectionMode = true,
            thumbnail = { SamplePage(seed = 6) },
        )
        DocumentRow(
            title = "Recipe card",
            meta = "Deleted Wed 14 Oct",
            pageCount = 1,
            onClick = noop,
            selectionMode = true,
            thumbnail = { SamplePage(seed = 2, look = SampleLook.Grey) },
        )
    }
    BottomActionBar {
        ActionBarItem(HeycamIcons.Restore, "Restore", noop)
        ActionBarItem(HeycamIcons.Delete, "Delete", noop, destructive = true)
    }
}

/** S6 First run on Home while the scanner downloads, or after the download failed. */
@Composable
fun ScannerDownloadSketch(failed: Boolean = false) = Screen {
    Masthead(onSearch = noop, onSettings = noop)
    Column(Modifier.padding(horizontal = HeycamSize.gutter)) {
        Dateline(end = "Tue 20 Oct", middle = "0 docs")
        SectionTabs(tabs = listOf("Contents", "Tools"), selected = 0, onSelect = {})
        Spacer(Modifier.height(12.dp))
        if (failed) {
            StatusBanner("No connection", isError = true, actionLabel = "Retry", onAction = noop)
        } else {
            StatusBanner("Getting the scanner", icon = HeycamIcons.Scanner, progress = 0.42f)
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        EmptyState(
            kicker = "Nothing scanned yet",
            headline = "Scan your first page.",
            actions = {
                PrimaryButton("Scan", noop, icon = HeycamIcons.Camera, large = true, enabled = !failed, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Import photos", noop, icon = HeycamIcons.Gallery, large = true, modifier = Modifier.fillMaxWidth())
            },
            footer = { Note("Scans stay on this phone") },
        )
    }
}

/** S9 Page viewer: the page full-bleed on canvas, with Rotate, Filter, Retake and Delete. */
@Composable
fun PageViewerSketch() = Screen(background = false) {
    ArticleTopBar(title = "Page 2 of 8", onBack = noop, actions = { IconButton(HeycamIcons.Share, "Share", noop) })
    Box(Modifier.weight(1f).fillMaxWidth().padding(HeycamSize.gutter), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(A4_PORTRAIT_RATIO)
                .border(HeycamSize.hair, Heycam.colors.ink),
        ) { SamplePage(seed = 2) }
    }
    BottomActionBar {
        ActionBarItem(HeycamIcons.Rotate, "Rotate", noop)
        ActionBarItem(HeycamIcons.Filter, "Filter", noop)
        ActionBarItem(HeycamIcons.Camera, "Retake", noop)
        ActionBarItem(HeycamIcons.Delete, "Delete", noop, destructive = true)
    }
}

/** S14 About: nameplate, version, source and licences. */
@Composable
fun AboutSketch() = Screen {
    ArticleTopBar(title = "About", onBack = noop)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Nameplate()
            Spacer(Modifier.height(6.dp))
            Text("1.0.0", style = Heycam.type.figure, color = Heycam.colors.muted)
        }
        ValueRow("Licence", value = "Apache-2.0")
        ValueRow("Source code", onClick = noop, external = true)
        ValueRow("Privacy policy", onClick = noop, external = true)
        ValueRow("Open-source licences", onClick = noop)
        Spacer(Modifier.height(20.dp))
        Note("Uses Google ML Kit", icon = HeycamIcons.Info, modifier = Modifier.padding(horizontal = HeycamSize.gutter))
    }
}

/** S14 Licences: fonts and libraries with their licence in mono; each row opens the full text. */
@Composable
fun LicencesSketch() = Screen {
    ArticleTopBar(title = "Licences", onBack = noop)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        SectionHeader("Fonts", modifier = Modifier.padding(horizontal = HeycamSize.gutter))
        ValueRow("Fraunces", value = "OFL-1.1", onClick = noop)
        ValueRow("Libre Franklin", value = "OFL-1.1", onClick = noop)
        ValueRow("IBM Plex Mono", value = "OFL-1.1", onClick = noop)
        Spacer(Modifier.height(16.dp))
        SectionHeader("Libraries", modifier = Modifier.padding(horizontal = HeycamSize.gutter))
        ValueRow("Jetpack Compose", value = "Apache-2.0", onClick = noop)
        ValueRow("Material Symbols", value = "Apache-2.0", onClick = noop)
        ValueRow("PDFBox-Android", value = "Apache-2.0", onClick = noop)
        ValueRow("Google ML Kit", onClick = noop, external = true)
    }
}

/** S14 Storage: space used by scans, trash and temporary files, with the two clean-up actions. */
@Composable
fun StorageSketch() = Screen {
    ArticleTopBar(title = "Storage", onBack = noop)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        ValueRow("Scans", value = "182 MB")
        ValueRow("Trash", value = "12 MB")
        ValueRow("Temporary files", value = "3.1 MB")
        Column(
            Modifier.padding(HeycamSize.gutter),
            verticalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing),
        ) {
            SecondaryButton("Empty trash", noop, icon = HeycamIcons.Delete, modifier = Modifier.fillMaxWidth())
            SecondaryButton("Clear temporary files", noop, modifier = Modifier.fillMaxWidth())
        }
    }
}

@ScreenPreviews @Composable private fun SearchPreview() = HeycamTheme { SearchSketch() }
@ScreenPreviews @Composable private fun TrashPreview() = HeycamTheme { TrashSketch() }
@ScreenPreviews @Composable private fun ScannerDownloadPreview() = HeycamTheme { ScannerDownloadSketch() }
@ScreenPreviews @Composable private fun ScannerDownloadFailedPreview() = HeycamTheme { ScannerDownloadSketch(failed = true) }
@ScreenPreviews @Composable private fun PageViewerPreview() = HeycamTheme { PageViewerSketch() }
@ScreenPreviews @Composable private fun AboutPreview() = HeycamTheme { AboutSketch() }
@ScreenPreviews @Composable private fun LicencesPreview() = HeycamTheme { LicencesSketch() }
@ScreenPreviews @Composable private fun StoragePreview() = HeycamTheme { StorageSketch() }
