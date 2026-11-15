package one.lmk.wordcraft

/**
 * Pure JVM-testable selection and validation helpers for offline Android `Presentation` display
 * streaming and native Cast settings fallback intents.
 */
internal data class PresentationDisplaySpec(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val isPresentationFlag: Boolean,
    val stateOn: Boolean,
)

internal object CastDisplayHelper {
    const val ACTION_CAST_SETTINGS = "android.settings.CAST_SETTINGS"
    const val ACTION_WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"
    const val ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS"
    const val MAX_FRAME_SIDE_PX = 4096

    fun castSettingsIntentActions(): List<String> = listOf(
        ACTION_CAST_SETTINGS,
        ACTION_WIFI_DISPLAY_SETTINGS,
        ACTION_DISPLAY_SETTINGS,
    )

    fun selectPreferredPresentationDisplay(
        candidates: List<PresentationDisplaySpec>,
    ): PresentationDisplaySpec? = candidates
        .asSequence()
        .filter { it.displayId > 0 && it.widthPx > 0 && it.heightPx > 0 && it.stateOn }
        .maxWithOrNull(
            compareBy<PresentationDisplaySpec>(
                { it.isPresentationFlag },
                { it.widthPx.toLong() * it.heightPx.toLong() },
                { -it.displayId },
            ),
        )

    fun isValidPresentationFrame(width: Int, height: Int, byteCount: Int): Boolean {
        if (width !in 1..MAX_FRAME_SIDE_PX || height !in 1..MAX_FRAME_SIDE_PX) {
            return false
        }
        return width.toLong() * height.toLong() * 4L == byteCount.toLong()
    }
}
