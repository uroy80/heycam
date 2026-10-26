package heycam.core.designsystem.catalog

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import heycam.core.designsystem.ActionBarItem
import heycam.core.designsystem.ArticleTopBar
import heycam.core.designsystem.BottomActionBar
import heycam.core.designsystem.Chip
import heycam.core.designsystem.Dateline
import heycam.core.designsystem.DialogCard
import heycam.core.designsystem.DocumentRow
import heycam.core.designsystem.EmptyPicture
import heycam.core.designsystem.FilterStrip
import heycam.core.designsystem.FindBar
import heycam.core.designsystem.Flag
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.HeycamSnackbar
import heycam.core.designsystem.HeycamSnackbarVisuals
import heycam.core.designsystem.HeycamTextField
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.IconButton
import heycam.core.designsystem.InfoButton
import heycam.core.designsystem.Kicker
import heycam.core.designsystem.LeadStory
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.Masthead
import heycam.core.designsystem.Nameplate
import heycam.core.designsystem.Note
import heycam.core.designsystem.OcrText
import heycam.core.designsystem.PageThumb
import heycam.core.designsystem.PrimaryButton
import heycam.core.designsystem.PrintedSwitchRow
import heycam.core.designsystem.RowAction
import heycam.core.designsystem.ScanBar
import heycam.core.designsystem.SecondaryButton
import heycam.core.designsystem.SectionHeader
import heycam.core.designsystem.SectionTabs
import heycam.core.designsystem.SegmentedRow
import heycam.core.designsystem.SheetFrame
import heycam.core.designsystem.StatusBanner
import heycam.core.designsystem.TextButton
import heycam.core.designsystem.TextHitRow
import heycam.core.designsystem.ToolItem
import heycam.core.designsystem.ToolSection
import heycam.core.designsystem.ToolsList
import heycam.core.designsystem.ValueRow

/*
 * The Broadsheet component catalog: every shared component on one of five sheets, in the states screens use. The
 * same composables feed Android Studio previews and the Roborazzi goldens (src/testDebug), so a preview and its
 * golden never drift apart. Copy here follows the COPY RULE: short labels, no explanations.
 */

private val noop: () -> Unit = {}

@Preview(name = "Day paper", widthDp = 390, showBackground = true)
@Preview(name = "Night ink", widthDp = 390, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class SheetPreviews

/** A labelled specimen: the component's name in mono, then the component. */
@Composable
private fun Specimen(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(name, style = Heycam.type.frameNo, color = Heycam.colors.muted, modifier = Modifier.padding(horizontal = HeycamSize.gutter))
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Heycam.colors.paper).padding(vertical = 8.dp), content = content)
}

private val Inset = Modifier.padding(horizontal = HeycamSize.gutter)

/** Type roles, kickers, flags and the nameplate. */
@Composable
fun TypeSheet() = Sheet {
    val t = Heycam.type
    val c = Heycam.colors
    Specimen("Nameplate") { Box(Inset) { Nameplate() } }
    Specimen("Kicker · Flag") {
        Row(Inset, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Kicker("Latest")
            Flag("Text")
            Flag("Home & lease", outlined = true)
        }
    }
    Specimen("Display · headline · title") {
        Column(Inset) {
            Text("Scan your first page.", style = t.display, color = c.ink)
            Text("Lease agreement", style = t.headline, color = c.ink)
            Text("Hardware store receipt", style = t.title, color = c.ink)
        }
    }
    Specimen("Row title · lede · reading") {
        Column(Inset) {
            Text("Electricity bill, September", style = t.rowTitle, color = c.ink)
            Text("Straight, clean and on your phone.", style = t.lede, color = c.ink2)
            Text("The tenant will pay a deposit of four weeks' rent before moving in.", style = t.reading, color = c.ink)
        }
    }
    Specimen("Label · body · figure · folio") {
        Column(Inset) {
            Text("Searchable PDF", style = t.label, color = c.ink)
            Text("Copy, share or search the text.", style = t.body, color = c.ink2)
            Text("Tue 20 Oct · 14:32 · 2.4 MB", style = t.figure, color = c.muted)
            Text("8", style = t.folio, color = c.ink)
        }
    }
}

