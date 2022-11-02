package ir.am3n.rtsp.client

import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import ir.am3n.rtsp.client.codec.AudioDecoder
import ir.am3n.rtsp.client.codec.FrameQueue
import ir.am3n.rtsp.client.codec.VideoDecoder
import ir.am3n.rtsp.client.data.Frame
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import ir.am3n.rtsp.client.interfaces.RtspFrameListener
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import ir.am3n.utils.NetUtils
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Rtsp {

    companion object {

        private const val TAG: String = "Rtsp"
        private const val DEBUG = true
        private const val DEFAULT_RTSP_PORT = 554

        suspend fun isOnline(url: String, username: String? = null, password: String? = null, userAgent: String? = null): Boolean {
            return suspendCoroutine {
                Rtsp().apply {
                    init(url, username, password, userAgent)
                    setStatusListener(object : RtspStatusListener {
                        override fun onConnecting() {}
                        override fun onConnected(sdpInfo: SdpInfo) {}
                        override fun onDisconnected() {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(false)
                        }
                        override fun onUnauthorized() {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(true)
                        }
                        override fun onFailed(message: String?) {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(false)
                        }
                    })
                    setFrameListener(object : RtspFrameListener {
                        override fun onVideoNalUnitReceived(frame: Frame?) {
                            setStatusListener(null)
                            setFrameListener(null)
                            stop()
                            it.resume(true)
                        }
                        override fun onVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {}
                        override fun onAudioSampleReceived(frame: Frame?) {}
                    })
                    start(requestVideo = true, requestAudio = false, autoPlayAudio = false)
                }
            }
        }

    }

    internal inner class RtspThread : Thread() {

        private var rtspStopped: AtomicBoolean = AtomicBoolean(false)

        init {
            name = "RTSP IO thread"
        }

        fun stopAsync() {
            if (DEBUG) Log.v(TAG, "stopAsync()")
            rtspStopped.set(true)
            interrupt()
        }

        override fun run() {
            onRtspClientStarted()
            val port = if (uri.port == -1) DEFAULT_RTSP_PORT else uri.port
            try {
                if (DEBUG) Log.d(TAG, "Connecting to ${uri.host.toString()}:$port...")

                val socket: Socket = NetUtils.createSocketAndConnect(uri, port, timeout = 5_000)

                // Blocking call until stopped variable is true or connection failed
                val rtspClient = RtspClient.Builder(socket, uri.toString(), rtspStopped, clientListener)
                    .requestVideo(requestVideo)
                    .requestAudio(requestAudio)
                    .withUserAgent(userAgent)
                    .withCredentials(username, password)
                    .build()

                rtspClient.execute()

                NetUtils.closeSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onRtspClientStopped()
        }

    }

    private lateinit var uri: Uri
    private var username: String? = null
    private var password: String? = null
    private var userAgent: String? = null
    private var requestVideo = true
    private var requestMediaImage = false
    private var requestYuvBytes = false
    private var requestBitmap = false
    private var requestAudio = true
    private var autoPlayAudio = true
    private var rtspThread: RtspThread? = null
    private var videoQueue = FrameQueue(120)
    private var audioQueue = FrameQueue(120)
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    private var statusListener: RtspStatusListener? = null
    private var frameListener: RtspFrameListener? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var surfaceView: SurfaceView? = null
    private var videoMimeType: String = "video/avc"
    private var audioMimeType: String = ""
    private var audioSampleRate: Int = 0
    private var audioChannelCount: Int = 0
    private var audioCodecConfig: ByteArray? = null

    private val clientListener = object : RtspClientListener {

        override fun onRtspConnected(sdpInfo: SdpInfo) {
            if (DEBUG) Log.v(TAG, "onRtspConnected()")
            if (sdpInfo.videoTrack != null) {
                videoQueue.clear()
                when (sdpInfo.videoTrack?.videoCodec) {
                    RtspClientUtils.VIDEO_CODEC_H264 -> videoMimeType = "video/avc"
                    RtspClientUtils.VIDEO_CODEC_H265 -> videoMimeType = "video/hevc"
                }
                when (sdpInfo.audioTrack?.audioCodec) {
                    RtspClientUtils.AUDIO_CODEC_AAC -> audioMimeType = "audio/mp4a-latm"
                }
                val sps: ByteArray? = sdpInfo.videoTrack?.sps
                val pps: ByteArray? = sdpInfo.videoTrack?.pps
                // Initialize decoder
                if (sps != null && pps != null) {
                    val data = ByteArray(sps.size + pps.size)
                    sps.copyInto(data, 0, 0, sps.size)
                    pps.copyInto(data, sps.size, 0, pps.size)
                    videoQueue.push(Frame(data, 0, data.size, 0))
                } else {
                    if (DEBUG) Log.d(TAG, "RTSP SPS and PPS NAL units missed in SDP")
                }
            }
            if (sdpInfo.audioTrack != null) {
                audioQueue.clear()
                when (sdpInfo.audioTrack?.audioCodec) {
                    RtspClientUtils.AUDIO_CODEC_AAC -> audioMimeType = "audio/mp4a-latm"
                }
                audioSampleRate = sdpInfo.audioTrack?.sampleRateHz!!
                audioChannelCount = sdpInfo.audioTrack?.channels!!
                audioCodecConfig = sdpInfo.audioTrack?.config
            }
            startDecoders(sdpInfo)
            uiHandler.post {
                statusListener?.onConnected(sdpInfo)
            }
        }

        override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0) {
                videoQueue.push(Frame(data, offset, length, timestamp))
                frameListener?.onVideoNalUnitReceived(Frame(data, offset, length, timestamp))
            } else {
                frameListener?.onVideoNalUnitReceived(null)
                if (DEBUG) Log.e(TAG, "onRtspVideoNalUnitReceived() zero length")
            }
        }

        override fun onRtspVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv420Bytes: ByteArray?, bitmap: Bitmap?) {
            frameListener?.onVideoFrameReceived(width, height, mediaImage, yuv420Bytes, bitmap)
        }

        override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0) {
                audioQueue.push(Frame(data, offset, length, timestamp))
                frameListener?.onAudioSampleReceived(Frame(data, offset, length, timestamp))
            } else {
                frameListener?.onAudioSampleReceived(null)
                if (DEBUG) Log.e(TAG, "onRtspAudioSampleReceived() zero length")
            }
        }

        override fun onRtspDisconnected() {
            if (DEBUG) Log.v(TAG, "onRtspDisconnected()")
            uiHandler.post {
                statusListener?.onDisconnected()
            }
        }

        override fun onRtspFailedUnauthorized() {
            if (DEBUG) Log.v(TAG, "onRtspFailedUnauthorized()")
            uiHandler.post {
                statusListener?.onUnauthorized()
            }
        }

        override fun onRtspFailed(message: String?) {
            if (DEBUG) Log.v(TAG, "onRtspFailed(message='$message')")
            uiHandler.post {
                statusListener?.onFailed(message)
            }
        }

    }

    private val surfaceCallback = object : SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder) {
            if (DEBUG) Log.v(TAG, "surfaceCreated()")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (DEBUG) Log.v(TAG, "surfaceChanged(format=$format, width=$width, height=$height)")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (DEBUG) Log.v(TAG, "surfaceDestroyed()")
        }

    }

    fun init(url: String, username: String? = null, password: String? = null, userAgent: String? = null) {
        if (DEBUG) Log.v(TAG, "init(uri='$url', username=$username, password=$password, userAgent='$userAgent')")
        this.uri = Uri.parse(url)
        this.username = username
        this.password = password
        this.userAgent = userAgent
    }

    fun start(requestVideo: Boolean = true, requestAudio: Boolean = true, autoPlayAudio: Boolean = true) {
        if (DEBUG) Log.v(TAG, "start()")
        if (isStarted()) return
        rtspThread?.stopAsync()
        this.requestVideo = requestVideo
        this.requestAudio = requestAudio
        this.autoPlayAudio = autoPlayAudio
        rtspThread = RtspThread()
        rtspThread!!.start()
    }

    fun stop() {
        if (DEBUG) Log.v(TAG, "stop()")
        rtspThread?.stopAsync()
        rtspThread = null
    }

    fun isStarted(): Boolean {
        return rtspThread != null
    }

    fun setStatusListener(listener: RtspStatusListener?) {
        if (DEBUG) Log.v(TAG, "setStatusListener()")
        this.statusListener = listener
    }

    fun setFrameListener(listener: RtspFrameListener?) {
        if (DEBUG) Log.v(TAG, "setFrameListener()")
        this.frameListener = listener
    }

    fun setSurfaceView(surfaceView: SurfaceView?) {
        this.surfaceView = surfaceView
        this.videoDecoder?.surfaceView = surfaceView
    }

    fun setRequestMediaImage(requestMediaImage: Boolean) {
        this.requestMediaImage = requestMediaImage
        this.videoDecoder?.requestMediaImage = requestMediaImage
    }

    fun setRequestYuvBytes(requestYuvBytes: Boolean) {
        this.requestYuvBytes = requestYuvBytes
        this.videoDecoder?.requestYuvBytes = requestYuvBytes
    }

    fun setRequestBitmap(requestBitmap: Boolean) {
        this.requestBitmap = requestBitmap
        this.videoDecoder?.requestBitmap = requestBitmap
    }

    private fun onRtspClientStarted() {
        if (DEBUG) Log.v(TAG, "onRtspClientStarted()")
        uiHandler.post { statusListener?.onConnecting() }
    }

    private fun onRtspClientStopped() {
        if (DEBUG) Log.v(TAG, "onRtspClientStopped()")
        stopDecoders()
        rtspThread = null
        uiHandler.post { statusListener?.onDisconnected() }
    }


    private fun startDecoders(sdpInfo: SdpInfo) {
        if (DEBUG) Log.v(TAG, "startDecoders()")
        if (requestVideo && videoMimeType.isNotEmpty()) {
            if (DEBUG) Log.i(TAG, "Starting video decoder with mime type \"$videoMimeType\"")
            surfaceView?.holder?.addCallback(surfaceCallback)
            videoDecoder?.stopAsync()
            videoDecoder = VideoDecoder(
                surfaceView, requestMediaImage, requestYuvBytes, requestBitmap, videoMimeType,
                sdpInfo.videoTrack!!.frameWidth, sdpInfo.videoTrack!!.frameHeight, videoQueue, clientListener
            )
            videoDecoder!!.start()
        }
        if (requestAudio && autoPlayAudio && audioMimeType.isNotEmpty()) {
            if (DEBUG) Log.i(TAG, "Starting audio decoder with mime type \"$audioMimeType\"")
            audioDecoder?.stopAsync()
            audioDecoder = AudioDecoder(audioMimeType, audioSampleRate, audioChannelCount, audioCodecConfig, audioQueue)
            audioDecoder!!.start()
        }
    }

    private fun stopDecoders(video: Boolean = true, audio: Boolean = true) {
        if (DEBUG) Log.v(TAG, "stopDecoders()")
        if (video) {
            surfaceView?.holder?.removeCallback(surfaceCallback)
            videoDecoder?.stopAsync()
            videoDecoder = null
        }
        if (audio) {
            audioDecoder?.stopAsync()
            audioDecoder = null
        }
    }

}
