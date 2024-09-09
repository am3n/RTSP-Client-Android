package ir.am3n.rtsp.client.interfaces

interface Frame {
    val data: ByteArray
    val offset: Int
    val length: Int
    val timestamp: Long
}