/** Buttons, choices, switches and fields. */
@Composable
fun ControlsSheet() = Sheet {
    Specimen("PrimaryButton · SecondaryButton") {
        Column(Inset, verticalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing)) {
            PrimaryButton("Scan", noop, icon = HeycamIcons.Camera, large = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing)) {
                SecondaryButton("Import photos", noop, icon = HeycamIcons.Gallery, modifier = Modifier.weight(1f))
                PrimaryButton("Save", noop, enabled = false)
            }
        }
    }
    Specimen("TextButton · IconButton · InfoButton") {
        Row(Inset, horizontalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing), verticalAlignment = Alignment.CenterVertically) {
            TextButton("Open", noop)
            TextButton("Share", noop)
            IconButton(HeycamIcons.Search, "Search", noop)
            IconButton(HeycamIcons.More, "More", noop)
            IconButton(HeycamIcons.Delete, "Delete", noop, enabled = false)
            InfoButton("Searchable PDF", "Lets you search and copy the text.")
        }
    }
    Specimen("Chip") {
        Row(Inset, horizontalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing)) {
            Chip("A4", selected = true, onClick = noop)
            Chip("Letter", selected = false, onClick = noop)
            Chip("Legal", selected = false, onClick = noop)
            Chip("Fit", selected = false, onClick = noop)
        }
    }
    Specimen("SegmentedRow") {
        Column(Inset, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedRow(listOf("PDF", "JPG", "PNG", "Text"), selected = 0, onSelect = {})
            SegmentedRow(
                listOf("Small", "Standard", "Original"),
                selected = 1,
                onSelect = {},
                details = listOf("0.6 MB", "1.8 MB", "9.4 MB"),
            )
            SegmentedRow(listOf("Pages", "Text"), selected = 0, onSelect = {}, icons = listOf(HeycamIcons.Pages, HeycamIcons.TextMode))
        }
    }
    Specimen("PrintedSwitchRow") {
        Column(Inset) {
            PrintedSwitchRow(checked = true, onCheckedChange = {}, label = "Searchable PDF")
            PrintedSwitchRow(checked = false, onCheckedChange = {}, label = "Read text automatically")
        }
    }
    Specimen("HeycamTextField · FindBar") {
        Column(Inset, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeycamTextField(value = "", onValueChange = {}, placeholder = "Search", leadingIcon = HeycamIcons.Search)
            HeycamTextField(value = "lease-agreement-flat-4b", onValueChange = {}, label = "File name", mono = true, suffix = ".pdf")
            HeycamTextField(value = "Receipts/", onValueChange = {}, label = "Folder name", error = "Use letters and numbers")
            FindBar(query = "deposit", onQueryChange = {}, current = 2, total = 5, onPrevious = noop, onNext = noop)
        }
    }
}

/** Mastheads, bars and rows. */
@Composable
fun RowsSheet() = Sheet {
    Specimen("Masthead · Dateline · SectionTabs") {
        Masthead(onSearch = noop, onSettings = noop)
        Column(Inset) {
            Dateline(end = "Tue 20 Oct", middle = "42 docs · 318 pp")
            SectionTabs(tabs = listOf("Contents", "Tools"), selected = 0, onSelect = {})
        }
    }
    Specimen("ArticleTopBar") {
        ArticleTopBar(title = "Lease agreement, flat 4B", onBack = noop, actions = {
            IconButton(HeycamIcons.Share, "Share", noop)
            IconButton(HeycamIcons.More, "More", noop)
        })
        ArticleTopBar(title = "Filter", kicker = "Page 3 of 3", onBack = noop)
    }
    Specimen("LeadStory") {
        LeadStory(
            title = "Lease agreement, flat 4B",
            meta = "8 pp · Today 14:32 · 2.4 MB",
            onOpen = noop,
            onShare = noop,
            flags = listOf("Text"),
            thumbnail = { SamplePage() },
            modifier = Inset,
        )
    }
    Specimen("SectionHeader · DocumentRow") {
        SectionHeader("Receipts", count = 6, onClick = noop, modifier = Inset)
        DocumentRow(
            title = "Hardware store, paint and brushes",
            meta = "Mon 19 Oct · 18:05 · 210 KB",
            pageCount = 1,
            onClick = noop,
            thumbnail = { SamplePage(seed = 2) },
            actions = listOf(RowAction("Share", noop), RowAction("Delete", noop)),
        )
        DocumentRow(
            title = "Electricity bill, September",
            meta = "Thu 15 Oct · 09:12 · 620 KB",
            pageCount = 3,
            onClick = noop,
            flags = listOf("Text"),
            selected = true,
            thumbnail = { SamplePage(seed = 3) },
        )
    }
    Specimen("TextHitRow") {
        val snippet = "…the tenant will pay a deposit of four weeks' rent before moving in…"
        val at = snippet.indexOf("deposit")
        TextHitRow(
            title = "Lease agreement, flat 4B",
            snippet = snippet,
            match = at until at + 7,
            pageNumber = 2,
            onClick = noop,
        )
    }
    Specimen("ValueRow") {
        ValueRow("Page size", value = "A4", onClick = noop)
        ValueRow("Source code", onClick = noop, external = true)
        ValueRow("Scans", value = "182 MB")
    }
    Specimen("ToolsList") {
        ToolsList(
            sections = listOf(
                ToolSection(null, listOf(ToolItem("Scan to PDF", noop), ToolItem("Import photos", noop))),
                ToolSection("Convert", listOf(ToolItem("Extract text", noop), ToolItem("Print", noop))),
            ),
            footer = { Note("More PDF tools on heypdf.edgr.in", icon = HeycamIcons.OpenExternal, onClick = noop) },
        )
    }
}

