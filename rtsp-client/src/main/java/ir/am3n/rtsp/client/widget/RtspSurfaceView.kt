package ir.am3n.rtsp.client.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.container.NalUnitUtil
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.RtspClient
import ir.am3n.rtsp.client.RtspClientUtils.AUDIO_CODEC_AAC
import ir.am3n.rtsp.client.RtspClientUtils.AUDIO_CODEC_OPUS
import ir.am3n.rtsp.client.RtspClientUtils.VIDEO_CODEC_H264
import ir.am3n.rtsp.client.RtspClientUtils.VIDEO_CODEC_H265
import ir.am3n.rtsp.client.data.AudioFrame
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.data.VideoFrame
import ir.am3n.rtsp.client.decoders.AudioDecoder
import ir.am3n.rtsp.client.decoders.AudioFrameQueue
import ir.am3n.rtsp.client.decoders.VideoDecoder
import ir.am3n.rtsp.client.decoders.VideoFrameQueue
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import ir.am3n.utils.AudioCodecType
import ir.am3n.utils.ByteUtils.toHexString
import ir.am3n.utils.DecoderType
import ir.am3n.utils.NetUtils
import ir.am3n.utils.VideoCodecType
import ir.am3n.utils.VideoCodecUtils
import ir.am3n.utils.spsDataToString
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

open class RtspSurfaceView : SurfaceView {

    companion object {
        private const val TAG: String = "RtspSurfaceView"
        private const val DEFAULT_RTSP_PORT = 554
    }

    private lateinit var uri: Uri
    private var username: String? = null
    private var password: String? = null
    private var userAgent: String? = null
    private var playVideo = true
    private var playAudio = true
    private var rtspThread: RtspThread? = null
    private var videoFrameQueue = VideoFrameQueue(frameQueueCapacity = 60)
    private var audioFrameQueue = AudioFrameQueue(frameQueueCapacity = 10)
    private var videoDecodeThread: VideoDecoder? = null
    private var audioDecodeThread: AudioDecoder? = null
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080
    private var statusListener: RtspStatusListener? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var videoMimeType: String = "video/avc"
    private var audioMimeType: String = ""
    private var audioSampleRate: Int = 0
    private var audioChannelCount: Int = 0
    private var audioCodecConfig: ByteArray? = null
    private var firstFrameRendered = false

    var statistics = Statistics()
        get() {
            videoDecodeThread?.let { decoder ->
                field.apply {
                    networkLatencyMillis = decoder.getCurrentNetworkLatencyMillis()
                    videoDecoderLatencyMillis = decoder.getCurrentVideoDecoderLatencyMillis()
                    videoDecoderType = decoder.getCurrentVideoDecoderType()
                    videoDecoderName = decoder.getCurrentVideoDecoderName()
                }
            }
            return field
        }
        private set

    /**
     * Show more debug info on console on runtime.
     */
    var debug = false

    /**
     * Video rotation in degrees. Allowed values: 0, 90, 180, 270.
     * Note that not all hardware video decoders support rotation.
     */
    var videoRotation = 0
        set(value) {
            if (value == 0 || value == 90 || value == 180 || value == 270)
                field = value
        }

    /**
     * Requested video decoder type.
     */
    var videoDecoderType = DecoderType.HARDWARE

