package ir.am3n.rtsp.client.decoders

import ir.am3n.rtsp.client.data.AudioFrame

class AudioFrameQueue(frameQueueCapacity: Int) : FrameQueue<AudioFrame>(frameQueueCapacity)