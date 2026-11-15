package one.lmk.wordcraft

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object PlatformCastFactory {
    fun create(
        activity: WordCraftActivity,
        onRemoteStateChanged: () -> Unit,
    ): CastPlatformController = PlayCastController(activity, onRemoteStateChanged)
}

private class PlayCastController(
    private val activity: WordCraftActivity,
    private val onRemoteStateChanged: () -> Unit,
) : CastPlatformController {

    companion object {
        private const val CAST_WIDTH_PX = 1920
        private const val CAST_HEIGHT_PX = 1080
        private const val MIN_PUSH_INTERVAL_MS = 180L
        private const val TAG_CHOOSER = "docone_cast_chooser"
        private const val TAG_CONTROLLER = "docone_cast_controller"
    }

    private data class RawFrame(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
    )

    private val appContext = activity.applicationContext
    private val localServer = LocalCastHttpServer(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "docone-cast-encode").apply { isDaemon = true }
    }

    private val pendingFrame = AtomicReference<RawFrame?>(null)
    private val encodeScheduled = AtomicBoolean(false)

    @Volatile
    private var lastPushElapsedMs = 0L

    @Volatile
    private var castContext: CastContext? = null

    @Volatile
    private var initAttempted = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var activeCastSession: CastSession? = null

    @Volatile
    private var sessionConnected = false

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            onSessionConnected(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            onSessionDisconnected()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            onSessionDisconnected()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            onSessionConnected(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            onSessionDisconnected()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            onSessionDisconnected()
        }
    }

    init {
        ensureCastContext(openDialogOnReady = false)
    }

    override fun onResume() {
        ensureCastContext(openDialogOnReady = false)
        val current = castContext?.sessionManager?.currentCastSession
        val connected = current != null && current.isConnected
        if (connected && activeCastSession !== current) {
            onSessionConnected(current!!)
        } else if (!connected && activeCastSession != null) {
            onSessionDisconnected()
        }
    }

    override fun onDestroy() {
        destroyed = true
        sessionConnected = false
        pendingFrame.set(null)
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        } catch (_: Throwable) {
        }
        activeCastSession = null
        localServer.stop()
        try {
            encodeExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    override fun isRemoteSessionActive(): Boolean = !destroyed && sessionConnected && activeCastSession != null

    override fun remoteDisplaySizePx(): Pair<Int, Int>? =
        if (isRemoteSessionActive()) CAST_WIDTH_PX to CAST_HEIGHT_PX else null

    override fun openCastMenu(): Boolean {
        val ctx = castContext
        if (ctx == null) {
            if (!initAttempted) {
                ensureCastContext(openDialogOnReady = true)
                return true
            }
            return openSystemCastFallback()
        }
        activity.runOnUiThread {
            if (!showCastDialog(ctx)) {
                openSystemCastFallback()
            }
        }
        return true
    }

    override fun onPageFrame(rgba: ByteArray, width: Int, height: Int, backdropArgb: Int) {
        if (destroyed || !isRemoteSessionActive()) return
        pendingFrame.set(RawFrame(rgba, width, height))
        if (encodeScheduled.compareAndSet(false, true)) {
            val now = android.os.SystemClock.elapsedRealtime()
            val delayMs = (MIN_PUSH_INTERVAL_MS - (now - lastPushElapsedMs)).coerceAtLeast(0L)
            mainHandler.postDelayed({ submitEncodeTask() }, delayMs)
        }
    }

    override fun onClearFrame() {
        pendingFrame.set(null)
        localServer.clearFrame()
    }

    private fun submitEncodeTask() {
        if (destroyed) {
            encodeScheduled.set(false)
            return
        }
        try {
            encodeExecutor.execute { processLatestFrame() }
        } catch (_: Throwable) {
            encodeScheduled.set(false)
        }
    }

    private fun processLatestFrame() {
        try {
            val frame = pendingFrame.getAndSet(null) ?: return
            if (destroyed || !isRemoteSessionActive()) return
            val pngBytes = encodeRgbaToPng(frame.rgba, frame.width, frame.height) ?: return
            val url = localServer.updateFrame(pngBytes) ?: return
            lastPushElapsedMs = android.os.SystemClock.elapsedRealtime()
            activity.runOnUiThread { pushUrlToCastReceiver(url) }
        } finally {
            encodeScheduled.set(false)
            if (pendingFrame.get() != null && !destroyed && isRemoteSessionActive()) {
                if (encodeScheduled.compareAndSet(false, true)) {
                    mainHandler.postDelayed({ submitEncodeTask() }, MIN_PUSH_INTERVAL_MS)
                }
            }
        }
    }

    private fun encodeRgbaToPng(rgba: ByteArray, width: Int, height: Int): ByteArray? {
        if (!CastDisplayHelper.isValidPresentationFrame(width, height, rgba.size)) return null
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            val out = ByteArrayOutputStream(64 * 1024)
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        } catch (_: Throwable) {
            null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun pushUrlToCastReceiver(url: String) {
        if (destroyed || activity.isDestroyed || activity.isFinishing) return
        val client = activeCastSession?.takeIf { it.isConnected }?.remoteMediaClient ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
            putString(MediaMetadata.KEY_TITLE, "DocOne")
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setContentType("image/png")
            .setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        try {
            client.load(request).setResultCallback { result ->
                if (!result.status.isSuccess) {
                    Log.w(
                        "WordCraftCast",
                        "remoteMediaClient.load failed code=${result.status.statusCode} msg=${result.status.statusMessage}",
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("WordCraftCast", "remoteMediaClient.load threw", t)
        }
    }

    private fun onSessionConnected(session: CastSession) {
        activeCastSession = session
        sessionConnected = session.isConnected
        localServer.ensureStarted()
        onRemoteStateChanged()
    }

    private fun onSessionDisconnected() {
        sessionConnected = false
        activeCastSession = null
        pendingFrame.set(null)
        localServer.clearFrame()
        onRemoteStateChanged()
    }

    private fun ensureCastContext(openDialogOnReady: Boolean) {
        initAttempted = true
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            CastContext.getSharedInstance(activity, executor)
                .addOnSuccessListener { ctx ->
                    if (destroyed) return@addOnSuccessListener
                    val firstAttach = castContext == null
                    castContext = ctx
                    if (firstAttach) {
                        try {
                            ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                        } catch (_: Throwable) {
                        }
                    }
                    val current = ctx.sessionManager.currentCastSession
                    if (current != null && current.isConnected) {
                        onSessionConnected(current)
                    }
                    if (openDialogOnReady) {
                        if (!showCastDialog(ctx)) {
                            openSystemCastFallback()
                        }
                    }
                }
                .addOnFailureListener {
                    if (openDialogOnReady && !destroyed) {
                        openSystemCastFallback()
                    }
                }
        } catch (_: Throwable) {
            if (openDialogOnReady && !destroyed) {
                openSystemCastFallback()
            }
        }
    }

    private fun showCastDialog(ctx: CastContext): Boolean {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return false
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) return false
        val session = ctx.sessionManager.currentCastSession
        if (session != null && (session.isConnected || session.isConnecting)) {
            if (fm.findFragmentByTag(TAG_CONTROLLER) != null) return true
            return try {
                MediaRouteControllerDialogFragment().show(fm, TAG_CONTROLLER)
                true
            } catch (_: Throwable) {
                false
            }
        }
        val selector = ctx.mergedSelector ?: return false
        if (fm.findFragmentByTag(TAG_CHOOSER) != null) return true
        return try {
            MediaRouteChooserDialogFragment().apply {
                routeSelector = selector
            }.show(fm, TAG_CHOOSER)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun openSystemCastFallback(): Boolean {
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
