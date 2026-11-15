package one.lmk.wordcraft

import android.content.Intent

internal object PlatformCastFactory {
    fun create(
        activity: WordCraftActivity,
        @Suppress("UNUSED_PARAMETER") onRemoteStateChanged: () -> Unit,
    ): CastPlatformController = object : CastPlatformController {
        override fun openCastMenu(): Boolean {
            val actions = CastDisplayHelper.castSettingsIntentActions()
            if (actions.isEmpty()) return false
            activity.runOnUiThread {
                for (action in actions) {
                    try {
                        activity.startActivity(Intent(action))
                        break
                    } catch (_: Throwable) {
                    }
                }
            }
            return true
        }
    }
}
