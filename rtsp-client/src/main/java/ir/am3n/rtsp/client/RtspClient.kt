package ir.am3n.rtsp.client

import android.text.TextUtils
import android.util.Log
import android.util.Pair
import ir.am3n.rtsp.client.RtspClientUtils.AUDIO_CODEC_AAC
import ir.am3n.rtsp.client.RtspClientUtils.CRLF
import ir.am3n.rtsp.client.RtspClientUtils.MAX_LINE_SIZE
import ir.am3n.rtsp.client.RtspClientUtils.RTSP_CAPABILITY_GET_PARAMETER
import ir.am3n.rtsp.client.RtspClientUtils.RTSP_CAPABILITY_TEARDOWN
import ir.am3n.rtsp.client.RtspClientUtils.checkExitFlag
import ir.am3n.rtsp.client.RtspClientUtils.checkStatusCode
import ir.am3n.rtsp.client.RtspClientUtils.dumpHeaders
import ir.am3n.rtsp.client.RtspClientUtils.getBasicAuthHeader
import ir.am3n.rtsp.client.RtspClientUtils.getDescribeParams
import ir.am3n.rtsp.client.RtspClientUtils.getDigestAuthHeader
import ir.am3n.rtsp.client.RtspClientUtils.getHeader
import ir.am3n.rtsp.client.RtspClientUtils.getHeaderContentLength
import ir.am3n.rtsp.client.RtspClientUtils.getHeaderWwwAuthenticateBasicRealm
import ir.am3n.rtsp.client.RtspClientUtils.getHeaderWwwAuthenticateDigestRealmAndNonce
import ir.am3n.rtsp.client.RtspClientUtils.getSdpInfoFromDescribeParams
import ir.am3n.rtsp.client.RtspClientUtils.getSupportedCapabilities
import ir.am3n.rtsp.client.RtspClientUtils.getUriForSetup
import ir.am3n.rtsp.client.RtspClientUtils.hasCapability
import ir.am3n.rtsp.client.RtspClientUtils.memcmp
import ir.am3n.rtsp.client.RtspClientUtils.readContentAsText
import ir.am3n.rtsp.client.RtspClientUtils.readRtpData
import ir.am3n.rtsp.client.RtspClientUtils.sendDescribeCommand
import ir.am3n.rtsp.client.RtspClientUtils.sendGetParameterCommand
import ir.am3n.rtsp.client.RtspClientUtils.sendOptionsCommand
import ir.am3n.rtsp.client.RtspClientUtils.sendPlayCommand
import ir.am3n.rtsp.client.RtspClientUtils.sendSetupCommand
import ir.am3n.rtsp.client.RtspClientUtils.sendTeardownCommand
import ir.am3n.rtsp.client.RtspClientUtils.shiftLeftArray
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.data.Track
import ir.am3n.rtsp.client.exceptions.NoResponseHeadersException
import ir.am3n.rtsp.client.exceptions.UnauthorizedException
import ir.am3n.rtsp.client.interfaces.RtspClientKeepAliveListener
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import ir.am3n.utils.NetUtils
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// https://www.ietf.org/rfc/rfc2326.txt
internal class RtspClient private constructor(builder: Builder) {

    companion object {
        const val TAG = "RtspClient"
    }

    class Builder(
        val rtspSocket: Socket,
        val uriRtsp: String,
        val exitFlag: AtomicBoolean,
        val listener: RtspClientListener
    ) {

        companion object {
            private const val DEFAULT_USER_AGENT = "Lavf58.29.100"
        }

        var username: String? = null
        var password: String? = null
        var userAgent: String? = DEFAULT_USER_AGENT
        var requestVideo: Boolean = true
        var requestAudio: Boolean = false

        fun withCredentials(username: String?, password: String?): Builder {
            this.username = username
            this.password = password
            return this
        }

        fun withUserAgent(userAgent: String?): Builder {
            this.userAgent = userAgent
            return this
        }

        fun requestVideo(requestVideo: Boolean): Builder {
            this.requestVideo = requestVideo
            return this
        }

        fun requestAudio(requestAudio: Boolean): Builder {
            this.requestAudio = requestAudio
            return this
        }

        fun build(): RtspClient {
            return RtspClient(this)
        }

    }

