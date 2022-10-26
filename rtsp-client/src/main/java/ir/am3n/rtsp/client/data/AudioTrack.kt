package ir.am3n.rtsp.client.data

import ir.am3n.rtsp.client.RtspClientUtils

class AudioTrack : Track() {
    var audioCodec: Int = RtspClientUtils.AUDIO_CODEC_UNKNOWN
    // 16000, 8000
    var sampleRateHz = 0
    // 1 - mono, 2 - stereo
    var channels = 0
    // AAC-lbr, AAC-hbr
    var mode: String? = null
    var config: ByteArray? = null
}