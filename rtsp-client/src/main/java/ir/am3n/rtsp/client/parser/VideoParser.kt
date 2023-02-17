package ir.am3n.rtsp.client.parser

abstract class VideoParser {

    abstract val NAL_SPS: Byte
    abstract val NAL_PPS: Byte
    abstract val NAL_IDR_SLICE: Byte

    abstract fun processPacketAndGetNalUnit(data: ByteArray, length: Int): ByteArray?

    abstract fun getNalUnitType(data: ByteArray?, offset: Int, length: Int): Byte

}