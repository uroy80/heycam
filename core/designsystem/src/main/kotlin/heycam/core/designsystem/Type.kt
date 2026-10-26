@file:OptIn(ExperimentalTextApi::class)

package heycam.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Fonts in res/font (OFL-1.1; licences in assets/licences, fetched by fetch-fonts.sh):
 *   fraunces.ttf, fraunces_italic.ttf   Fraunces variable (SOFT, WONK, opsz, wght)
 *   libre_franklin.ttf                  Libre Franklin variable (wght)
 *   ibm_plex_mono_regular/medium.ttf    IBM Plex Mono (Reserved Font Name "Plex": shipped unmodified)
 * Variable fonts need API 26+; minSdk is 28, so there is no static fallback.
 */

/**
 * Fraunces is one variable file per style, and a FontFamily holds one (wght, opsz) instance, so each role gets its
 * own family. Both axes are always set because the file's defaults are wght 900 / opsz 9. SOFT and WONK stay at
 * the font's defaults, as on heyp's web.
 */
private fun fraunces(weight: Int, opsz: Float, italic: Boolean = false) = FontFamily(
    Font(
        resId = if (italic) R.font.fraunces_italic else R.font.fraunces,
        weight = FontWeight(weight),
        style = if (italic) FontStyle.Italic else FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)),
    ),
)

/** Libre Franklin is one variable file (wght 100–900); the roles use these weights. */
private val LibreFranklin = FontFamily(
    listOf(400, 500, 600, 650, 700).map { w ->
        Font(
            resId = R.font.libre_franklin,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val Centred = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily,
    weight: Int,
    size: TextUnit,
    lineHeight: TextUnit,
    tracking: TextUnit = 0.em,
    italic: Boolean = false,
    tabularFigures: Boolean = false,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    fontFeatureSettings = if (tabularFigures) "tnum" else null,
    lineHeightStyle = Centred,
)

/**
 * Editorial type roles (theme.json `typography.roles`). Composables apply colours. The caps roles (kicker,
 * sectionHead, dateline, modeLabel) are upper-cased on screen while TalkBack hears the sentence-case source.
 */
@Immutable
data class HeycamType(
    val nameplate: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val rowTitle: TextStyle,
    val tab: TextStyle,
    val tabSelected: TextStyle,
    val lede: TextStyle,
    val reading: TextStyle,
    val caption: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val button: TextStyle,
    val hint: TextStyle,
    val kicker: TextStyle,
    val sectionHead: TextStyle,
    val dateline: TextStyle,
    val figure: TextStyle,
    val figureStrong: TextStyle,
    val folio: TextStyle,
    val frameNo: TextStyle,
    val modeLabel: TextStyle,
)

val BroadsheetType = HeycamType(
    nameplate = style(fraunces(760, 144f), 760, 34.sp, 34.sp, (-0.035).em),
    display = style(fraunces(600, 144f), 600, 40.sp, 42.sp, (-0.03).em),
    headline = style(fraunces(600, 72f), 600, 28.sp, 31.sp, (-0.02).em),
    title = style(fraunces(600, 48f), 600, 22.sp, 26.sp, (-0.01).em),
    rowTitle = style(fraunces(400, 24f), 400, 18.sp, 23.sp, (-0.005).em),
    tab = style(fraunces(400, 24f), 400, 17.sp, 22.sp),
    tabSelected = style(fraunces(640, 30f, italic = true), 640, 17.sp, 22.sp, italic = true),
    lede = style(fraunces(400, 18f, italic = true), 400, 16.sp, 23.sp, italic = true),
    reading = style(fraunces(400, 14f), 400, 16.sp, 25.sp),
    caption = style(fraunces(400, 14f, italic = true), 400, 14.sp, 19.sp, italic = true),
    body = style(LibreFranklin, 400, 15.sp, 22.sp),
    label = style(LibreFranklin, 500, 15.sp, 20.sp),
    button = style(LibreFranklin, 600, 15.sp, 20.sp, 0.01.em),
    hint = style(LibreFranklin, 400, 13.sp, 18.sp),
    kicker = style(LibreFranklin, 700, 11.sp, 13.sp, 0.14.em),
    sectionHead = style(LibreFranklin, 700, 11.sp, 13.sp, 0.14.em),
    dateline = style(LibreFranklin, 600, 10.5.sp, 12.sp, 0.12.em),
    figure = style(PlexMono, 400, 12.5.sp, 17.sp, 0.02.em, tabularFigures = true),
    figureStrong = style(PlexMono, 500, 12.5.sp, 17.sp, 0.02.em, tabularFigures = true),
    folio = style(PlexMono, 500, 16.sp, 18.sp, tabularFigures = true),
    frameNo = style(PlexMono, 400, 11.sp, 12.sp, 0.04.em, tabularFigures = true),
    modeLabel = style(LibreFranklin, 700, 12.sp, 14.sp, 0.12.em),
)

/** Stock Material 3 components inherit the house style through this mapping (theme.json `materialTypography`). */
val BroadsheetMaterialTypography = Typography(
    displayLarge = BroadsheetType.display,
    displayMedium = BroadsheetType.display.copy(fontSize = 34.sp, lineHeight = 37.sp),
    displaySmall = BroadsheetType.display.copy(fontSize = 30.sp, lineHeight = 33.sp),
    headlineLarge = BroadsheetType.headline,
    headlineMedium = BroadsheetType.headline.copy(fontSize = 24.sp, lineHeight = 28.sp),
    headlineSmall = BroadsheetType.title,
    titleLarge = BroadsheetType.title.copy(fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = style(LibreFranklin, 600, 16.sp, 22.sp),
    titleSmall = style(LibreFranklin, 600, 14.sp, 20.sp),
    bodyLarge = style(LibreFranklin, 400, 16.sp, 24.sp),
    bodyMedium = BroadsheetType.body,
    bodySmall = BroadsheetType.hint,
    labelLarge = BroadsheetType.button,
    labelMedium = style(LibreFranklin, 600, 12.sp, 16.sp, 0.02.em),
    labelSmall = style(LibreFranklin, 600, 11.sp, 14.sp, 0.04.em),
)
