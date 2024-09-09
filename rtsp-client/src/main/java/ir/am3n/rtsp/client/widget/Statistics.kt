package ir.am3n.rtsp.client.widget

import ir.am3n.utils.DecoderType

class Statistics {
    var videoDecoderType = DecoderType.HARDWARE
    var videoDecoderName: String? = null
    var videoDecoderLatencyMillis = -1
    var networkLatencyMillis = -1
}