    private val proxyClientListener = object : RtspClientListener {

        override fun onRtspConnecting() {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspConnecting()")
            uiHandler.post {
                statusListener?.onConnecting()
            }
        }

        override fun onRtspConnected(sdpInfo: SdpInfo) {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspConnected()")
            if (sdpInfo.videoTrack != null) {
                videoFrameQueue.clear()
                when (sdpInfo.videoTrack?.videoCodec) {
                    VIDEO_CODEC_H264 -> videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
                    VIDEO_CODEC_H265 -> videoMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC
                }
                when (sdpInfo.audioTrack?.audioCodec) {
                    AUDIO_CODEC_AAC -> audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
                    AUDIO_CODEC_OPUS -> audioMimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
                }
                val sps: ByteArray? = sdpInfo.videoTrack?.sps
                val pps: ByteArray? = sdpInfo.videoTrack?.pps
                // Initialize decoder
                @SuppressLint("UnsafeOptInUsageError")
                if (sps != null && pps != null) {
                    val data = ByteArray(sps.size + pps.size)
                    sps.copyInto(data, 0, 0, sps.size)
                    pps.copyInto(data, sps.size, 0, pps.size)
                    videoFrameQueue.push(
                        VideoFrame(
                            VideoCodecType.H264,
                            isKeyframe = true,
                            data,
                            0,
                            data.size,
                            0
                        )
                    )
                    try {
                        val offset = if (sps[3] == 1.toByte()) 5 else 4
                        val spsData = NalUnitUtil.parseSpsNalUnitPayload(
                            data, offset, data.size - offset
                        )
                        if (spsData.maxNumReorderFrames > 0) {
                            Log.w(
                                TAG, "SPS frame param max_num_reorder_frames=" +
                                        "${spsData.maxNumReorderFrames} is too high" +
                                        " for low latency decoding (expecting 0)."
                            )
                        }
                        if (Rtsp.DEBUG) {
                            Log.d(TAG, "SPS frame: " + sps.toHexString(0, sps.size))
                            Log.d(TAG, "\t${spsData.spsDataToString()}")
                            Log.d(TAG, "PPS frame: " + pps.toHexString(0, pps.size))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    if (Rtsp.DEBUG) Log.d(TAG, "RTSP SPS and PPS NAL units missed in SDP")
                }
            }
            if (sdpInfo.audioTrack != null) {
                audioFrameQueue.clear()
                when (sdpInfo.audioTrack?.audioCodec) {
                    AUDIO_CODEC_AAC -> audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
                    AUDIO_CODEC_OPUS -> audioMimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
                }
                audioSampleRate = sdpInfo.audioTrack?.sampleRateHz!!
                audioChannelCount = sdpInfo.audioTrack?.channels!!
                audioCodecConfig = sdpInfo.audioTrack?.config
            }
            onRtspClientConnected()
            uiHandler.post {
                statusListener?.onConnected(sdpInfo)
            }
        }

        override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspVideoNalUnitReceived(length=$length)")

            // Search for NAL_IDR_SLICE within first 1KB maximum
            val isKeyframe = VideoCodecUtils.isAnyH264KeyFrame(data, offset, min(length, 1000))
            if (debug) {
                val nalList = ArrayList<VideoCodecUtils.NalUnit>()
                VideoCodecUtils.getH264NalUnits(data, offset, length, nalList)
                var b = StringBuilder()
                for (nal in nalList) {
                    b.append(VideoCodecUtils.getH264NalUnitTypeString(nal.type)).append(" (${nal.length}), ")
                }
                if (b.length > 2)
                    b = b.removeRange(b.length - 2, b.length) as StringBuilder
                Log.d(TAG, "NALs: $b")
                @SuppressLint("UnsafeOptInUsageError")
                if (isKeyframe) {
                    val sps = VideoCodecUtils.getSpsNalUnitFromArray(
                        data,
                        offset,
                        // Check only first 100 bytes maximum. That's enough for finding SPS NAL unit.
                        Integer.min(length, 100)
                    )
                    Log.d(
                        TAG, "\tKey frame received ($length} bytes, ts=${timestamp}," +
                                " profile=${sps?.profileIdc}, level=${sps?.levelIdc})"
                    )
                }
            }
            videoFrameQueue.push(
                VideoFrame(
                    VideoCodecType.H264,
                    isKeyframe,
                    data,
                    offset,
                    length,
                    timestamp,
                    capturedTimestamp = System.currentTimeMillis()
                )
            )
        }

        override fun onRtspVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuvBytes: ByteArray?, bitmap: Bitmap?) {

        }

        override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0)
                audioFrameQueue.push(
                    AudioFrame(
                        AudioCodecType.AAC_LC,
                        data, offset,
                        length,
                        timestamp
                    )
                )
        }

        override fun onRtspDisconnecting() {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspDisconnecting()")
            uiHandler.post {
                statusListener?.onDisconnecting()
            }
        }

        override fun onRtspDisconnected() {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspDisconnected()")
            uiHandler.post {
                statusListener?.onDisconnected()
            }
        }

        override fun onRtspFailedUnauthorized() {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspFailedUnauthorized()")
            uiHandler.post {
                statusListener?.onUnauthorized()
            }
        }

        override fun onRtspFailed(message: String?) {
            if (Rtsp.DEBUG) Log.v(TAG, "onRtspFailed(message='$message')")
            uiHandler.post {
                statusListener?.onFailed(message)
            }
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (Rtsp.DEBUG) Log.v(TAG, "surfaceCreated()")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (Rtsp.DEBUG) Log.v(TAG, "surfaceChanged(format=$format, width=$width, height=$height)")
            surfaceWidth = width
            surfaceHeight = height
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (Rtsp.DEBUG) Log.v(TAG, "surfaceDestroyed()")
            stopDecoders()
        }
    }

    constructor(context: Context) : super(context) {
        initView()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initView()
    }

    private fun initView() {
        if (Rtsp.DEBUG) Log.v(TAG, "initView()")
        holder.addCallback(surfaceCallback)
    }

    fun init(uri: Uri, username: String? = null, password: String? = null, userAgent: String? = null) {
        if (Rtsp.DEBUG) Log.v(TAG, "init(uri='$uri', username='$username', password='$password', userAgent='$userAgent')")
        this.uri = uri
        this.username = username
        this.password = password
        this.userAgent = userAgent
    }

    fun start(playVideo: Boolean, playAudio: Boolean) {
        if (Rtsp.DEBUG) Log.v(TAG, "start(playVideo=$playVideo, playAudio=$playAudio)")
        if (rtspThread != null) rtspThread?.stopAsync()
        this.playVideo = playVideo
        this.playAudio = playAudio
        rtspThread = RtspThread().apply {
            name = "RTSP IO thread [${getUriName()}]"
            start()
        }
    }

    fun stop() {
        if (Rtsp.DEBUG) Log.v(TAG, "stop()")
        rtspThread?.stopAsync()
        rtspThread = null
    }

    fun isStarted(): Boolean {
        return rtspThread != null
    }

    inner class RtspThread : Thread() {

        private var rtspStopped: AtomicBoolean = AtomicBoolean(false)

        fun stopAsync() {
            if (Rtsp.DEBUG) Log.v(TAG, "stopAsync()")
            rtspStopped.set(true)
            // Wake up sleep() code
            interrupt()
        }

        override fun run() {
            onRtspClientStarted()
            val port = if (uri.port == -1) DEFAULT_RTSP_PORT else uri.port
            try {
                if (Rtsp.DEBUG) Log.d(TAG, "Connecting to ${uri.host.toString()}:$port...")

                val socket: Socket = if (uri.scheme?.lowercase() == "rtsps")
                    NetUtils.createSslSocketAndConnect(uri.host.toString(), port, 5000)
                else
                    NetUtils.createSocketAndConnect(uri.host.toString(), port, 5000)

                // Blocking call until stopped variable is true or connection failed
                val rtspClient = RtspClient.Builder(socket, uri.toString(), rtspStopped, proxyClientListener)
                    .requestVideo(playVideo)
                    .requestAudio(playAudio)
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

    fun setStatusListener(listener: RtspStatusListener?) {
        if (Rtsp.DEBUG) Log.v(TAG, "setStatusListener()")
        this.statusListener = listener
    }

    private fun onRtspClientStarted() {
        if (Rtsp.DEBUG) Log.v(TAG, "onRtspClientStarted()")
    }

    private fun onRtspClientConnected() {
        if (Rtsp.DEBUG) Log.v(TAG, "onRtspClientConnected()")
        if (videoMimeType.isNotEmpty()) {
            Log.i(TAG, "Starting video decoder with mime type \"$videoMimeType\"")
            firstFrameRendered = false
            videoDecodeThread = VideoDecoder(
                surface = holder.surface,
                surfaceView = null,
                requestMediaImage = false,
                requestYuvBytes = false,
                requestBitmap = false,
                mimeType = videoMimeType,
                surfaceWidth,
                surfaceHeight,
                videoRotation,
                videoFrameQueue,
                videoDecoderType,
                clientListener = null,
                frameRenderedListener = { _, _, _ ->
                    if (!firstFrameRendered) {
                        firstFrameRendered = true
                        if (Rtsp.DEBUG) Log.v(TAG, "FrameRenderedListener()  firstFrameRendered")
                        uiHandler.post {
                            statusListener?.onFirstFrameRendered()
                        }
                    }
                }
            )
            videoDecodeThread!!.apply {
                name = "RTSP video thread [${getUriName()}]"
                start()
            }
        }
        if (audioMimeType.isNotEmpty()) {
            Log.i(TAG, "Starting audio decoder with mime type \"$audioMimeType\"")
            audioDecodeThread = AudioDecoder(
                audioMimeType, audioSampleRate, audioChannelCount,
                audioCodecConfig, audioFrameQueue
            )
            audioDecodeThread!!.apply {
                name = "RTSP audio thread [${getUriName()}]"
                start()
            }
        }
    }

    private fun onRtspClientStopped() {
        if (Rtsp.DEBUG) Log.v(TAG, "onRtspClientStopped()")
        stopDecoders()
        rtspThread = null
    }

    private fun stopDecoders() {
        if (Rtsp.DEBUG) Log.v(TAG, "stopDecoders()")
        videoDecodeThread?.stopAsync()
        videoDecodeThread = null
        audioDecodeThread?.stopAsync()
        audioDecodeThread = null
    }

    private fun getUriName(): String {
        val port = if (uri.port == -1) DEFAULT_RTSP_PORT else uri.port
        return "${uri.host.toString()}:$port"
    }

}