    private val rtspSocket: Socket
    private val uriRtsp: String
    private val exitFlag: AtomicBoolean
    private val listener: RtspClientListener
    private val username: String?
    private val password: String?
    private val userAgent: String?
    private val requestVideo: Boolean
    private val requestAudio: Boolean

    init {
        rtspSocket = builder.rtspSocket
        uriRtsp = builder.uriRtsp
        exitFlag = builder.exitFlag
        listener = builder.listener
        username = builder.username
        password = builder.password
        userAgent = builder.userAgent
        requestVideo = builder.requestVideo
        requestAudio = builder.requestAudio
    }

    fun execute() {
        Log.v(TAG, "execute()")
        try {

            val inputStream = rtspSocket.getInputStream()
            val outputStream: OutputStream = LoggerOutputStream(rtspSocket.getOutputStream())
            var sdpInfo = SdpInfo()
            val cSeq = AtomicInteger(0)
            var headers: ArrayList<Pair<String, String>>
            var status: Int
            var authToken: String? = null
            var digestRealmNonce: Pair<String, String>? = null
            checkExitFlag(exitFlag)
            sendOptionsCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, null)
            status = readResponseStatusCode(inputStream)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)

            // Try once again with credentials
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers)
                authToken = if (digestRealmNonce == null) {
                    val basicRealm = getHeaderWwwAuthenticateBasicRealm(headers)
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw IOException("Unknown authentication type")
                    }
                    // Basic auth
                    getBasicAuthHeader(username, password)
                } else {
                    // Digest auth
                    getDigestAuthHeader(username, password, "OPTIONS", uriRtsp, digestRealmNonce.first, digestRealmNonce.second)
                }
                checkExitFlag(exitFlag)
                sendOptionsCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
                status = readResponseStatusCode(inputStream)
                headers = readResponseHeaders(inputStream)
                dumpHeaders(headers)
            }
            Log.i(TAG, "OPTIONS status: $status")
            checkStatusCode(status)

            val capabilities = getSupportedCapabilities(headers)
            checkExitFlag(exitFlag)
            sendDescribeCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
            status = readResponseStatusCode(inputStream)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)

            // Try once again with credentials. OPTIONS command can be accepted without authentication.
            if (status == 401) {
                digestRealmNonce = getHeaderWwwAuthenticateDigestRealmAndNonce(headers)
                authToken = if (digestRealmNonce == null) {
                    val basicRealm = getHeaderWwwAuthenticateBasicRealm(headers)
                    if (TextUtils.isEmpty(basicRealm)) {
                        throw IOException("Unknown authentication type")
                    }
                    // Basic auth
                    getBasicAuthHeader(username, password)
                } else {
                    // Digest auth
                    getDigestAuthHeader(username, password, "DESCRIBE", uriRtsp, digestRealmNonce.first, digestRealmNonce.second)
                }
                checkExitFlag(exitFlag)
                sendDescribeCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken)
                status = readResponseStatusCode(inputStream)
                headers = readResponseHeaders(inputStream)
                dumpHeaders(headers)
            }
            Log.i(TAG, "DESCRIBE status: $status")
            checkStatusCode(status)

            val contentLength = getHeaderContentLength(headers)
            if (contentLength > 0) {
                val content = readContentAsText(inputStream, contentLength)
                Log.i(TAG, "" + content)
                try {
                    val params = getDescribeParams(content)
                    sdpInfo = getSdpInfoFromDescribeParams(params)
                    if (!requestVideo)
                        sdpInfo.videoTrack = null
                    if (!requestAudio)
                        sdpInfo.audioTrack = null
                    // Only AAC supported
                    if (sdpInfo.audioTrack != null && sdpInfo.audioTrack?.audioCodec != AUDIO_CODEC_AAC) {
                        Log.e(TAG, "Unknown RTSP audio codec (" + sdpInfo.audioTrack?.audioCodec + ") specified in SDP")
                        sdpInfo.audioTrack = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var session: String? = null
            var sessionTimeout = 0

            for (i in 0..1) {

                checkExitFlag(exitFlag)
                val track: Track? =
                    if (i == 0 && requestVideo) sdpInfo.videoTrack
                    else if (i == 1 && requestAudio) sdpInfo.audioTrack
                    else null

                if (track != null) {
                    val uriRtspSetup = getUriForSetup(uriRtsp, track)
                        ?: throw Exception("Failed to get RTSP URI for SETUP")
                    if (digestRealmNonce != null) {
                        authToken = getDigestAuthHeader(username, password, method = "SETUP",
                            uriRtspSetup, digestRealmNonce.first, digestRealmNonce.second)
                    }
                    sendSetupCommand(outputStream, uriRtspSetup, cSeq.addAndGet(1), userAgent,
                        authToken, session, interleaved = if (i == 0) "0-1" /*video*/ else "2-3" /*audio*/)
                    status = readResponseStatusCode(inputStream)
                    Log.i(TAG, "SETUP status: $status")
                    checkStatusCode(status)
                    headers = readResponseHeaders(inputStream)
                    dumpHeaders(headers)
                    session = getHeader(headers, "Session")
                    if (!TextUtils.isEmpty(session)) {
                        var params = TextUtils.split(session, ";")
                        session = params[0]
                        // Getting session timeout
                        if (params.size > 1) {
                            params = TextUtils.split(params[1], "=")
                            if (params.size > 1) {
                                try {
                                    sessionTimeout = params[1].toInt()
                                } catch (e: NumberFormatException) {
                                    Log.e(TAG, "Failed to parse RTSP session timeout")
                                }
                            }
                        }
                    }
                    Log.d(TAG, "SETUP session: $session, timeout: $sessionTimeout")
                    if (TextUtils.isEmpty(session))
                        throw IOException("Failed to get RTSP session")
                }

            }


            if (TextUtils.isEmpty(session))
                throw IOException("Failed to get any media track")
            checkExitFlag(exitFlag)
            if (digestRealmNonce != null)
                authToken = getDigestAuthHeader(username, password, "PLAY", uriRtsp, digestRealmNonce.first, digestRealmNonce.second)
            sendPlayCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken, session!!)
            status = readResponseStatusCode(inputStream)
            Log.i(TAG, "PLAY status: $status")
            checkStatusCode(status)
            headers = readResponseHeaders(inputStream)
            dumpHeaders(headers)
            listener.onRtspConnected(sdpInfo)

            if (sdpInfo.videoTrack != null || sdpInfo.audioTrack != null) {
                if (digestRealmNonce != null) {
                    authToken = getDigestAuthHeader(
                        username, password,
                        if (hasCapability(RTSP_CAPABILITY_GET_PARAMETER, capabilities)) "GET_PARAMETER" else "OPTIONS",
                        uriRtsp, digestRealmNonce.first, digestRealmNonce.second
                    )
                }
                val authTokenFinal = authToken
                val sessionFinal = session
                val keepAliveListener = object : RtspClientKeepAliveListener {
                    override fun onRtspKeepAliveRequested() {
                        try {
                            Log.d(TAG, "Sending keep-alive")
                            if (hasCapability(RTSP_CAPABILITY_GET_PARAMETER, capabilities))
                                sendGetParameterCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, sessionFinal, authTokenFinal
                            ) else {
                                sendOptionsCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authTokenFinal)
                            }
                            // Do not read response right now, since it may contain unread RTP frames.
                            // RtpHeader.searchForNextRtpHeader will handle that.
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }

                // Blocking call unless exitFlag set to true, thread.interrupt() called or connection closed.
                try {
                    readRtpData(inputStream, sdpInfo, exitFlag, listener, keepAliveTimeout = sessionTimeout / 2 * 1000, keepAliveListener)
                } catch (t: Throwable) {
                    t.printStackTrace()
                } finally {
                    // Cleanup resources on server side
                    if (hasCapability(RTSP_CAPABILITY_TEARDOWN, capabilities)) {
                        if (digestRealmNonce != null)
                            authToken = getDigestAuthHeader(username, password, "TEARDOWN", uriRtsp, digestRealmNonce.first, digestRealmNonce.second)
                        sendTeardownCommand(outputStream, uriRtsp, cSeq.addAndGet(1), userAgent, authToken, sessionFinal)
                    }
                }

            } else {
                listener.onRtspFailed("No tracks found. RTSP server issue.")
            }

            listener.onRtspDisconnected()

        } catch (e: UnauthorizedException) {
            e.printStackTrace()
            listener.onRtspFailedUnauthorized()
        } catch (e: InterruptedException) {
            // Thread interrupted. Expected behavior.
            listener.onRtspDisconnected()
        } catch (e: Exception) {
            e.printStackTrace()
            listener.onRtspFailed(e.message)
        }
        try {
            rtspSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun readResponseStatusCode(inputStream: InputStream): Int {
        var line: String? = ""
        val rtspHeader = "RTSP/1.0 ".toByteArray()
        // Search fpr "RTSP/1.0 "
        while (!exitFlag.get() && readUntilBytesFound(inputStream, rtspHeader) && readLine(inputStream).also { line = it } != null) {
            Log.d(TAG, "" + line)
            val indexCode = line!!.indexOf(' ')
            val code = line!!.substring(0, indexCode)
            try {
                val statusCode = code.toInt()
                Log.d(TAG, "Status code: $statusCode")
                return statusCode
            } catch (e: NumberFormatException) {
                // Does not fulfill standard "RTSP/1.1 200 OK" token
                // Continue search for
            }
        }
        Log.w(TAG, "Could not obtain status code")
        return -1
    }

    @Throws(IOException::class)
    private fun readResponseHeaders(inputStream: InputStream): ArrayList<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        var line = ""
        while (!exitFlag.get() && !TextUtils.isEmpty(readLine(inputStream).also { line = it ?: "" })) {
            Log.d(TAG, "" + line)
            if (CRLF == line) {
                return headers
            } else {
                val pairs = TextUtils.split(line, ":")
                if (pairs.size == 2) {
                    headers.add(Pair.create(pairs[0].trim { it <= ' ' }, pairs[1].trim { it <= ' ' }))
                }
            }
        }
        return headers
    }

    @Throws(IOException::class)
    private fun readUntilBytesFound(inputStream: InputStream, array: ByteArray): Boolean {
        val buffer = ByteArray(array.size)

        // Fill in buffer
        if (NetUtils.readData(inputStream, buffer, 0, buffer.size) != buffer.size) return false // EOF
        while (!exitFlag.get()) {
            // Check if buffer is the same one
            if (memcmp(buffer, 0, array, 0, buffer.size)) {
                return true
            }
            // ABCDEF -> BCDEFF
            shiftLeftArray(buffer, buffer.size)
            // Read 1 byte into last buffer item
            if (NetUtils.readData(inputStream, buffer, buffer.size - 1, 1) != 1) {
                return false // EOF
            }
        }
        return false
    }

    @Throws(IOException::class)
    private fun readLine(inputStream: InputStream): String? {
        val bufferLine = ByteArray(MAX_LINE_SIZE)
        var offset = 0
        var readBytes: Int
        do {
            // Didn't find "\r\n" within 4K bytes
            if (offset >= MAX_LINE_SIZE) {
                throw NoResponseHeadersException()
            }
            // Read 1 byte
            readBytes = inputStream.read(bufferLine, offset, 1)
            if (readBytes == 1) {
                // Check for EOL
                // Some cameras like Linksys WVC200 do not send \n instead of \r\n
                if (offset > 0 && bufferLine[offset] == '\n'.code.toByte()) {
                    // Found empty EOL. End of header section
                    return if (offset == 1) "" else String(bufferLine, 0, offset - 1) //break;

                    // Found EOL. Add to array.
                } else {
                    offset++
                }
            }
        } while (readBytes > 0 && !exitFlag.get())
        return null
    }

}