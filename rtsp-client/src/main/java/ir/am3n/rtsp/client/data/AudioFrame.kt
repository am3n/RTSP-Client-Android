package ir.am3n.rtsp.client.data

import ir.am3n.rtsp.client.interfaces.Frame
import ir.am3n.utils.AudioCodecType

data class AudioFrame(
    val codecType: AudioCodecType,
    override val data: ByteArray,
    override val offset: Int,
    override val length: Int,
    override val timestamp: Long,
) : Frame {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioFrame

        if (codecType != other.codecType) return false
        if (!data.contentEquals(other.data)) return false
        if (offset != other.offset) return false
        if (length != other.length) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = codecType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + offset
        result = 31 * result + length
        result = 31 * result + timestamp.hashCode()
        return result
    }

}