package ir.am3n.rtsp.client

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
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import ir.am3n.utils.NetUtils
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Rtsp {

    companion object {

        private const val TAG: String = "Rtsp"
        private const val DEFAULT_RTSP_PORT = 554

        suspend fun isOnline(url: String, username: String? = null, password: String? = null, userAgent: String? = null): Boolean {
            return suspendCoroutine {
                Rtsp().apply {
                    init(url, username, password, userAgent)
                    setStatusListener(object : RtspStatusListener {
                        override fun onConnecting() {}
                        override fun onConnected(sdpInfo: SdpInfo) {}
                        override fun onVideoNalUnitReceived(frame: Frame) {
                            setStatusListener(null)
                            stop()
                            it.resume(true)
                        }
                        override fun onAudioSampleReceived(frame: Frame) {}
                        override fun onDisconnected() {
                            setStatusListener(null)
                            it.resume(false)
                        }

                        override fun onUnauthorized() {
                            setStatusListener(null)
                            it.resume(true)
                        }

                        override fun onFailed(message: String?) {
                            setStatusListener(null)
                            it.resume(false)
                        }
                    })
                    start(requestVideo = true, requestAudio = false, autoPlayAudio = false)
                }
            }
        }

    }

    private lateinit var uri: Uri
    private var username: String? = null
    private var password: String? = null
    private var userAgent: String? = null
    private var requestVideo = true
    private var requestAudio = true
    private var autoPlayAudio = true
    private var rtspThread: RtspThread? = null
    private var videoQueue = FrameQueue(120)
    private var audioQueue = FrameQueue(120)
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    private var surfaceView: SurfaceView? = null
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080
    private var statusListener: RtspStatusListener? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var videoMimeType: String = "video/avc"
    private var audioMimeType: String = ""
    private var audioSampleRate: Int = 0
    private var audioChannelCount: Int = 0
    private var audioCodecConfig: ByteArray? = null

    private val proxyClientListener = object : RtspClientListener {

        override fun onRtspConnected(sdpInfo: SdpInfo) {
            Log.v(TAG, "onRtspConnected()")
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
                    Log.d(TAG, "RTSP SPS and PPS NAL units missed in SDP")
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
            startDecoders()
            uiHandler.post {
                statusListener?.onConnected(sdpInfo)
            }
        }

        override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0) {
                videoQueue.push(Frame(data, offset, length, timestamp))
                uiHandler.post {
                    statusListener?.onVideoNalUnitReceived(Frame(data, offset, length, timestamp))
                }
            } else {
                Log.e(TAG, "onRtspVideoNalUnitReceived() zero length")
            }
        }

        override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0) {
                audioQueue.push(Frame(data, offset, length, timestamp))
                uiHandler.post {
                    statusListener?.onAudioSampleReceived(Frame(data, offset, length, timestamp))
                }
            } else {
                Log.e(TAG, "onRtspAudioSampleReceived() zero length")
            }
        }

        override fun onRtspDisconnected() {
            Log.v(TAG, "onRtspDisconnected()")
            uiHandler.post {
                statusListener?.onDisconnected()
            }
        }

        override fun onRtspFailedUnauthorized() {
            Log.v(TAG, "onRtspFailedUnauthorized()")
            uiHandler.post {
                statusListener?.onUnauthorized()
            }
        }

        override fun onRtspFailed(message: String?) {
            Log.v(TAG, "onRtspFailed(message='$message')")
            uiHandler.post {
                statusListener?.onFailed(message)
            }
        }

    }

    private val surfaceCallback = object : SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.v(TAG, "surfaceCreated()")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.v(TAG, "surfaceChanged(format=$format, width=$width, height=$height)")
            surfaceWidth = width
            surfaceHeight = height
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.v(TAG, "surfaceDestroyed()")
            stopDecoders(video = true, audio = false)
        }

    }


    inner class RtspThread : Thread() {

        private var rtspStopped: AtomicBoolean = AtomicBoolean(false)

        init {
            name = "RTSP IO thread"
        }

        fun stopAsync() {
            Log.v(TAG, "stopAsync()")
            rtspStopped.set(true)
            interrupt()
        }

        override fun run() {
            onRtspClientStarted()
            val port = if (uri.port == -1) DEFAULT_RTSP_PORT else uri.port
            try {
                Log.d(TAG, "Connecting to ${uri.host.toString()}:$port...")

                val socket: Socket = NetUtils.createSocketAndConnect(uri, port, timeout = 5_000)

                // Blocking call until stopped variable is true or connection failed
                val rtspClient = RtspClient.Builder(socket, uri.toString(), rtspStopped, proxyClientListener)
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


    fun init(url: String, username: String? = null, password: String? = null, userAgent: String? = null) {
        Log.v(TAG, "init(uri='$url', username=$username, password=$password, userAgent='$userAgent')")
        this.uri = Uri.parse(url)
        this.username = username
        this.password = password
        this.userAgent = userAgent
    }

    fun start(requestVideo: Boolean = true, requestAudio: Boolean = true, autoPlayAudio: Boolean = true) {
        Log.v(TAG, "start()")
        if (isStarted()) return
        rtspThread?.stopAsync()
        this.requestVideo = requestVideo
        this.requestAudio = requestAudio
        this.autoPlayAudio = autoPlayAudio
        rtspThread = RtspThread()
        rtspThread!!.start()
    }

    fun stop() {
        Log.v(TAG, "stop()")
        rtspThread?.stopAsync()
        rtspThread = null
    }

    fun isStarted(): Boolean {
        return rtspThread != null
    }

    fun setStatusListener(listener: RtspStatusListener?) {
        Log.v(TAG, "setStatusListener()")
        this.statusListener = listener
    }

    fun setSurfaceView(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        if (isStarted()) {
            startDecoders()
        }
    }

    private fun onRtspClientStarted() {
        Log.v(TAG, "onRtspClientStarted()")
        uiHandler.post { statusListener?.onConnecting() }
    }

    private fun onRtspClientStopped() {
        Log.v(TAG, "onRtspClientStopped()")
        stopDecoders()
        rtspThread = null
        uiHandler.post { statusListener?.onDisconnected() }
    }


    private fun startDecoders() {
        Log.v(TAG, "startDecoders()")
        if (surfaceView != null && videoMimeType.isNotEmpty()) {
            Log.i(TAG, "Starting video decoder with mime type \"$videoMimeType\"")
            surfaceView!!.holder.addCallback(surfaceCallback)
            videoDecoder?.stopAsync()
            videoDecoder = VideoDecoder(surfaceView!!.holder.surface, videoMimeType, surfaceWidth, surfaceHeight, videoQueue)
            videoDecoder!!.start()
        }
        if (autoPlayAudio && audioMimeType.isNotEmpty()) {
            Log.i(TAG, "Starting audio decoder with mime type \"$audioMimeType\"")
            audioDecoder?.stopAsync()
            audioDecoder = AudioDecoder(audioMimeType, audioSampleRate, audioChannelCount, audioCodecConfig, audioQueue)
            audioDecoder!!.start()
        }
    }

    private fun stopDecoders(video: Boolean = true, audio: Boolean = true) {
        Log.v(TAG, "stopDecoders()")
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
