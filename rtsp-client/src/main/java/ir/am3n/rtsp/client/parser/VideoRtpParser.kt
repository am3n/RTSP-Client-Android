package ir.am3n.rtsp.client.parser

import android.util.Log

internal class VideoRtpParser {

    private val _buffer = arrayOfNulls<ByteArray>(1024)
    private lateinit var _nalUnit: ByteArray
    private var _nalEndFlag = false
    private var _bufferLength = 0
    private var _packetNum = 0

    fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int): ByteArray? {
        if (DEBUG) Log.v(TAG, "processRtpPacketAndGetNalUnit(length=$length)")
        var tmpLen: Int
        val nalType: Int = data[0].toInt() and 0x1F
        val packFlag: Int = data[1].toInt() and 0xC0
        if (DEBUG) Log.d(TAG, "NAL type: $nalType, pack flag: $packFlag")
        when (nalType) {
            NAL_UNIT_TYPE_STAP_A -> {}
            NAL_UNIT_TYPE_STAP_B -> {}
            NAL_UNIT_TYPE_MTAP16 -> {}
            NAL_UNIT_TYPE_MTAP24 -> {}
            NAL_UNIT_TYPE_FU_A -> when (packFlag) {
                0x80 -> {
                    _nalEndFlag = false
                    _packetNum = 1
                    _bufferLength = length - 1
                    _buffer[1] = ByteArray(_bufferLength)
                    _buffer[1]!![0] = (data[0].toInt() and 0xE0 or (data[1].toInt() and 0x1F)).toByte()
                    System.arraycopy(data, 2, _buffer[1]!!, 1, length - 2)
                }
                0x00 -> {
                    _nalEndFlag = false
                    _packetNum++
                    _bufferLength += length - 2
                    _buffer[_packetNum] = ByteArray(length - 2)
                    System.arraycopy(data, 2, _buffer[_packetNum]!!, 0, length - 2)
                }
                0x40 -> {
                    _nalEndFlag = true
                    _nalUnit = ByteArray(_bufferLength + length + 2)
                    _nalUnit[0] = 0x00
                    _nalUnit[1] = 0x00
                    _nalUnit[2] = 0x00
                    _nalUnit[3] = 0x01
                    tmpLen = 4
                    System.arraycopy(_buffer[1]!!, 0, _nalUnit, tmpLen, _buffer[1]!!.size)
                    tmpLen += _buffer[1]!!.size
                    var i = 2
                    while (i < _packetNum + 1) {
                        System.arraycopy(_buffer[i]!!, 0, _nalUnit, tmpLen, _buffer[i]!!.size)
                        tmpLen += _buffer[i]!!.size
                        ++i
                    }
                    System.arraycopy(data, 2, _nalUnit, tmpLen, length - 2)
                }
            }
            NAL_UNIT_TYPE_FU_B -> {}
            else -> {
                if (DEBUG) Log.d(TAG, "Single NAL")
                _nalUnit = ByteArray(4 + length)
                _nalUnit[0] = 0x00
                _nalUnit[1] = 0x00
                _nalUnit[2] = 0x00
                _nalUnit[3] = 0x01
                System.arraycopy(data, 0, _nalUnit, 4, length)
                _nalEndFlag = true
            }
        }
        return if (_nalEndFlag) {
            _nalUnit
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "VideoRtpParser"
        private const val DEBUG = false
        private const val NAL_UNIT_TYPE_STAP_A = 24
        private const val NAL_UNIT_TYPE_STAP_B = 25
        private const val NAL_UNIT_TYPE_MTAP16 = 26
        private const val NAL_UNIT_TYPE_MTAP24 = 27
        private const val NAL_UNIT_TYPE_FU_A = 28
        private const val NAL_UNIT_TYPE_FU_B = 29
    }

}