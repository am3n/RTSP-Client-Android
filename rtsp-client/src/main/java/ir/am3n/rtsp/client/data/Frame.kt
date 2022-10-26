package ir.am3n.rtsp.client.data

data class Frame(
    val data: ByteArray,
    val offset: Int,
    val length: Int,
    val timestamp: Long
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Frame

        if (!data.contentEquals(other.data)) return false
        if (offset != other.offset) return false
        if (length != other.length) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + offset
        result = 31 * result + length
        result = 31 * result + timestamp.hashCode()
        return result
    }

}