/** Pages, filters and recognised text. */
@Composable
fun PagesSheet() = Sheet {
    Specimen("PageThumb") {
        Row(Inset, horizontalArrangement = Arrangement.spacedBy(HeycamSize.pageGridGap)) {
            PageThumb(1, Modifier.weight(1f), current = true) { SamplePage(seed = 1) }
            PageThumb(2, Modifier.weight(1f), marked = true) { SamplePage(seed = 2) }
            PageThumb(3, Modifier.weight(1f))
        }
    }
    Specimen("FilterStrip") {
        val looks = SampleLook.entries
        FilterStrip(labels = listOf("Original", "Auto", "Grey", "B&W"), selected = 1, onSelect = {}) { i ->
            SamplePage(look = looks[i])
        }
    }
    Specimen("OcrText") {
        val text = "TENANCY AGREEMENT\nThis agreement is made on 1 October 2026 between the landlord and the tenant. " +
            "The tenant will pay a deposit of four weeks' rent before moving in. The deposit is held in a scheme."
        val first = text.indexOf("deposit")
        val second = text.indexOf("deposit", first + 1)
        OcrText(
            text = text,
            matches = listOf(first until first + 7, second until second + 7),
            currentMatch = 1,
            kicker = "Page 1 of 8",
            figure = "412 words",
            modifier = Inset,
        )
    }
}

/** Status, progress, messages, sheets and empty pictures. */
@Composable
fun FeedbackSheet() = Sheet {
    Specimen("StatusBanner") {
        Column(Inset, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBanner("Getting the scanner", icon = HeycamIcons.Scanner, progress = 0.42f)
            StatusBanner("Reading text", icon = HeycamIcons.TextMode, busy = true)
            StatusBanner("No connection", isError = true, actionLabel = "Retry", onAction = noop)
        }
    }
    Specimen("LoadBar") { LoadBar(progress = 0.6f, modifier = Inset) }
    Specimen("Snackbar") {
        HeycamSnackbar(StaticSnackbar(HeycamSnackbarVisuals("Page deleted", actionLabel = "Undo")))
        HeycamSnackbar(StaticSnackbar(HeycamSnackbarVisuals("Not enough space", isError = true)))
    }
    Specimen("Dialog") {
        Box(Inset) {
            DialogCard(
                title = "Discard 3 pages?",
                confirmLabel = "Discard",
                onConfirm = noop,
                onDismiss = noop,
                dismissLabel = "Cancel",
            )
        }
    }
    Specimen("SheetFrame") {
        SheetFrame(title = "Share 8 pages", kicker = "Export") {
            SegmentedRow(listOf("PDF", "JPG", "PNG", "Text"), selected = 0, onSelect = {})
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(HeycamSize.touchSpacing)) {
                SecondaryButton("Save", noop, icon = HeycamIcons.Save, modifier = Modifier.weight(1f))
                PrimaryButton("Share", noop, icon = HeycamIcons.Share, modifier = Modifier.weight(1f))
            }
        }
    }
    Specimen("BottomActionBar · ScanBar") {
        BottomActionBar {
            ActionBarItem(HeycamIcons.Rotate, "Rotate", noop)
            ActionBarItem(HeycamIcons.Filter, "Filter", noop)
            ActionBarItem(HeycamIcons.Camera, "Retake", noop)
            ActionBarItem(HeycamIcons.Delete, "Delete", noop, destructive = true)
        }
        ScanBar(onScan = noop, onImport = noop)
    }
    Specimen("EmptyPicture") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EmptyPicture() }
    }
}

/** A snackbar frozen on screen for the catalog. */
private class StaticSnackbar(override val visuals: HeycamSnackbarVisuals) : SnackbarData {
    override fun performAction() = Unit
    override fun dismiss() = Unit
}

@SheetPreviews @Composable private fun TypeSheetPreview() = HeycamTheme { TypeSheet() }
@SheetPreviews @Composable private fun ControlsSheetPreview() = HeycamTheme { ControlsSheet() }
@SheetPreviews @Composable private fun RowsSheetPreview() = HeycamTheme { RowsSheet() }
@SheetPreviews @Composable private fun PagesSheetPreview() = HeycamTheme { PagesSheet() }
@SheetPreviews @Composable private fun FeedbackSheetPreview() = HeycamTheme { FeedbackSheet() }
