package ir.am3n.rtsp.client.data

import ir.am3n.rtsp.client.RtspClientUtils

class VideoTrack : Track() {
    var videoCodec = RtspClientUtils.VIDEO_CODEC_H264
    var sps: ByteArray? = null
    var pps: ByteArray? = null
}