package ir.am3n.utils

import android.util.Log
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.experimental.and

internal object VideoCodecUtils {

    internal data class NalUnit(val type: Byte, val offset: Int, val length: Int)

    private const val TAG = "VideoCodecUtils"

    private const val NAL_SLICE: Byte = 1
    private const val NAL_DPA: Byte = 2
    private const val NAL_DPB: Byte = 3
    private const val NAL_DPC: Byte = 4
    internal const val NAL_IDR_SLICE: Byte = 5
    private const val NAL_SEI: Byte = 6
    internal const val NAL_SPS: Byte = 7
    internal const val NAL_PPS: Byte = 8
    private const val NAL_AUD: Byte = 9
    private const val NAL_END_SEQUENCE: Byte = 10
    private const val NAL_END_STREAM: Byte = 11
    private const val NAL_FILLER_DATA: Byte = 12
    private const val NAL_SPS_EXT: Byte = 13
    private const val NAL_AUXILIARY_SLICE: Byte = 19
    private const val NAL_STAP_A: Byte = 24 // https://tools.ietf.org/html/rfc3984 5.7.1
    private const val NAL_STAP_B: Byte = 25 // 5.7.1
    private const val NAL_MTAP16: Byte = 26 // 5.7.2
    private const val NAL_MTAP24: Byte = 27 // 5.7.2
    private const val NAL_FU_A: Byte = 28 // 5.8 fragmented unit
    private const val NAL_FU_B: Byte = 29 // 5.8

    private val NAL_PREFIX1 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    private val NAL_PREFIX2 = byteArrayOf(0x00, 0x00, 0x01)


    fun getH264NalUnitType(data: ByteArray?, offset: Int, length: Int): Byte {
        if (data == null || length <= NAL_PREFIX1.size) return ((-1).toByte())
        var nalUnitTypeOctetOffset = -1
        if (data[offset + NAL_PREFIX2.size - 1].toInt() == 1) nalUnitTypeOctetOffset =
            offset + NAL_PREFIX2.size - 1 else if (data[offset + NAL_PREFIX1.size - 1].toInt() == 1) nalUnitTypeOctetOffset = offset + NAL_PREFIX1.size - 1
        return if (nalUnitTypeOctetOffset != -1) {
            val nalUnitTypeOctet = data[nalUnitTypeOctetOffset + 1]
            (nalUnitTypeOctet and 0x1f)
        } else {
            ((-1).toByte())
        }
    }

    /**
     * Search for 00 00 01 or 00 00 00 01 in byte stream.
     *
     * @return offset to the start of NAL unit if found, otherwise -1
     */
    private fun searchForH264NalUnitStart(
        data: ByteArray,
        offset: Int,
        length: Int,
        prefixSize: AtomicInteger
    ): Int {
        if (offset >= data.size - 3) return -1
        for (pos in 0 until length) {
            val prefix = getNalUnitStartCodePrefixSize(data, pos + offset, length)
            if (prefix >= 0) {
                prefixSize.set(prefix)
                return pos + offset
            }
        }
        return -1
    }

    fun getH264NalUnitsNumber(
        data: ByteArray,
        dataOffset: Int,
        length: Int
    ): Int {
        return getH264NalUnits(data, dataOffset, length, ArrayList())
    }

    private fun getH264NalUnits(
        data: ByteArray,
        dataOffset: Int,
        length: Int,
        foundNals: ArrayList<NalUnit?>
    ): Int {
        foundNals.clear()
        var nalUnits = 0
        val nextNalOffset = 0
        val nalUnitPrefixSize = AtomicInteger(-1)
        val timestamp = System.currentTimeMillis()
        var offset = dataOffset
        var stopped = false
        while (!stopped) {

            // Search for first NAL unit
            val nalUnitIndex = searchForH264NalUnitStart(
                data,
                offset + nextNalOffset,
                length - nextNalOffset,
                nalUnitPrefixSize
            )

            // NAL unit found
            if (nalUnitIndex >= 0) {
                nalUnits++
                val nalUnitOffset = offset + nextNalOffset + nalUnitPrefixSize.get()
                val nalUnitTypeOctet = data[nalUnitOffset]
                val nalUnitType: Byte = nalUnitTypeOctet and 0x1f

                // Search for second NAL unit (optional)
                var nextNalUnitStartIndex = searchForH264NalUnitStart(
                    data,
                    nalUnitOffset,
                    length - nalUnitOffset,
                    nalUnitPrefixSize
                )

                // Second NAL unit not found. Use till the end.
                if (nextNalUnitStartIndex < 0) {
                    // Not found next NAL unit. Use till the end.
//                  nextNalUnitStartIndex = length - nextNalOffset + dataOffset;
                    nextNalUnitStartIndex = length + dataOffset
                    stopped = true
                }
                val l = nextNalUnitStartIndex - offset
                Log.d(
                    TAG, "NAL unit type: " + getH264NalUnitTypeString(nalUnitType) +
                            " (" + nalUnitType + ") - " + l + " bytes, offset " + offset
                )
                foundNals.add(NalUnit(nalUnitType, offset, l))
                offset = nextNalUnitStartIndex

                // Check that we are not too long here
                if (System.currentTimeMillis() - timestamp > 100) {
                    Log.w(TAG, "Cannot process data within 100 msec in $length bytes")
                    break
                }
            } else {
                stopped = true
            }
        }
        return nalUnits
    }

    private fun getH264NalUnitTypeString(nalUnitType: Byte): String {
        return when (nalUnitType) {
            NAL_SLICE -> "NAL_SLICE"
            NAL_DPA -> "NAL_DPA"
            NAL_DPB -> "NAL_DPB"
            NAL_DPC -> "NAL_DPC"
            NAL_IDR_SLICE -> "NAL_IDR_SLICE"
            NAL_SEI -> "NAL_SEI"
            NAL_SPS -> "NAL_SPS"
            NAL_PPS -> "NAL_PPS"
            NAL_AUD -> "NAL_AUD"
            NAL_END_SEQUENCE -> "NAL_END_SEQUENCE"
            NAL_END_STREAM -> "NAL_END_STREAM"
            NAL_FILLER_DATA -> "NAL_FILLER_DATA"
            NAL_SPS_EXT -> "NAL_SPS_EXT"
            NAL_AUXILIARY_SLICE -> "NAL_AUXILIARY_SLICE"
            NAL_STAP_A -> "NAL_STAP_A"
            NAL_STAP_B -> "NAL_STAP_B"
            NAL_MTAP16 -> "NAL_MTAP16"
            NAL_MTAP24 -> "NAL_MTAP24"
            NAL_FU_A -> "NAL_FU_A"
            NAL_FU_B -> "NAL_FU_B"
            else -> "unknown - $nalUnitType"
        }
    }

    private fun getNalUnitStartCodePrefixSize(data: ByteArray, offset: Int, length: Int): Int {
        if (length < 4) return -1
        return if (ByteUtils.memcmp(data, offset, NAL_PREFIX1, offsetSource2 = 0, NAL_PREFIX1.size))
            NAL_PREFIX1.size
        else if (ByteUtils.memcmp(data, offset, NAL_PREFIX2, offsetSource2 = 0, NAL_PREFIX2.size))
            NAL_PREFIX2.size else -1
    }

}