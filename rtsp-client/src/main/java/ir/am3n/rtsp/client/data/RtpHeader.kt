package ir.am3n.rtsp.client.data

import android.util.Log
import ir.am3n.utils.NetUtils.readData
import kotlin.Throws
import ir.am3n.rtsp.client.parser.RtpParser
import java.io.IOException
import java.io.InputStream

internal class RtpHeader {

    companion object {

        private const val TAG = "RtpHeader"
        private const val DEBUG = false

        // If RTP header found, return 4 bytes of the header
        @Throws(IOException::class)
        fun searchForNextRtpHeader(inputStream: InputStream, header: ByteArray): Boolean {
            if (header.size < 4) throw IOException("Invalid allocated buffer size")
            var bytesRemaining = 100000 // 100 KB max to check
            var foundFirstByte = false
            var foundSecondByte = false
            val oneByte = ByteArray(1)
            // Search for {0x24, 0x00}
            do {
                if (bytesRemaining-- < 0) return false
                // Read 1 byte
                readData(inputStream, oneByte, 0, 1)
                if (foundFirstByte) {
                    // Found 0x24. Checking for 0x00-0x02.
                    if (oneByte[0].toInt() == 0x00) foundSecondByte = true else foundFirstByte = false
                }
                if (!foundFirstByte && oneByte[0].toInt() == 0x24) {
                    // Found 0x24
                    foundFirstByte = true
                }
            } while (!foundSecondByte)
            header[0] = 0x24
            header[1] = oneByte[0]
            // Read 2 bytes more (packet size)
            readData(inputStream, header, 2, 2)
            return true
        }

        fun parseData(header: ByteArray, packetSize: Int): RtpHeader? {
            val rtpHeader = RtpHeader()
            rtpHeader.version = header[0].toInt() and 0xFF shr 6
            if (rtpHeader.version != 2) {
                if (DEBUG) Log.e(TAG, "Not a RTP packet (" + rtpHeader.version + ")")
                return null
            }

            // 80 60 40 91 fd ab d4 2a
            // 80 c8 00 06
            rtpHeader.padding = header[0].toInt() and 0x20 shr 5 // 0b00100100
            rtpHeader.extension = header[0].toInt() and 0x10 shr 4
            rtpHeader.marker = header[1].toInt() and 0x80 shr 7
            rtpHeader.payloadType = header[1].toInt() and 0x7F
            rtpHeader.sequenceNumber = (header[3].toInt() and 0xFF) + (header[2].toInt() and 0xFF shl 8)
            rtpHeader.timeStamp =
                (header[7].toInt() and 0xFF) + (header[6].toInt() and 0xFF shl 8) + (header[5].toInt() and 0xFF shl 16) + ((header[4].toInt() and 0xFF).toLong() shl 24) and 0xffffffffL
            rtpHeader.ssrc =
                (header[7].toInt() and 0xFF) + (header[6].toInt() and 0xFF shl 8) + (header[5].toInt() and 0xFF shl 16) + ((header[4].toInt() and 0xFF).toLong() shl 24) and 0xffffffffL
            rtpHeader.payloadSize = packetSize - RtpParser.RTP_HEADER_SIZE
            return rtpHeader
        }

        fun getPacketSize(header: ByteArray): Int {
            val packetSize: Int = header[2].toInt() and 0xFF shl 8 or (header[3].toInt() and 0xFF)
            if (DEBUG) Log.d(TAG, "Packet size: $packetSize")
            return packetSize
        }

    }

    var version = 0
    var padding = 0
    var extension = 0
    var cc = 0
    var marker = 0
    var payloadType = 0
    var sequenceNumber = 0
    var timeStamp: Long = 0
    var ssrc: Long = 0
    var payloadSize = 0

    fun dumpHeader() {
        Log.d(
            "RTP", "RTP header version: " + version
                    + ", padding: " + padding
                    + ", ext: " + extension
                    + ", cc: " + cc
                    + ", marker: " + marker
                    + ", payload type: " + payloadType
                    + ", seq num: " + sequenceNumber
                    + ", ts: " + timeStamp
                    + ", ssrc: " + ssrc
                    + ", payload size: " + payloadSize
        )
    }

}