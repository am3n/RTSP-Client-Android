package ir.am3n.rtsp.client.parser

import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.utils.VideoCodecUtils
import ir.am3n.utils.VideoCodecUtils.getH264NalUnitTypeString

class VideoRtpParser {

    companion object {
        private const val TAG: String = "VideoRtpParser"
    }

    // TODO Use already allocated buffer with RtpPacket.MAX_SIZE = 65507
    // Used only for NAL_FU_A fragmented packets
    private val _fragmentedBuffer = arrayOfNulls<ByteArray>(1024)
    private var _fragmentedBufferLength = 0
    private var _fragmentedPackets = 0

    fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int): ByteArray? {
        if (Rtsp.DEBUG) Log.v(TAG, "processRtpPacketAndGetNalUnit(length=$length)")

        var tmpLen: Int
        val nalType = (data[0].toInt() and 0x1F).toByte()
        val packFlag = data[1].toInt() and 0xC0
        var nalUnit: ByteArray? = null

        if (Rtsp.DEBUG) Log.d(TAG, "NAL type: " + getH264NalUnitTypeString(nalType) + ", pack flag: " + packFlag)
        when (nalType) {
            VideoCodecUtils.NAL_STAP_A, VideoCodecUtils.NAL_STAP_B -> {}
            VideoCodecUtils.NAL_MTAP16, VideoCodecUtils.NAL_MTAP24 -> {}
            VideoCodecUtils.NAL_FU_A -> when (packFlag) {
                0x80 -> {
                    _fragmentedPackets = 0
                    _fragmentedBufferLength = length - 1
                    _fragmentedBuffer[0] = ByteArray(_fragmentedBufferLength)
                    _fragmentedBuffer[0]!![0] = ((data[0].toInt() and 0xE0) or (data[1].toInt() and 0x1F)).toByte()
                    System.arraycopy(data, 2, _fragmentedBuffer[0]!!, 1, length - 2)
                }

                0x00 -> {
                    _fragmentedPackets++
                    if (_fragmentedPackets >= _fragmentedBuffer.size) {
                        Log.e(TAG, "Too many middle packets. No NAL FU_A end packet received. Skipped RTP packet.")
                        _fragmentedBuffer[0] = null
                    } else {
                        _fragmentedBufferLength += length - 2
                        _fragmentedBuffer[_fragmentedPackets] = ByteArray(length - 2)
                        System.arraycopy(data, 2, _fragmentedBuffer[_fragmentedPackets]!!, 0, length - 2)
                    }
                }

                0x40 -> {
                    if (_fragmentedBuffer[0] == null) {
                        Log.e(TAG, "No NAL FU_A start packet received. Skipped RTP packet.")
                    } else {
                        nalUnit = ByteArray(_fragmentedBufferLength + length + 2)
                        writeNalPrefix0001(nalUnit)
                        tmpLen = 4
                        // Write start and middle packets
                        var i = 0
                        while (i < _fragmentedPackets + 1) {
                            System.arraycopy(_fragmentedBuffer[i]!!, 0, nalUnit, tmpLen, _fragmentedBuffer[i]!!.size)
                            tmpLen += _fragmentedBuffer[i]!!.size
                            ++i
                        }
                        // Write end packet
                        System.arraycopy(data, 2, nalUnit, tmpLen, length - 2)
                        clearFragmentedBuffer()
                        if (Rtsp.DEBUG) Log.d(TAG, "Fragmented NAL (" + (nalUnit.size) + ")")
                    }
                }
            }

            VideoCodecUtils.NAL_FU_B -> {}
            else -> {
                nalUnit = ByteArray(4 + length)
                writeNalPrefix0001(nalUnit)
                System.arraycopy(data, 0, nalUnit, 4, length)
                clearFragmentedBuffer()
                if (Rtsp.DEBUG) Log.d(TAG, "Single NAL (" + nalUnit.size + ")")
            }
        }
        return nalUnit
    }

    private fun clearFragmentedBuffer() {
        for (i in 0 until _fragmentedPackets + 1) {
            _fragmentedBuffer[i] = null
        }
    }

    private fun writeNalPrefix0001(buffer: ByteArray) {
        buffer[0] = 0x00
        buffer[1] = 0x00
        buffer[2] = 0x00
        buffer[3] = 0x01
    }

}
