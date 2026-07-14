type EncodedStreamPair = {
    readable: ReadableStream;
    writable: WritableStream;
};

type InsertableSender = RTCRtpSender & {
    createEncodedStreams?: () => EncodedStreamPair;
};

type InsertableReceiver = RTCRtpReceiver & {
    createEncodedStreams?: () => EncodedStreamPair;
};

export type WebCodecInsertableCodec = 'vp9' | 'vp8';
export const DEFAULT_WEB_CODEC_INSERTABLE_CODEC: WebCodecInsertableCodec = 'vp9';

export interface WebCodecInsertableOptions {
    codec?: WebCodecInsertableCodec;
    width?: number;
    height?: number;
    framerate?: number;
    bitrate?: number;
    keyFrameIntervalSeconds?: number;
}

export interface WebCodecPayloadEncoderHandle {
    stop: () => void;
}

export interface WebCodecSenderPipelineHandle {
    worker: Worker;
    payloadEncoder: WebCodecPayloadEncoderHandle | null;
    stop: () => void;
}

export function isEncodedInsertableStreamsSupported(): boolean {
    return typeof RTCRtpSender !== 'undefined'
        && typeof (RTCRtpSender.prototype as InsertableSender).createEncodedStreams === 'function';
}

export function isWebCodecPayloadEncoderSupported(): boolean {
    return typeof window !== 'undefined'
        && typeof VideoEncoder !== 'undefined'
        && typeof window.MediaStreamTrackProcessor !== 'undefined';
}

export function createWebCodecInsertableWorker(): Worker {
    return new Worker('/webcodec_insertable_worker.js', {
        name: 'WebCodec Insertable Streams worker',
    });
}

function toVideoEncoderCodecString(codec: WebCodecInsertableCodec): string {
    return codec === 'vp9' ? 'vp09.00.10.08' : 'vp8';
}

export async function startWebCodecPayloadEncoder(
    stream: MediaStream,
    worker: Worker,
    options: WebCodecInsertableOptions = {}
): Promise<WebCodecPayloadEncoderHandle | null> {
    if (!isWebCodecPayloadEncoderSupported()) {
        console.warn('[webcodec_insertable] WebCodecs payload encoder is not supported.');
        return null;
    }

    const sourceTrack = stream.getVideoTracks()[0];

    if (!sourceTrack) {
        console.warn('[webcodec_insertable] Payload encoder needs a video track.');
        return null;
    }

    const track = sourceTrack.clone();
    const settings = sourceTrack.getSettings();
    const width = options.width ?? settings.width ?? 640;
    const height = options.height ?? settings.height ?? 480;
    const framerate = options.framerate ?? settings.frameRate ?? 30;
    const keyFrameInterval = Math.max(
        1,
        Math.floor(framerate * (options.keyFrameIntervalSeconds ?? 2))
    );
    const processor = new window.MediaStreamTrackProcessor!({ track });
    const reader = processor.readable.getReader();
    const codec = options.codec ?? DEFAULT_WEB_CODEC_INSERTABLE_CODEC;
    const config: VideoEncoderConfig = {
        codec: toVideoEncoderCodecString(codec),
        width,
        height,
        framerate,
        bitrate: options.bitrate ?? 1_200_000,
        latencyMode: 'realtime',
        hardwareAcceleration: 'prefer-hardware',
    };
    const support = await VideoEncoder.isConfigSupported(config);

    if (!support.supported) {
        console.warn('[webcodec_insertable] VideoEncoder config is not supported:', config);
        track.stop();
        return null;
    }

    let stopped = false;
    let frameIndex = 0;
    let forceKeyFrame = true;

    const encoder = new VideoEncoder({
        output: (chunk) => {
            const data = new Uint8Array(chunk.byteLength);
            chunk.copyTo(data);
            worker.postMessage({
                operation: 'webcodecEncodedChunk',
                type: chunk.type,
                timestamp: chunk.timestamp,
                duration: chunk.duration,
                data: data.buffer,
            }, [data.buffer]);
        },
        error: (error) => {
            console.error('[webcodec_insertable] VideoEncoder error:', error);
        },
    });

    const onWorkerMessage = (event: MessageEvent) => {
        if (event.data?.operation === 'requestWebCodecKeyFrame') {
            forceKeyFrame = true;
        }
    };

    worker.addEventListener('message', onWorkerMessage);
    worker.postMessage({ operation: 'clearWebCodecQueue' });
    encoder.configure(support.config ?? config);
    void pump();

    async function pump() {
        while (!stopped) {
            const { value: frame, done } = await reader.read();

            if (done || !frame) {
                break;
            }

            frameIndex++;

            try {
                const keyFrame = forceKeyFrame || frameIndex % keyFrameInterval === 1;
                forceKeyFrame = false;
                encoder.encode(frame, { keyFrame });
            } catch (error) {
                console.error('[webcodec_insertable] WebCodecs payload encode failed:', error);
            } finally {
                frame.close();
            }
        }
    }

    return {
        stop() {
            stopped = true;
            worker.removeEventListener('message', onWorkerMessage);
            worker.postMessage({ operation: 'clearWebCodecQueue' });
            reader.cancel().catch(() => undefined);
            track.stop();

            if (encoder.state !== 'closed') {
                encoder.close();
            }
        },
    };
}

export async function setupWebCodecSenderTransform(
    sender: RTCRtpSender,
    stream: MediaStream,
    options: WebCodecInsertableOptions = {}
): Promise<WebCodecSenderPipelineHandle | null> {
    if (sender.track?.kind !== 'video') {
        return null;
    }

    const insertableSender = sender as InsertableSender;

    if (typeof insertableSender.createEncodedStreams !== 'function') {
        console.warn('[webcodec_insertable] RTCRtpSender.createEncodedStreams is not supported.');
        return null;
    }

    const worker = createWebCodecInsertableWorker();
    const { readable, writable } = insertableSender.createEncodedStreams();

    worker.postMessage({
        operation: 'encode',
        readable,
        writable,
        codec: options.codec ?? DEFAULT_WEB_CODEC_INSERTABLE_CODEC,
    }, [readable, writable]);

    const payloadEncoder = await startWebCodecPayloadEncoder(stream, worker, options);

    if (!payloadEncoder) {
        worker.terminate();
        return null;
    }

    return {
        worker,
        payloadEncoder,
        stop() {
            payloadEncoder.stop();
            worker.terminate();
        },
    };
}

export function setupReceiverPassthroughTransform(receiver: RTCRtpReceiver): Worker | null {
    if (receiver.track?.kind !== 'video') {
        return null;
    }

    const insertableReceiver = receiver as InsertableReceiver;

    if (typeof insertableReceiver.createEncodedStreams !== 'function') {
        return null;
    }

    const worker = createWebCodecInsertableWorker();
    const { readable, writable } = insertableReceiver.createEncodedStreams();

    worker.postMessage({
        operation: 'decode',
        readable,
        writable,
    }, [readable, writable]);

    return worker;
}
