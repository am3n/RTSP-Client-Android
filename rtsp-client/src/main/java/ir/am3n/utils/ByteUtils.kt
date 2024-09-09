package ir.am3n.utils

internal object ByteUtils {

    // int memcmp ( const void * ptr1, const void * ptr2, size_t num );
    fun memcmp(source1: ByteArray, offsetSource1: Int, source2: ByteArray, offsetSource2: Int, num: Int): Boolean {
        if (source1.size - offsetSource1 < num)
            return false
        if (source2.size - offsetSource2 < num)
            return false
        for (i in 0 until num) {
            if (source1[offsetSource1 + i] != source2[offsetSource2 + i])
                return false
        }
        return true
    }

    fun copy(src: ByteArray): ByteArray {
        val dest = ByteArray(src.size)
        System.arraycopy(src, 0, dest, 0, src.size)
        return dest
    }

    fun ByteArray.toHexString(offset: Int, maxLength: Int): String {
        val length = minOf(maxLength, size - offset)
        return sliceArray(offset until (offset + length))
            .joinToString(separator = "") { byte ->
                "%02x ".format(byte).uppercase()
            }
    }

}