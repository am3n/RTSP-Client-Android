package ir.am3n.rtsp.client

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.Pair
import ir.am3n.rtsp.client.data.AudioTrack
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.data.Track
import ir.am3n.rtsp.client.data.VideoTrack
import ir.am3n.rtsp.client.exceptions.UnauthorizedException
import ir.am3n.rtsp.client.interfaces.RtspClientKeepAliveListener
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import ir.am3n.rtsp.client.parser.AacParser
import ir.am3n.rtsp.client.parser.RtpParser
import ir.am3n.rtsp.client.parser.VideoRtpParser
import ir.am3n.utils.NetUtils
import ir.am3n.utils.VideoCodecUtils
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


internal object RtspClientUtils {

    private const val TAG = "RtspClientUtils"

    private const val RTSP_CAPABILITY_NONE = 0
    private const val RTSP_CAPABILITY_OPTIONS = 1 shl 1
    private const val RTSP_CAPABILITY_DESCRIBE = 1 shl 2
    private const val RTSP_CAPABILITY_ANNOUNCE = 1 shl 3
    private const val RTSP_CAPABILITY_SETUP = 1 shl 4
    private const val RTSP_CAPABILITY_PLAY = 1 shl 5
    private const val RTSP_CAPABILITY_RECORD = 1 shl 6
    private const val RTSP_CAPABILITY_PAUSE = 1 shl 7
    internal const val RTSP_CAPABILITY_TEARDOWN = 1 shl 8
    private const val RTSP_CAPABILITY_SET_PARAMETER = 1 shl 9
    internal const val RTSP_CAPABILITY_GET_PARAMETER = 1 shl 10
    private const val RTSP_CAPABILITY_REDIRECT = 1 shl 11

    internal const val VIDEO_CODEC_H264 = 0
    internal const val VIDEO_CODEC_H265 = 1
    internal const val AUDIO_CODEC_UNKNOWN = -1
    internal const val AUDIO_CODEC_AAC = 0
    internal const val CRLF = "\r\n"

    // Size of buffer for reading from the connection
    internal const val MAX_LINE_SIZE = 4098

    internal fun hasCapability(capability: Int, capabilitiesMask: Int): Boolean {
        return capabilitiesMask and capability != 0
    }

    internal fun getUriForSetup(uriRtsp: String, track: Track?): String? {
        if (track == null || TextUtils.isEmpty(track.request)) return null
        var uriRtspSetup: String? = uriRtsp
        if (track.request!!.startsWith("rtsp://") || track.request!!.startsWith("rtsps://")) {
            // Absolute URL
            uriRtspSetup = track.request
        } else {
            // Relative URL
            if (!track.request!!.startsWith("/")) {
                track.request = "/" + track.request
            }
            uriRtspSetup += track.request
        }
        return uriRtspSetup
    }

    @Throws(InterruptedException::class)
    internal fun checkExitFlag(exitFlag: AtomicBoolean) {
        if (exitFlag.get()) throw InterruptedException()
    }

    @Throws(IOException::class)
    internal fun checkStatusCode(code: Int) {
        when (code) {
            200 -> {}
            401 -> throw UnauthorizedException()
            else -> throw IOException("Invalid status code $code")
        }
    }

