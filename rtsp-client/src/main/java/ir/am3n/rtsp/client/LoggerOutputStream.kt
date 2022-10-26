package ir.am3n.rtsp.client

import android.util.Log
import kotlin.jvm.Synchronized
import kotlin.Throws
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

internal class LoggerOutputStream(out: OutputStream) : BufferedOutputStream(out) {

    @Throws(IOException::class)
    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        Log.i(RtspClient.TAG + " Out", String(b, off, len))
        super.write(b, off, len)
    }

}