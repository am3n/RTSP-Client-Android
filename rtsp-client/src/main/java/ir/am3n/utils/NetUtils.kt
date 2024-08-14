package ir.am3n.utils

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetUtils {

    private const val TAG = "NetUtils"
    private const val MAX_LINE_SIZE = 4098

    @Throws(Exception::class)
    fun createSocketAndConnect(dstUri: Uri, dstPort: Int, timeout: Long): Socket {
        if (Rtsp.DEBUG) Log.v(TAG, "createSocketAndConnect(dstUri=$dstUri, dstPort=$dstPort, timeout=$timeout)")
        return if (dstUri.scheme!!.lowercase().equals("rtsps", ignoreCase = true))
            createSslSocketAndConnect(dstUri.host!!, dstPort, timeout)
        else
            createSocketAndConnect(dstUri.host!!, dstPort, timeout)
    }

    @Throws(Exception::class)
    fun createSslSocketAndConnect(dstName: String, dstPort: Int, timeout: Long): SSLSocket {
        if (Rtsp.DEBUG) Log.v(TAG, "createSslSocketAndConnect(dstName=$dstName, dstPort=$dstPort, timeout=$timeout)")

//        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
//        trustManagerFactory.init((KeyStore) null);
//        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
//        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
//           throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
//        }
//        X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(FakeX509TrustManager()), null)
        val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
        sslSocket.connect(InetSocketAddress(dstName, dstPort), timeout.toInt())
        sslSocket.setSoLinger(false, 1)
        sslSocket.soTimeout = timeout.toInt()
        return sslSocket
    }

    @Throws(IOException::class)
    fun createSocketAndConnect(dstName: String, dstPort: Int, timeout: Long): Socket {
        if (Rtsp.DEBUG) Log.v(TAG, "createSocketAndConnect(dstName=$dstName, dstPort=$dstPort, timeout=$timeout)")
        val socket = Socket()
        socket.connect(InetSocketAddress(dstName, dstPort), timeout.toInt())
        socket.setSoLinger(false, 1)
        socket.soTimeout = timeout.toInt()
        return socket
    }

    @Throws(IOException::class)
    fun createSocket(timeout: Long): Socket {
        val socket = Socket()
        socket.setSoLinger(false, 1) // 1 sec for flush() before close()
        socket.soTimeout = timeout.toInt() // 10 sec timeout for read(), not for write()
        return socket
    }

    @Throws(IOException::class)
    fun closeSocket(socket: Socket?) {
        if (Rtsp.DEBUG) Log.v(TAG, "closeSocket()")
        if (socket != null) {
            try {
                socket.shutdownInput()
            } catch (ignored: Exception) {
            }
            try {
                socket.shutdownOutput()
            } catch (ignored: Exception) {
            }
            socket.close()
        }
    }

    @Throws(IOException::class)
    fun readResponseHeaders(inputStream: InputStream): ArrayList<String> {
        val headers = ArrayList<String>()
        var line: String?
        while (true) {
            line = readLine(inputStream)
            if (line != null) {
                if (line == "\r\n") return headers else headers.add(line)
            } else {
                break
            }
        }
        return headers
    }

    @Throws(IOException::class)
    fun readLine(inputStream: InputStream): String? {
        val bufferLine = ByteArray(MAX_LINE_SIZE)
        var offset = 0
        var readBytes: Int
        do {
            // Didn't find "\r\n" within 4K bytes
            if (offset >= MAX_LINE_SIZE) {
                throw IOException("Invalid headers")
            }

            // Read 1 byte
            readBytes = inputStream.read(bufferLine, offset, 1)
            if (readBytes == 1) {
                // Check for EOL
                // Some cameras like Linksys WVC200 do not send \n instead of \r\n
                if (offset > 0 && bufferLine[offset] == '\n'.code.toByte()) {
                    // Found empty EOL. End of header section
                    if (offset == 1) break

                    // Found EOL. Add to array.
                    return String(bufferLine, 0, offset - 1)
                } else {
                    offset++
                }
            }
        } while (readBytes > 0)
        return null
    }

    fun getResponseStatusCode(headers: ArrayList<String>): Int {
        // Search for HTTP status code header
        for (header in headers) {
            var indexHttp = header.indexOf("HTTP/1.1 ") // 9 characters
            if (indexHttp == -1) indexHttp = header.indexOf("HTTP/1.0 ")
            if (indexHttp >= 0) {
                val indexCode = header.indexOf(' ', 9)
                val code = header.substring(9, indexCode)
                try {
                    return code.toInt()
                } catch (e: NumberFormatException) {
                    // Does not fulfill standard "HTTP/1.1 200 Ok" token
                    // Continue search for
                }
            }
        }
        // Not found
        return -1
    }

    @Throws(IOException::class)
    fun readContentAsText(inputStream: InputStream?): String? {
        if (inputStream == null) return null
        val r = BufferedReader(InputStreamReader(inputStream))
        val total = StringBuilder()
        var line: String?
        while (r.readLine().also { line = it } != null) {
            total.append(line)
            total.append("\r\n")
        }
        return total.toString()
    }

    @Throws(IOException::class)
    fun readContentAsText(inputStream: InputStream, length: Int): String {
        if (length <= 0) return ""
        val b = ByteArray(length)
        val read = readData(inputStream, b, 0, length)
        return String(b, 0, read)
    }

    @Throws(IOException::class)
    fun readData(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var readBytes: Int
        var totalReadBytes = 0
        do {
            readBytes = inputStream.read(buffer, offset + totalReadBytes, length - totalReadBytes)
            if (readBytes > 0) totalReadBytes += readBytes
        } while (readBytes >= 0 && totalReadBytes < length)
        return totalReadBytes
    }

    /**
     * Constructor for FakeX509TrustManager.
     */
    @SuppressLint("CustomX509TrustManager")
    class FakeX509TrustManager : X509TrustManager {

        companion object {
            /**
             * Accepted issuers for fake trust manager
             */
            private val mAcceptedIssuers = arrayOf<X509Certificate>()
        }

        /**
         * @see javax.net.ssl.X509TrustManager.checkClientTrusted
         */
        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkClientTrusted(certificates: Array<X509Certificate>, authType: String) {
        }

        /**
         * @see javax.net.ssl.X509TrustManager.checkServerTrusted
         */
        @SuppressLint("TrustAllX509TrustManager")
        @Throws(CertificateException::class)
        override fun checkServerTrusted(certificates: Array<X509Certificate>, authType: String) {
        }

        // https://github.com/square/okhttp/issues/4669
        // Called by Android via reflection in X509TrustManagerExtensions.
        @Throws(CertificateException::class)
        fun checkServerTrusted(chain: Array<X509Certificate?>, authType: String?, host: String?): List<X509Certificate?> {
            return listOf(*chain)
        }

        /**
         * @see javax.net.ssl.X509TrustManager.getAcceptedIssuers
         */
        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return mAcceptedIssuers
        }

    }

}