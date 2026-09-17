package com.jcversa.swiftslate.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.jcversa.swiftslate.EXTRA_OPEN_SECURE_BACKUP
import com.jcversa.swiftslate.MainActivity

/**
 * Quick Settings entry point for the explicit encrypted API-key export/import flow.
 *
 * The tile never reads, exports, or decrypts a key itself. It only opens SwiftSlate's Settings
 * screen, where the user must choose export/import and enter the passphrase.
 */
@RequiresApi(24)
class ApiKeyBackupTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_SECURE_BACKUP, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            collapseLegacyActivity(intent)
        }
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseLegacyActivity(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
