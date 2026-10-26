package heycam.app.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import heycam.app.MainActivity

/**
 * Quick Settings tile "Scan" (PLAN F11). Tapping it collapses the shade and opens MainActivity with
 * [ACTION_SCAN], which goes straight to the scanner. On a locked phone the user unlocks first, because the
 * scanner leads into their documents.
 */
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // A shortcut, not a switch: it never shows as "on".
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openScanner) else openScanner()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openScanner() {
        val intent = scanIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** MainActivity's scan action; the launcher shortcut "Scan" sends the same one (res/xml/shortcuts.xml). */
        const val ACTION_SCAN = "heycam.action.SCAN"

        /** Brings an open heycam to the front (onNewIntent) instead of stacking a second copy. */
        fun scanIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SCAN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
