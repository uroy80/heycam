package heycam.feature.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamIcons
import heycam.core.designsystem.HeycamSize
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.Nameplate
import heycam.core.designsystem.focusRing
import heycam.core.designsystem.R as DesignR
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** About (S14): the nameplate and version, links out, licences. */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenLicences: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    SettingsPage(title = stringResource(R.string.about_title), onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // "heycam :)" is read as "heycam": the smile is decorative.
            Nameplate(Modifier.semantics { heading() })
            if (version != null) {
                Text(
                    text = stringResource(R.string.about_version, version),
                    style = Heycam.type.figure,
                    color = Heycam.colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        SectionHead(stringResource(R.string.about_links))
        LinkRow(
            label = stringResource(R.string.settings_privacy_policy),
            external = true,
            onClick = { openWebPage(context, PRIVACY_POLICY_URL) },
        )
        RowDivider()
        LinkRow(label = stringResource(R.string.about_source), external = true, onClick = { openWebPage(context, SOURCE_URL) })
        RowDivider()
        LinkRow(
            label = stringResource(R.string.about_contact),
            value = SUPPORT_EMAIL,
            external = true,
            opensIn = stringResource(R.string.about_opens_email),
            onClick = { writeEmail(context, SUPPORT_EMAIL) },
        )
        RowDivider()
        LinkRow(label = stringResource(R.string.about_licences), onClick = onOpenLicences)
        Text(
            text = stringResource(R.string.about_footer),
            style = Heycam.type.figure,
            color = Heycam.colors.muted,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

private fun versionName(context: Context): String? =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

/** Open-source licences (S14). An asset licence opens in place; Back returns to the list. */
@Composable
internal fun LicencesScreen(onBack: () -> Unit, components: List<OpenSourceComponent> = OpenSourceLicences.components) {
    val context = LocalContext.current
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val open = openIndex?.let(components::getOrNull)
    if (open != null) {
        BackHandler { openIndex = null }
        LicenceTextPage(open, onBack = { openIndex = null })
        return
    }
    SettingsPage(title = stringResource(R.string.licences_title), onBack = onBack) {
        components.forEachIndexed { index, component ->
            if (index > 0) RowDivider()
            ComponentRow(component) {
                when (val text = component.text) {
                    is LicenceText.Asset -> openIndex = index
                    is LicenceText.Web -> openWebPage(context, text.url)
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(component: OpenSourceComponent, onClick: () -> Unit) {
    val c = Heycam.colors
    val external = component.text is LicenceText.Web
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeycamSize.toolRow)
            .focusRing()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(component.name, style = Heycam.type.label, color = c.ink)
            Text(component.owner, style = Heycam.type.hint, color = c.muted)
        }
        Text(component.licence, style = Heycam.type.figureStrong, color = c.muted, modifier = Modifier.padding(end = 8.dp))
        Icon(
            imageVector = if (external) HeycamIcons.OpenExternal else HeycamIcons.ChevronEnd,
            contentDescription = if (external) stringResource(DesignR.string.hc_opens_outside) else null,
            tint = c.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LicenceTextPage(component: OpenSourceComponent, onBack: () -> Unit) {
    val context = LocalContext.current
    val asset = component.text as LicenceText.Asset
    val text by produceState<LicenceLoad>(LicenceLoad.Loading, asset) {
        value = withContext(Dispatchers.IO) {
            try {
                LicenceLoad.Loaded(listOfNotNull(asset.path, asset.followedBy).map(context::readAsset))
            } catch (e: IOException) {
                LicenceLoad.Missing
            }
        }
    }
    SettingsPage(title = component.name, onBack = onBack) {
        Text(
            text = component.licence,
            style = Heycam.type.figureStrong,
            color = Heycam.colors.muted,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        when (val load = text) {
            LicenceLoad.Loading -> LoadBar()
            LicenceLoad.Missing -> Text(
                text = stringResource(R.string.licences_missing),
                style = Heycam.type.body,
                color = Heycam.colors.accent,
            )
            is LicenceLoad.Loaded -> SelectionContainer {
                Column {
                    load.texts.forEachIndexed { i, licence ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 20.dp), HeycamSize.hair, Heycam.colors.rule)
                        Text(licence.trim(), style = Heycam.type.figure.copy(fontSize = 12.sp), color = Heycam.colors.ink)
                    }
                }
            }
        }
    }
}

private fun Context.readAsset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

private sealed interface LicenceLoad {
    data object Loading : LicenceLoad
    data object Missing : LicenceLoad
    data class Loaded(val texts: List<String>) : LicenceLoad
}
