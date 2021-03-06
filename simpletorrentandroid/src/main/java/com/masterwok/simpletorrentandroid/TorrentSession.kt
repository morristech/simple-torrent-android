package com.masterwok.simpletorrentandroid

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.*
import com.masterwok.simpletorrentandroid.contracts.TorrentSessionListener
import com.masterwok.simpletorrentandroid.extensions.*
import com.masterwok.simpletorrentandroid.models.TorrentSessionBuffer
import com.masterwok.simpletorrentandroid.models.TorrentSessionStatus
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLDecoder
import java.security.InvalidParameterException


/**
 * This class is used to control a torrent download session for the provided [torrentUri].
 * It is configured using the provided [torrentSessionOptions].
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class TorrentSession(
        val torrentUri: Uri
        , private val torrentSessionOptions: TorrentSessionOptions
) {
    companion object {
        private const val Tag = "TorrentSession"
    }

    /**
     * Whether or not the session is paused.
     */
    val isPaused get() = sessionManager.isPaused

    /**
     * Whether or not the session is running (started).
     */
    val isRunning get() = sessionManager.isRunning

    /**
     * The provided [listener] will receive status updates during the torrent download.
     */
    var listener: TorrentSessionListener? = null

    private lateinit var torrentSessionBuffer: TorrentSessionBuffer
    private lateinit var saveLocationUri: Uri
    private lateinit var largestFileUri: Uri

    private val alertListener = TorrentSessionAlertListener(this)
    private val sessionManager = SessionManager()
    private val dhtLock = Object()

    init {
        sessionManager.addListener(alertListener)
    }

    private class TorrentSessionAlertListener(
            torrentSession: TorrentSession
    ) : AlertListener {

        private val torrentSession: WeakReference<TorrentSession> = WeakReference(torrentSession)

        @Synchronized
        override fun alert(alert: Alert<*>) {
            try {
                if (alert.isTorrentAlert() && !alert.hasValidTorrentHandle()) {
                    Log.w(Tag, "Ignoring alert with invalid torrent handle: ${alert.type()}")
                    return
                }

                when (alert.type()) {
                    AlertType.DHT_BOOTSTRAP -> torrentSession.get()?.onDhtBootstrap()
                    AlertType.DHT_STATS -> torrentSession.get()?.onDhtStats()
                    AlertType.METADATA_RECEIVED -> torrentSession.get()?.onMetadataReceived(alert as MetadataReceivedAlert)
                    AlertType.METADATA_FAILED -> torrentSession.get()?.onMetadataFailed(alert as MetadataFailedAlert)
                    AlertType.PIECE_FINISHED -> torrentSession.get()?.onPieceFinished(alert as PieceFinishedAlert)
                    AlertType.TORRENT_DELETE_FAILED -> torrentSession.get()?.onTorrentDeleteFailed(alert as TorrentDeleteFailedAlert)
                    AlertType.TORRENT_DELETED -> torrentSession.get()?.onTorrentDeleted(alert as TorrentDeletedAlert)
                    AlertType.TORRENT_REMOVED -> torrentSession.get()?.onTorrentRemoved(alert as TorrentRemovedAlert)
                    AlertType.TORRENT_RESUMED -> torrentSession.get()?.onTorrentResumed(alert as TorrentResumedAlert)
                    AlertType.TORRENT_PAUSED -> torrentSession.get()?.onTorrentPaused(alert as TorrentPausedAlert)
                    AlertType.TORRENT_FINISHED -> torrentSession.get()?.onTorrentFinished(alert as TorrentFinishedAlert)
                    AlertType.TORRENT_ERROR -> torrentSession.get()?.onTorrentError(alert as TorrentErrorAlert)
                    AlertType.ADD_TORRENT -> torrentSession.get()?.onAddTorrent(alert as AddTorrentAlert)
                    AlertType.BLOCK_UPLOADED -> torrentSession.get()?.onBlockUploaded(alert as BlockUploadedAlert)
                    else -> Log.d(Tag, "Unhandled alert: $alert")
                }
            } catch (e: Exception) {
                Log.e(Tag, "An exception occurred within torrent session callback", e)
            }
        }

        override fun types(): IntArray? = null
    }

    private fun onBlockUploaded(blockUploadedAlert: BlockUploadedAlert) {
        val torrentHandle = blockUploadedAlert.handle()

        listener?.onBlockUploaded(createTorrentSessionStatus(torrentHandle))
    }

    private fun createTorrentSessionStatus(torrentHandle: TorrentHandle): TorrentSessionStatus =
            TorrentSessionStatus.createInstance(
                    torrentUri
                    , torrentHandle
                    , torrentSessionBuffer
                    , saveLocationUri
                    , largestFileUri
            )

    private fun onTorrentDeleteFailed(torrentDeleteFailedAlert: TorrentDeleteFailedAlert) {
        val torrentHandle = torrentDeleteFailedAlert.handle()

        listener?.onTorrentDeleteFailed(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentPaused(torrentPausedAlert: TorrentPausedAlert) {
        val torrentHandle = torrentPausedAlert.handle()

        listener?.onTorrentPaused(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentResumed(torrentResumedAlert: TorrentResumedAlert) {
        val torrentHandle = torrentResumedAlert.handle()

        listener?.onTorrentResumed(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentRemoved(torrentRemovedAlert: TorrentRemovedAlert) {
        val torrentHandle = torrentRemovedAlert.handle()

        listener?.onTorrentRemoved(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentDeleted(torrentDeletedAlert: TorrentDeletedAlert) {
        val torrentHandle = torrentDeletedAlert.handle()

        listener?.onTorrentDeleted(createTorrentSessionStatus(torrentHandle))
    }

    private fun onMetadataReceived(metadataReceivedAlert: MetadataReceivedAlert) {
        val torrentHandle = metadataReceivedAlert.handle()

        largestFileUri = torrentHandle.getLargestFileUri(torrentSessionOptions.downloadLocation)
        saveLocationUri = Uri.fromFile(torrentSessionOptions.downloadLocation)

        listener?.onMetadataReceived(createTorrentSessionStatus(torrentHandle))

        sessionManager.download(
                torrentHandle.torrentFile()
                , torrentSessionOptions.downloadLocation
        )
    }

    private fun onPieceFinished(pieceFinishedAlert: PieceFinishedAlert) {
        val torrentHandle = pieceFinishedAlert.handle()

        val pieceIndex = pieceFinishedAlert.pieceIndex()

        if (pieceIndex < torrentSessionBuffer.startIndex || pieceIndex > torrentSessionBuffer.endIndex) {
            // TODO: WHY IS THIS HAPPENING?
            Log.w(Tag, "Out of range piece downloaded.")
            return
        }

        torrentSessionBuffer.setPieceDownloaded(pieceIndex)

        if (torrentSessionOptions.shouldStream) {
            torrentHandle.setBufferPriorities(torrentSessionBuffer)
        }

        listener?.onPieceFinished(createTorrentSessionStatus(torrentHandle))
    }

    private fun onAddTorrent(addTorrentAlert: AddTorrentAlert) {
        val torrentHandle = addTorrentAlert.handle()

        if (torrentSessionOptions.onlyDownloadLargestFile) {
            torrentHandle.ignoreAllFiles()
            torrentHandle.prioritizeLargestFile(Priority.NORMAL)
        }

        torrentSessionBuffer = TorrentSessionBuffer(
                torrentSessionBuffer.bufferSize
                , torrentHandle.getFirstNonIgnoredPieceIndex()
                , torrentHandle.getLastNonIgnoredPieceIndex()
        )

        if (torrentSessionOptions.shouldStream) {
            torrentHandle.setBufferPriorities(torrentSessionBuffer)
        }

        listener?.onAddTorrent(createTorrentSessionStatus(torrentHandle))

        addTorrentAlert
                .handle()
                .resume()
    }

    private fun onTorrentError(torrentErrorAlert: TorrentErrorAlert) =
            listener?.onTorrentError(
                    createTorrentSessionStatus(torrentErrorAlert.handle())
            )

    private fun onTorrentFinished(torrentFinishedAlert: TorrentFinishedAlert) =
            listener?.onTorrentFinished(
                    createTorrentSessionStatus(torrentFinishedAlert.handle())
            )

    private fun onMetadataFailed(metadataFailedAlert: MetadataFailedAlert) =
            listener?.onMetadataFailed(
                    createTorrentSessionStatus(metadataFailedAlert.handle())
            )

    private fun onDhtStats() {
        synchronized(dhtLock) {
            if (isDhtReady()) {
                dhtLock.notify()
            }
        }
    }

    private fun onDhtBootstrap() {
        synchronized(dhtLock) {
            dhtLock.notify()
        }
    }

    private fun isDhtReady() = sessionManager
            .stats()
            .dhtNodes() >= torrentSessionOptions.dhtNodeMinimum

    private fun downloadUsingMagnetUri(
            magnetUrl: String
            , timeout: Int
    ): Boolean = synchronized(dhtLock) {
        // We must wait for DHT to start
        if (!isDhtReady()) {
            dhtLock.wait()
        }

        return sessionManager.fetchMagnet(
                URLDecoder.decode(magnetUrl, "utf-8")
                , timeout
        ) != null
    }

    /**
     * Start the torrent session and abort if the session takes longer
     * than the provided [timeout] to start. The provided [Context] is
     * used to resolve an input stream from the content resolver when
     * the URI is a file or content scheme.
     */
    fun start(context: Context, timeout: Int): Boolean {
        saveLocationUri = Uri.EMPTY
        largestFileUri = Uri.EMPTY

        torrentSessionBuffer = TorrentSessionBuffer(
                bufferSize = if (torrentSessionOptions.shouldStream) torrentSessionOptions.bufferSize else 0
        )

        if (!sessionManager.isRunning) {
            sessionManager.start(torrentSessionOptions.build())
        }

        val path = torrentUri.toString()

        if (path.startsWith("magnet")) {
            return downloadUsingMagnetUri(path, timeout)
        }

        if (URLUtil.isNetworkUrl(path)) {
            return sessionManager.downloadUsingNetworkUri(
                    torrentSessionOptions.downloadLocation
                    , URL(path)
                    , timeout
            )
        }

        if (URLUtil.isFileUrl(path) || URLUtil.isContentUrl(path)) {
            return sessionManager.downloadUsingContentUri(
                    context
                    , torrentSessionOptions.downloadLocation
                    , torrentUri
            )
        }

        throw InvalidParameterException("Unrecognized torrent URI: $torrentUri")
    }

    /**
     * Stop the torrent session. This is an expensive operation and should not be
     * done on the main thread.
     */
    fun stop() {
        sessionManager.removeListener(alertListener)
        sessionManager.stop()
    }

    /**
     * Pause torrent session.
     */
    fun pause() = sessionManager.pause()

    /**
     * Resume torrent session.
     */
    fun resume() = sessionManager.resume()

}

