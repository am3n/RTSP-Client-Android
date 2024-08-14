package ir.am3n.rtsp.client.decoders

import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.data.Frame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class FrameQueue(
    frameQueueSize: Int,
    var timeout: Long = 5000
) {

    companion object {
        private const val TAG: String = "FrameQueue"
    }

    private val queue: BlockingQueue<Frame> = ArrayBlockingQueue(frameQueueSize)

    @Throws(InterruptedException::class)
    fun push(frame: Frame): Boolean {
        if (queue.remainingCapacity() <= 0) {
            if (Rtsp.DEBUG) Log.w(TAG, "Queue is full, clearing it..")
            queue.clear()
        }
        if (queue.offer(frame, 5, TimeUnit.MILLISECONDS)) {
            return true
        }
        if (Rtsp.DEBUG) Log.w(TAG, "Cannot add frame, queue is full")
        return false
    }

    @Throws(InterruptedException::class)
    fun pop(): Frame? {
        try {
            val frame: Frame? = queue.poll(timeout, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (Rtsp.DEBUG) Log.w(TAG, "Cannot get frame, queue is empty")
            }
            return frame
        } catch (e: InterruptedException) {
            if (Rtsp.DEBUG) Log.w(TAG, "Cannot get frame", e)
            Thread.currentThread().interrupt()
        }
        return null
    }

    fun clear() {
        queue.clear()
    }

}
