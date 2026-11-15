package one.lmk.wordcraft

/**
 * Distribution-specific Cast backend (`foss` offline settings vs `play` Google Cast SDK).
 */
internal interface CastPlatformController {
    fun openCastMenu(): Boolean
    fun onResume() {}
    fun onDestroy() {}
    fun isRemoteSessionActive(): Boolean = false
    fun remoteDisplaySizePx(): Pair<Int, Int>? = null
    fun onPageFrame(rgba: ByteArray, width: Int, height: Int, backdropArgb: Int) {}
    fun onClearFrame() {}
}
