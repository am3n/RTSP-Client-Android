package ir.am3n.rtsp.client.decoders

import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.utils.AudioCodecType
import ir.am3n.utils.VideoCodecType
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Queue for concurrent adding/removing audio/video frames.
 */
open class FrameQueue<T>(
    private val frameQueueCapacity: Int,
    var timeout: Long = 5000
) {

    companion object {
        private val TAG: String = FrameQueue::class.java.simpleName
    }

    private val queue = ArrayBlockingQueue<T>(frameQueueCapacity)

    val size: Int
        get() = queue.size

    val capacity: Int
        get() = frameQueueCapacity

    @Throws(InterruptedException::class)
    fun push(frame: T): Boolean {
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
    open fun pop(): T? {
        try {
            val frame: T? = queue.poll(timeout, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (Rtsp.DEBUG) Log.w(TAG, "Cannot get frame, queue is empty")
            }
            return frame
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (Rtsp.DEBUG) Log.w(TAG, "Cannot get frame", e)
        }
        return null
    }

    fun clear() {
        queue.clear()
    }

    fun copyInto(dstFrameQueue: FrameQueue<T>) {
        dstFrameQueue.queue.addAll(queue)
    }

}