    @Throws(IOException::class)
    internal fun readRtpData(
        inputStream: InputStream,
        sdpInfo: SdpInfo,
        exitFlag: AtomicBoolean,
        listener: RtspClientListener,
        keepAliveTimeout: Int,
        keepAliveListener: RtspClientKeepAliveListener
    ) {
        var data = ByteArray(0) // Usually not bigger than MTU = 15KB
        val videoParser = if (sdpInfo.videoTrack != null) VideoRtpParser() else null
        val audioParser = if (sdpInfo.audioTrack?.audioCodec == AUDIO_CODEC_AAC) AacParser(sdpInfo.audioTrack!!.mode!!) else null
        var nalUnitSps = if (sdpInfo.videoTrack != null) sdpInfo.videoTrack!!.sps else null
        var nalUnitPps = if (sdpInfo.videoTrack != null) sdpInfo.videoTrack!!.pps else null
        var keepAliveSent = System.currentTimeMillis()
        while (!exitFlag.get()) {

            //Log.d(TAG, "readRdpData() > readHeader()")
            val header = RtpParser.readHeader(inputStream)
                ?: throw IOException("No RTP frame header found")

            if (header.payloadSize > data.size) {
                data = ByteArray(header.payloadSize)
            }

            //Log.d(TAG, "readRdpData() > readData()")
            NetUtils.readData(inputStream, data, 0, header.payloadSize)

            // Check if keep-alive should be sent
            val l = System.currentTimeMillis()
            if (keepAliveTimeout > 0 && l - keepAliveSent > keepAliveTimeout) {
                keepAliveSent = l
                keepAliveListener.onRtspKeepAliveRequested()
            }

            //Log.d(TAG, "readRdpData() > check track & payloadType")

            // Video
            if (header.payloadType == sdpInfo.videoTrack?.payloadType) {
                val nalUnit = videoParser?.processRtpPacketAndGetNalUnit(data, header.payloadSize)
                if (nalUnit != null) {
                    when (VideoCodecUtils.getH264NalUnitType(nalUnit, 0, nalUnit.size)) {
                        VideoCodecUtils.NAL_SPS -> {
                            nalUnitSps = nalUnit
                            // Looks like there is NAL_IDR_SLICE as well. Send it now.
                            if (nalUnit.size > 100)
                                listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.size, (header.timeStamp * 11.111111).toLong())
                        }
                        VideoCodecUtils.NAL_PPS -> {
                            nalUnitPps = nalUnit
                            // Looks like there is NAL_IDR_SLICE as well. Send it now.
                            if (nalUnit.size > 100)
                                listener.onRtspVideoNalUnitReceived(nalUnit, 0, nalUnit.size, (header.timeStamp * 11.111111).toLong())
                        }
                        VideoCodecUtils.NAL_IDR_SLICE -> {
                            // Combine IDR with SPS/PPS
                            if (nalUnitSps != null && nalUnitPps != null) {
                                val nalUnitSppPps = ByteArray(nalUnitSps.size + nalUnitPps.size)
                                System.arraycopy(nalUnitSps, 0, nalUnitSppPps, 0, nalUnitSps.size)
                                System.arraycopy(nalUnitPps, 0, nalUnitSppPps, nalUnitSps.size, nalUnitPps.size)
                                listener.onRtspVideoNalUnitReceived(
                                    nalUnitSppPps,
                                    offset = 0,
                                    length = nalUnitSppPps.size,
                                    timestamp = (header.timeStamp * 11.111111).toLong()
                                )
                                // Send it only once
                                nalUnitSps = null
                                nalUnitPps = null
                            }
                            listener.onRtspVideoNalUnitReceived(nalUnit, offset = 0, nalUnit.size, (header.timeStamp * 11.111111).toLong())
                        }
                        else ->
                            listener.onRtspVideoNalUnitReceived(nalUnit, offset = 0, length = nalUnit.size, timestamp = (header.timeStamp * 11.111111).toLong())
                    }
                }

                // Audio
            } else if (header.payloadType == sdpInfo.audioTrack?.payloadType) {
                val sample = audioParser?.processRtpPacketAndGetSample(data, header.payloadSize)
                if (sample != null)
                    listener.onRtspAudioSampleReceived(sample, offset = 0, sample.size, (header.timeStamp * 11.111111).toLong())

                // Unknown
            } else {
                // https://www.iana.org/assignments/rtp-parameters/rtp-parameters.xhtml
                Log.w(TAG, "Invalid RTP payload type " + header.payloadType)
            }
        }
    }

    @Throws(IOException::class)
    internal fun sendSimpleCommand(
        command: String,
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        session: String?,
        authToken: String?
    ) {
        outputStream.write(("$command $request RTSP/1.0$CRLF").toByteArray())
        if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
        outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
        if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
        if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
        outputStream.write(CRLF.toByteArray())
        outputStream.flush()
    }

    @Throws(IOException::class)
    internal fun sendOptionsCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        authToken: String?
    ) {
        Log.v(TAG, "sendOptionsCommand(request=\"$request\", cSeq=$cSeq)")
        sendSimpleCommand("OPTIONS", outputStream, request, cSeq, userAgent, null, authToken)
    }

    @Throws(IOException::class)
    internal fun sendGetParameterCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        session: String?,
        authToken: String?
    ) {
        Log.v(TAG, "sendGetParameterCommand(request=\"$request\", cSeq=$cSeq)")
        sendSimpleCommand("GET_PARAMETER", outputStream, request, cSeq, userAgent, session, authToken)
    }

    @Throws(IOException::class)
    internal fun sendDescribeCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        authToken: String?
    ) {
        Log.v(TAG, "sendDescribeCommand(request=\"$request\", cSeq=$cSeq)")
        outputStream.write(("DESCRIBE $request RTSP/1.0$CRLF").toByteArray())
        outputStream.write(("Accept: application/sdp$CRLF").toByteArray())
        if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
        outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
        if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
        outputStream.write(CRLF.toByteArray())
        outputStream.flush()
    }

    @Throws(IOException::class)
    internal fun sendTeardownCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        authToken: String?,
        session: String?
    ) {
        Log.v(TAG, "sendTeardownCommand(request=\"$request\", cSeq=$cSeq)")
        outputStream.write(("TEARDOWN $request RTSP/1.0$CRLF").toByteArray())
        if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
        outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
        if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
        if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
        outputStream.write(CRLF.toByteArray())
        outputStream.flush()
    }

    @Throws(IOException::class)
    internal fun sendSetupCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        authToken: String?,
        session: String?,
        interleaved: String
    ) {
        Log.v(TAG, "sendSetupCommand(request=\"$request\", cSeq=$cSeq)")
        outputStream.write(("SETUP $request RTSP/1.0$CRLF").toByteArray())
        outputStream.write(("Transport: RTP/AVP/TCP;unicast;interleaved=$interleaved$CRLF").toByteArray())
        if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
        outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
        if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
        if (session != null) outputStream.write(("Session: $session$CRLF").toByteArray())
        outputStream.write(CRLF.toByteArray())
        outputStream.flush()
    }

    @Throws(IOException::class)
    internal fun sendPlayCommand(
        outputStream: OutputStream,
        request: String,
        cSeq: Int,
        userAgent: String?,
        authToken: String?,
        session: String
    ) {
        Log.v(TAG, "sendPlayCommand(request=\"$request\", cSeq=$cSeq)")
        outputStream.write(("PLAY $request RTSP/1.0$CRLF").toByteArray())
        outputStream.write(("Range: npt=0.000-$CRLF").toByteArray())
        if (authToken != null) outputStream.write(("Authorization: $authToken$CRLF").toByteArray())
        outputStream.write(("CSeq: $cSeq$CRLF").toByteArray())
        if (userAgent != null) outputStream.write(("User-Agent: $userAgent$CRLF").toByteArray())
        outputStream.write(("Session: $session$CRLF").toByteArray())
        outputStream.write(CRLF.toByteArray())
        outputStream.flush()
    }

    /**
     * Get a list of tracks from SDP. Usually contains video and audio track only.
     *
     * @return array of 2 tracks. First is video track, second audio track.
     */
    private fun getTracksFromDescribeParams(params: List<Pair<String, String>>): Array<Track?> {
        val tracks = arrayOfNulls<Track>(2)
        var currentTrack: Track? = null
        for (param in params) {
            when (param.first) {
                "m" -> {
                    if (param.second.startsWith("video")) {
                        currentTrack = VideoTrack()
                        tracks[0] = currentTrack
                    } else if (param.second.startsWith("audio")) {
                        currentTrack = AudioTrack()
                        tracks[1] = currentTrack
                    } else {
                        currentTrack = null
                    }
                    if (currentTrack != null) {
                        val values = TextUtils.split(param.second, " ")
                        currentTrack.payloadType = if (values.size > 3) values[3].toInt() else -1
                        if (currentTrack.payloadType == -1) Log.e(TAG, "Failed to get payload type from \"m=" + param.second + "\"")
                    }
                }
                "a" -> {
                    if (currentTrack != null) {
                        if (param.second.startsWith("control:")) {
                            currentTrack.request = param.second.substring(8)
                        } else if (param.second.startsWith("fmtp:")) {
                            if (currentTrack is VideoTrack) {
                                // Video
                                updateVideoTrackFromDescribeParam(tracks[0] as VideoTrack, param)
                            } else {
                                // Audio
                                updateAudioTrackFromDescribeParam(tracks[1] as AudioTrack, param)
                            }
                        } else if (param.second.startsWith("framesize:")) {
                            var values = TextUtils.split(param.second, " ")
                            if (values.size > 1) {
                                values = TextUtils.split(values[1], "-")
                                if (values.size == 2) {
                                    (tracks[0] as VideoTrack).frameWidth = values[0].toInt()
                                    (tracks[0] as VideoTrack).frameHeight = values[1].toInt()
                                }
                            }

                        } else if (param.second.startsWith("rtpmap:")) {
                            if (currentTrack is VideoTrack) {
                                // Video
                                var values = TextUtils.split(param.second, " ")
                                if (values.size > 1) {
                                    values = TextUtils.split(values[1], "/")
                                    if (values.isNotEmpty()) {
                                        when (values[0].lowercase(Locale.getDefault())) {
                                            "h264" -> (tracks[0] as VideoTrack).videoCodec = VIDEO_CODEC_H264
                                            "h265" -> (tracks[0] as VideoTrack).videoCodec = VIDEO_CODEC_H265
                                            else -> Log.w(TAG, "Unknown video codec \"" + values[0] + "\"")
                                        }
                                        Log.i(TAG, "Video: " + values[0])
                                    }
                                }
                            } else {
                                // Audio
                                var values = TextUtils.split(param.second, " ")
                                if (values.size > 1) {
                                    val track = tracks[1] as AudioTrack
                                    values = TextUtils.split(values[1], "/")
                                    if (values.size > 1) {
                                        if ("mpeg4-generic".equals(values[0], ignoreCase = true)) {
                                            track.audioCodec = AUDIO_CODEC_AAC
                                        } else {
                                            Log.w(TAG, "Unknown audio codec \"" + values[0] + "\"")
                                            track.audioCodec = AUDIO_CODEC_UNKNOWN
                                        }
                                        track.sampleRateHz = values[1].toInt()
                                        // If no channels specified, use mono, e.g. "a=rtpmap:97 MPEG4-GENERIC/8000"
                                        track.channels = if (values.size > 2) values[2].toInt() else 1
                                        Log.i(
                                            TAG, "Audio: " + (if (track.audioCodec == AUDIO_CODEC_AAC) "AAC LC" else "n/a") + ", sample rate: "
                                                    + track.sampleRateHz + " Hz, channels: " + track.channels
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return tracks
    }

    internal fun getDescribeParams(text: String): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val params = TextUtils.split(text, "\r\n")
        for (param in params) {
            val i = param.indexOf('=')
            if (i > 0) {
                val name = param.substring(0, i).trim { it <= ' ' }
                val value = param.substring(i + 1)
                list.add(Pair.create(name, value))
            }
        }
        return list
    }

    internal fun getSdpInfoFromDescribeParams(params: List<Pair<String, String>>): SdpInfo {
        val sdpInfo = SdpInfo()
        getTracksFromDescribeParams(params).let { tracks ->
            sdpInfo.videoTrack = tracks[0] as VideoTrack?
            sdpInfo.audioTrack = tracks[1] as AudioTrack?
        }
        for (param in params) {
            when (param.first) {
                "s" -> sdpInfo.sessionName = param.second
                "i" -> sdpInfo.sessionDescription = param.second
            }
        }
        return sdpInfo
    }

    private fun getSdpAParams(param: Pair<String, String>): List<Pair<String, String>>? {
        if (param.first == "a" && param.second.startsWith("fmtp:")) { //
            val value = param.second.substring(8).trim { it <= ' ' } // fmtp can be '96' (2 chars) and '127' (3 chars)
            val paramsA = TextUtils.split(value, ";")
            val retParams = ArrayList<Pair<String, String>>()
            for (p in paramsA) {
                val trimmed = p.trim { it <= ' ' }
                val i = trimmed.indexOf("=")
                if (i != -1) {
                    retParams.add(Pair.create(trimmed.substring(0, i), trimmed.substring(i + 1)))
                }
            }
            return retParams
        } else {
            Log.e(TAG, "Not a valid fmtp")
        }
        return null
    }

    private fun updateVideoTrackFromDescribeParam(videoTrack: VideoTrack, param: Pair<String, String>) {
        val params = getSdpAParams(param)
        if (params != null) {
            for (pair in params) {
                if ("sprop-parameter-sets".equals(pair.first, ignoreCase = true)) {
                    val paramsSpsPps = TextUtils.split(pair.second, ",")
                    if (paramsSpsPps.size > 1) {
                        val sps = Base64.decode(paramsSpsPps[0], Base64.NO_WRAP)
                        val pps = Base64.decode(paramsSpsPps[1], Base64.NO_WRAP)
                        val nalSps = ByteArray(sps.size + 4)
                        val nalPps = ByteArray(pps.size + 4)
                        // Add 00 00 00 01 NAL unit header
                        nalSps[0] = 0
                        nalSps[1] = 0
                        nalSps[2] = 0
                        nalSps[3] = 1
                        System.arraycopy(sps, 0, nalSps, 4, sps.size)
                        nalPps[0] = 0
                        nalPps[1] = 0
                        nalPps[2] = 0
                        nalPps[3] = 1
                        System.arraycopy(pps, 0, nalPps, 4, pps.size)
                        videoTrack.sps = nalSps
                        videoTrack.pps = nalPps
                    }
                }
            }
        }
    }

    private fun updateAudioTrackFromDescribeParam(audioTrack: AudioTrack, param: Pair<String, String>) {
        val params = getSdpAParams(param)
        if (params != null) {
            for (pair in params) {
                when (pair.first.lowercase(Locale.getDefault())) {
                    "mode" -> audioTrack.mode = pair.second
                    "config" -> audioTrack.config = getBytesFromHexString(pair.second)
                }
            }
        }
    }

    private fun getBytesFromHexString(config: String): ByteArray {
        return BigInteger(config, 16).toByteArray()
    }

    internal fun getHeaderContentLength(headers: ArrayList<Pair<String, String>>): Int {
        val length = getHeader(headers, "content-length")
        if (!TextUtils.isEmpty(length)) {
            try {
                return length!!.toInt()
            } catch (ignored: NumberFormatException) {
            }
        }
        return -1
    }

    internal fun getSupportedCapabilities(headers: ArrayList<Pair<String, String>>): Int {
        for (head in headers) {
            val h = head.first.lowercase(Locale.getDefault())
            if ("public" == h) {
                var mask = 0
                val tokens = TextUtils.split(head.second.lowercase(Locale.getDefault()), ",")
                for (token in tokens) {
                    when (token.trim { it <= ' ' }) {
                        "options" -> mask = mask or RTSP_CAPABILITY_OPTIONS
                        "describe" -> mask = mask or RTSP_CAPABILITY_DESCRIBE
                        "announce" -> mask = mask or RTSP_CAPABILITY_ANNOUNCE
                        "setup" -> mask = mask or RTSP_CAPABILITY_SETUP
                        "play" -> mask = mask or RTSP_CAPABILITY_PLAY
                        "record" -> mask = mask or RTSP_CAPABILITY_RECORD
                        "pause" -> mask = mask or RTSP_CAPABILITY_PAUSE
                        "teardown" -> mask = mask or RTSP_CAPABILITY_TEARDOWN
                        "set_parameter" -> mask = mask or RTSP_CAPABILITY_SET_PARAMETER
                        "get_parameter" -> mask = mask or RTSP_CAPABILITY_GET_PARAMETER
                        "redirect" -> mask = mask or RTSP_CAPABILITY_REDIRECT
                    }
                }
                return mask
            }
        }
        return RTSP_CAPABILITY_NONE
    }

    internal fun getHeaderWwwAuthenticateDigestRealmAndNonce(headers: ArrayList<Pair<String, String>>): Pair<String, String>? {
        for (head in headers) {
            val h = head.first.lowercase(Locale.getDefault())
            if ("www-authenticate" == h && head.second.lowercase(Locale.getDefault()).startsWith("digest")) {
                val v = head.second.substring(7).trim { it <= ' ' }
                var begin: Int = v.indexOf("realm=")
                begin = v.indexOf('"', begin) + 1
                var end: Int = v.indexOf('"', begin)
                val digestRealm = v.substring(begin, end)
                begin = v.indexOf("nonce=")
                begin = v.indexOf('"', begin) + 1
                end = v.indexOf('"', begin)
                val digestNonce = v.substring(begin, end)
                return Pair.create(digestRealm, digestNonce)
            }
        }
        return null
    }

    internal fun getHeaderWwwAuthenticateBasicRealm(headers: ArrayList<Pair<String, String>>): String? {
        for (head in headers) {
            val h = head.first.lowercase(Locale.getDefault())
            var v = head.second.lowercase(Locale.getDefault())
            if ("www-authenticate" == h && v.startsWith("basic")) {
                v = v.substring(6).trim { it <= ' ' }
                val tokens = TextUtils.split(v, "\"")
                if (tokens.size > 2) return tokens[1]
            }
        }
        return null
    }

    internal fun getBasicAuthHeader(username: String?, password: String?): String {
        val auth = (username ?: "") + ":" + (password ?: "")
        return "Basic " + String(Base64.encode(auth.toByteArray(StandardCharsets.ISO_8859_1), Base64.NO_WRAP))
    }

    // Digest authentication
    internal fun getDigestAuthHeader(
        username: String?,
        password: String?,
        method: String,
        digestUri: String,
        realm: String,
        nonce: String
    ): String? {
        var clientUsername = username
        var clientPassword = password
        try {
            val md = MessageDigest.getInstance("MD5")
            if (clientUsername == null) clientUsername = ""
            if (clientPassword == null) clientPassword = ""

            // calc A1 digest
            md.update(clientUsername.toByteArray(StandardCharsets.ISO_8859_1))
            md.update(':'.code.toByte())
            md.update(realm.toByteArray(StandardCharsets.ISO_8859_1))
            md.update(':'.code.toByte())
            md.update(clientPassword.toByteArray(StandardCharsets.ISO_8859_1))
            val ha1: ByteArray = md.digest()

            // calc A2 digest
            md.reset()
            md.update(method.toByteArray(StandardCharsets.ISO_8859_1))
            md.update(':'.code.toByte())
            md.update(digestUri.toByteArray(StandardCharsets.ISO_8859_1))
            val ha2 = md.digest()

            // calc response
            md.update(getHexStringFromBytes(ha1).toByteArray(StandardCharsets.ISO_8859_1))
            md.update(':'.code.toByte())
            md.update(nonce.toByteArray(StandardCharsets.ISO_8859_1))
            md.update(':'.code.toByte())
            // TODO add support for more secure version of digest auth
            //md.update(nc.getBytes(StandardCharsets.ISO_8859_1))
            //md.update((byte) ':')
            //md.update(cnonce.getBytes(StandardCharsets.ISO_8859_1))
            //md.update((byte) ':')
            //md.update(qop.getBytes(StandardCharsets.ISO_8859_1))
            //md.update((byte) ':')
            md.update(getHexStringFromBytes(ha2).toByteArray(StandardCharsets.ISO_8859_1))
            val response = getHexStringFromBytes(md.digest())
            return "Digest username=\"$clientUsername\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$digestUri\", response=\"$response\""
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getHexStringFromBytes(bytes: ByteArray): String {
        val buf = StringBuilder()
        for (b in bytes) buf.append(String.format("%02x", b))
        return buf.toString()
    }

    @Throws(IOException::class)
    internal fun readContentAsText(inputStream: InputStream, length: Int): String {
        if (length <= 0) return ""
        val b = ByteArray(length)
        val read = readData(inputStream, b, 0, length)
        return String(b, 0, read)
    }

    // int memcmp ( const void * ptr1, const void * ptr2, size_t num )
    fun memcmp(
        source1: ByteArray,
        offsetSource1: Int,
        source2: ByteArray,
        offsetSource2: Int,
        num: Int
    ): Boolean {
        if (source1.size - offsetSource1 < num) return false
        if (source2.size - offsetSource2 < num) return false
        for (i in 0 until num) {
            if (source1[offsetSource1 + i] != source2[offsetSource2 + i]) return false
        }
        return true
    }

    internal fun shiftLeftArray(array: ByteArray, num: Int) {
        // ABCDEF -> BCDEF
        if (num - 1 >= 0) System.arraycopy(array, 1, array, 0, num - 1)
    }

    @Throws(IOException::class)
    internal fun readData(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        Log.v(TAG, "readData(offset=$offset, length=$length)")
        var readBytes: Int
        var totalReadBytes = 0
        do {
            readBytes = inputStream.read(buffer, offset + totalReadBytes, length - totalReadBytes)
            if (readBytes > 0) totalReadBytes += readBytes
        } while (readBytes >= 0 && totalReadBytes < length)
        return totalReadBytes
    }

    internal fun dumpHeaders(headers: ArrayList<Pair<String, String>>) {
        for (head in headers) {
            Log.d(TAG, head.first + ": " + head.second)
        }
    }

    internal fun getHeader(headers: ArrayList<Pair<String, String>>, header: String): String? {
        for (head in headers) {
            val h = head.first.lowercase(Locale.getDefault())
            if (header.lowercase(Locale.getDefault()) == h) {
                return head.second
            }
        }
        return null
    }

}