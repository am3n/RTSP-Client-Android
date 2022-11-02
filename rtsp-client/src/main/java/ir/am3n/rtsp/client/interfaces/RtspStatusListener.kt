package ir.am3n.rtsp.client.interfaces

import ir.am3n.rtsp.client.data.SdpInfo

interface RtspStatusListener {
    fun onConnecting()
    fun onConnected(sdpInfo: SdpInfo)
    fun onDisconnected()
    fun onUnauthorized()
    fun onFailed(message: String?)
}