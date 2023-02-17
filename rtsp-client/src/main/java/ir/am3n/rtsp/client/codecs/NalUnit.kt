package ir.am3n.rtsp.client.codecs

data class NalUnit(
    val type: Byte,
    val offset: Int,
    val length: Int
)