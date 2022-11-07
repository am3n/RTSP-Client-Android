package ir.am3n.rtsp.client.parser

import android.util.Log
import ir.am3n.utils.NetUtils.readData
import kotlin.Throws
import ir.am3n.rtsp.client.data.RtpHeader
import java.io.IOException
import java.io.InputStream

internal object RtpParser {

    private const val TAG = "RtpParser"
    private const val DEBUG = false

    const val RTP_HEADER_SIZE = 12

    @Throws(IOException::class)
    fun readHeader(inputStream: InputStream): RtpHeader? {
        // 24 01 00 1c 80 c8 00 06  7f 1d d2 c4
        // 24 01 00 1c 80 c8 00 06  13 9b cf 60
        // 24 02 01 12 80 e1 01 d2  00 07 43 f0
        val header = ByteArray(RTP_HEADER_SIZE)
        // Skip 4 bytes (TCP only). No those bytes in UDP.
        readData(inputStream, header, 0, 4)
        if (DEBUG) Log.d(TAG, if (header[1].toInt() == 0) "RTP packet" else "RTCP packet")
        var packetSize = RtpHeader.getPacketSize(header)
        if (DEBUG) Log.d(TAG, "Packet size: $packetSize")
        if (readData(inputStream, header, 0, header.size) == header.size) {
            val rtpHeader = RtpHeader.parseData(header, packetSize)
            if (rtpHeader == null) {
                // Header not found. Possible keep-alive response. Search for another RTP header.
                val foundHeader = RtpHeader.searchForNextRtpHeader(inputStream, header)
                if (foundHeader) {
                    packetSize = RtpHeader.getPacketSize(header)
                    if (readData(inputStream, header, 0, header.size) == header.size)
                        return RtpHeader.parseData(header, packetSize)
                }
            } else {
                return rtpHeader
            }
        }
        return null
    }

}