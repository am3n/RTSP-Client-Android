package ir.am3n.rtsp.client.codec

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.SurfaceView
import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoDecoder(
    var surfaceView: SurfaceView?,
    private val mimeType: String,
    private val width: Int,
    private val height: Int,
    private val queue: FrameQueue,
    private val clientListener: RtspClientListener
) : Thread() {

    companion object {
        private const val TAG: String = "VideoDecoder"
        private const val DEBUG = false
    }

    private val srcRect = Rect()
    private val dstRect = Rect()
    private var exitFlag: AtomicBoolean = AtomicBoolean(false)

    /*private var timestamp = System.currentTimeMillis()
    private var sum = 0L
    private var decodeSum = 0L
    private var count = 0*/

    init {
        name = "RTSP video thread"
        srcRect.right = width
        srcRect.bottom = height
        dstRect.right = width
        dstRect.bottom = height
        if (width > height) {
            val rate = (surfaceView?.measuredWidth ?: 0).toFloat() / width.toFloat()
            val height = (height * rate).toInt()
            surfaceView?.holder?.setFixedSize(width, height)
        } else {
            val rate = (surfaceView?.measuredHeight ?: 0).toFloat() / height.toFloat()
            val width = (width * rate).toInt()
            surfaceView?.holder?.setFixedSize(width, height)
        }
    }

    fun stopAsync() {
        if (DEBUG) Log.v(TAG, "stopAsync()")
        exitFlag.set(true)
        // Wake up sleep() code
        interrupt()
    }

    override fun run() {
        if (DEBUG) Log.d(TAG, "$name started")

        try {
            val decoder = MediaCodec.createDecoderByType(mimeType)
            val widthHeight = getDecoderSafeWidthHeight(decoder)
            val format = MediaFormat.createVideoFormat(mimeType, widthHeight.first, widthHeight.second)

            if (DEBUG) Log.d(TAG, "Configuring surface ${widthHeight.first}x${widthHeight.second} w/ '$mimeType'")

            decoder.configure(format, null, null, 0)

            decoder.start()
            if (DEBUG) Log.d(TAG, "Started surface decoder")

            val bufferInfo = MediaCodec.BufferInfo()

            // Main loop
            while (!exitFlag.get()) {

                val inIndex: Int = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {

                    val byteBuffer: ByteBuffer? = decoder.getInputBuffer(inIndex)
                    byteBuffer?.rewind()

                    val frame = queue.pop()
                    if (frame == null) {
                        if (DEBUG) Log.d(TAG, "Empty video frame")
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                    } else {
                        byteBuffer?.put(frame.data, frame.offset, frame.length)
                        decoder.queueInputBuffer(inIndex, frame.offset, frame.length, frame.timestamp, 0)
                    }
                }

                if (exitFlag.get())
                    break

                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (DEBUG) Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                        decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    }
                    else -> {
                        if (outIndex >= 0) {
                            decodeYuv(decoder, bufferInfo, outIndex)
                            decoder.releaseOutputBuffer(outIndex, bufferInfo.size != 0 && !exitFlag.get())
                        }
                    }
                }

                // All decoded frames have been rendered, we can stop playing now
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (DEBUG) Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                    break
                }

            }

            // Drain decoder
            val inIndex: Int = decoder.dequeueInputBuffer(5000L)
            if (inIndex >= 0) {
                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                if (DEBUG) Log.w(TAG, "Not able to signal end of stream")
            }

            decoder.stop()
            decoder.release()
            queue.clear()

        } catch (e: Exception) {
            if (DEBUG) Log.e(TAG, "$name stopped due to '${e.message}'")
            // While configuring stopAsync can be called and surface released. Just exit.
            if (!exitFlag.get())
                e.printStackTrace()
            return
        }

        if (DEBUG) Log.d(TAG, "$name stopped")
    }

    private fun decodeYuv(decoder: MediaCodec, info: MediaCodec.BufferInfo, index: Int) {
        try {

            //val t = System.currentTimeMillis()

            val buffer = decoder.getOutputBuffer(index)
            buffer!!.position(info.offset)
            buffer.limit(info.offset + info.size)
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray)

            val bitmap = try {
                Toolkit.yuvToRgbBitmap(byteArray, width, height, YuvFormat.YUV_420_888)
            } catch (t: Throwable) {
                t.printStackTrace()
                null
            }

            clientListener.onRtspVideoFrameReceived(
                decoder.getOutputImage(index),
                bitmap?.copy(Bitmap.Config.RGB_565, true)
            )

            if (bitmap != null) {
                surfaceView?.post {
                    surfaceView?.holder?.surface?.run {
                        if (isValid) {
                            lockCanvas(dstRect)?.run {
                                drawBitmap(bitmap, srcRect, dstRect, null)
                                unlockCanvasAndPost(this)
                            }
                        }
                    }
                }
            }

            /*val time = System.currentTimeMillis() - timestamp
            val decodeTime = System.currentTimeMillis() - t
            if (DEBUG) Log.d(TAG, "time: $time      decode time: $decodeTime")
            timestamp = System.currentTimeMillis()

            sum += time
            decodeSum += decodeTime
            count++
            if (count % 500 == 0) {
                if (DEBUG) Log.d(TAG, "avg: ${sum / count}      decode avg: ${(decodeSum / count).toInt()}")
                sum = 0
                decodeSum = 0
                count = 0
            }*/

        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun getDecoderSafeWidthHeight(decoder: MediaCodec): Pair<Int, Int> {
        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        return if (capabilities.isSizeSupported(width, height)) {
            Pair(width, height)
        } else {
            val widthAlignment = capabilities.widthAlignment
            val heightAlignment = capabilities.heightAlignment
            Pair(ceilDivide(width, widthAlignment) * widthAlignment, ceilDivide(height, heightAlignment) * heightAlignment)
        }
    }

    private fun ceilDivide(numerator: Int, denominator: Int): Int {
        return (numerator + denominator - 1) / denominator
    }

}

