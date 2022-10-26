package ir.am3n.rtsp.client.interfaces

import ir.am3n.rtsp.client.data.SdpInfo

internal interface RtspClientListener {
    fun onRtspConnected(sdpInfo: SdpInfo)
    fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
    fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long)
    fun onRtspDisconnected()
    fun onRtspFailedUnauthorized()
    fun onRtspFailed(message: String?)
}