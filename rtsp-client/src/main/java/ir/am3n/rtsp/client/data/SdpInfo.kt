package ir.am3n.rtsp.client.data

class SdpInfo {

    /**
     * Session name (RFC 2327). In most cases RTSP server name.
     */
    var sessionName: String? = null

    /**
     * Session description (RFC 2327).
     */
    var sessionDescription: String? = null

    var videoTrack: VideoTrack? = null

    var audioTrack: AudioTrack? = null

}