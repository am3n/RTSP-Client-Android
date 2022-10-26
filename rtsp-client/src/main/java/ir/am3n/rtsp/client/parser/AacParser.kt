package ir.am3n.rtsp.client.parser

import android.util.Log
import com.google.android.exoplayer2.util.ParsableBitArray
import com.google.android.exoplayer2.util.ParsableByteArray

// https://tools.ietf.org/html/rfc3640
class AacParser(aacMode: String) {

    companion object {

        private const val TAG = "AacParser"
        private const val DEBUG = false
        private const val MODE_LBR = 0
        private const val MODE_HBR = 1

        // Number of bits for AAC AU sizes, indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_SIZES = intArrayOf(6, 13)

        // Number of bits for AAC AU index(-delta), indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_INDEX = intArrayOf(2, 3)

        // Frame Sizes for AAC AU fragments, indexed by mode (LBR and HBR)
        private val FRAME_SIZES = intArrayOf(63, 8191)

    }

    private val headerScratchBits: ParsableBitArray
    private val headerScratchBytes: ParsableByteArray
    private val _aacMode: Int
    private val completeFrameIndicator = true

    init {
        _aacMode = if (aacMode.equals("AAC-lbr", ignoreCase = true)) MODE_LBR else MODE_HBR
        headerScratchBits = ParsableBitArray()
        headerScratchBytes = ParsableByteArray()
    }

    fun processRtpPacketAndGetSample(data: ByteArray, length: Int): ByteArray {
        if (DEBUG) Log.v(TAG, "processRtpPacketAndGetSample(length=$length)")
        var auHeadersCount = 1
        val numBitsAuSize = NUM_BITS_AU_SIZES[_aacMode]
        val numBitsAuIndex = NUM_BITS_AU_INDEX[_aacMode]
        val packet = ParsableByteArray(data, length)
        val auHeadersLength = packet.readShort().toInt()
        val auHeadersLengthBytes = (auHeadersLength + 7) / 8
        headerScratchBytes.reset(auHeadersLengthBytes)
        packet.readBytes(headerScratchBytes.data, 0, auHeadersLengthBytes)
        headerScratchBits.reset(headerScratchBytes.data)
        val bitsAvailable = auHeadersLength - (numBitsAuSize + numBitsAuIndex)
        if (bitsAvailable > 0) {
            auHeadersCount += bitsAvailable / (numBitsAuSize + numBitsAuIndex)
        }
        if (auHeadersCount == 1) {
            val auSize = headerScratchBits.readBits(numBitsAuSize)
            val auIndex = headerScratchBits.readBits(numBitsAuIndex)
            if (completeFrameIndicator) {
                if (auIndex == 0) {
                    if (packet.bytesLeft() == auSize) {
                        return handleSingleAacFrame(packet)
                    }
                }
            }
        }
        return ByteArray(0)
    }

    private fun handleSingleAacFrame(packet: ParsableByteArray): ByteArray {
        val length = packet.bytesLeft()
        val data = ByteArray(length)
        System.arraycopy(packet.data, packet.position, data, 0, data.size)
        return data
    }

}