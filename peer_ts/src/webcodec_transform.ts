// src/webcodec.ts

/*
  WebCodec encode/decode round-trip pipeline

  raw MediaStream
    -> video track
    -> MediaStreamTrackProcessor
    -> VideoFrame
    -> VideoEncoder
    -> EncodedVideoChunk
    -> VideoDecoder
    -> VideoFrame
    -> MediaStreamTrackGenerator
    -> processed MediaStream

  이 코드는 "테스트용"으로 WebCodecs VideoEncoder/VideoDecoder를 실제로 통과시키기 위한 코드이다.

  주의:
  이 WebCodecs 인코딩은 WebRTC RTP 전송용 인코딩이 아니다.
  최종적으로 pc.addTrack(processedTrack)을 하면 WebRTC가 다시 전송용 인코딩을 수행한다.
*/

type MediaStreamTrackProcessorConstructor = new (init: {
    track: MediaStreamTrack;
}) => {
    readable: ReadableStream<VideoFrame>;
};

type MediaStreamTrackGeneratorConstructor = new (
    kind: 'video'
) => MediaStreamTrack & {
    writable: WritableStream<VideoFrame>;
};

declare global {
    interface Window {
        MediaStreamTrackProcessor?: MediaStreamTrackProcessorConstructor;
        MediaStreamTrackGenerator?: MediaStreamTrackGeneratorConstructor;
    }
}

export type WebCodecTestCodec = 'vp8' | 'h264';

export interface WebCodecRoundTripOptions {
    codec?: WebCodecTestCodec;
    width?: number;
    height?: number;
    framerate?: number;
    bitrate?: number;
    hardwareAcceleration?: HardwareAcceleration;
    latencyMode?: LatencyMode;
}

export interface WebCodecRoundTripHandle {
    processedStream: MediaStream;
    processedVideoTrack: MediaStreamTrack;
    originalVideoTrack: MediaStreamTrack;
    abort: () => void;
}

export function isWebCodecRoundTripSupported(): boolean {
    return (
        typeof window !== 'undefined' &&
        typeof window.MediaStreamTrackProcessor !== 'undefined' &&
        typeof window.MediaStreamTrackGenerator !== 'undefined' &&
        typeof TransformStream !== 'undefined' &&
        typeof VideoFrame !== 'undefined' &&
        typeof VideoEncoder !== 'undefined' &&
        typeof VideoDecoder !== 'undefined'
    );
}

function getCodecString(codec: WebCodecTestCodec): string {
    /*
      테스트 안정성만 보면 vp8이 가장 단순하다.
      H.264는 브라우저/OS/하드웨어/프로파일에 따라 지원 여부가 달라질 수 있다.
    */
    if (codec === 'h264') {
        console.log('[webcodec.ts] Using H.264 codec string with baseline profile for better compatibility.');
        return 'avc1.42001E';
    }
    console.log('[webcodec.ts] Using VP8 codec string.');
    return 'vp8';
}

function getFrameSize(frame: VideoFrame): { width: number; height: number } {
    const width = frame.displayWidth || frame.codedWidth;
    const height = frame.displayHeight || frame.codedHeight;

    return { width, height };
}

/**
 * 핵심 함수:
 * rawStream의 video track을 WebCodecs VideoEncoder -> VideoDecoder로 통과시킨 뒤
 * 다시 MediaStreamTrack으로 만든다.
 */
export async function createWebCodecRoundTripStream(
    rawStream: MediaStream,
    options: WebCodecRoundTripOptions = {}
): Promise<WebCodecRoundTripHandle> {
    if (!isWebCodecRoundTripSupported()) {
        throw new Error(
            '[webcodec.ts] WebCodecs or MediaStreamTrackProcessor/Generator is not supported in this browser.'
        );
    }

    const originalVideoTrack = rawStream.getVideoTracks()[0];

    if (!originalVideoTrack) {
        throw new Error('[webcodec.ts] rawStream has no video track.');
    }

    const Processor = window.MediaStreamTrackProcessor!;
    const Generator = window.MediaStreamTrackGenerator!;

    const processor = new Processor({ track: originalVideoTrack });
    const generator = new Generator('video');

    const abortController = new AbortController();

    const trackSettings = originalVideoTrack.getSettings();
    const defaultWidth = trackSettings.width ?? options.width ?? 640;
    const defaultHeight = trackSettings.height ?? options.height ?? 480;
    const defaultFramerate = trackSettings.frameRate ?? options.framerate ?? 30;

    const codecType = options.codec ?? 'vp8';
    const codec = getCodecString(codecType);

    let streamController: TransformStreamDefaultController<VideoFrame> | null = null;
    let decoderConfigured = false;

    let canvas: OffscreenCanvas | null = null;
    let ctx: OffscreenCanvasRenderingContext2D | null = null;
    let frameCount = 0;
    let keyFrameInterval = Math.max(1, Math.floor(defaultFramerate * 2));

    const decoder = new VideoDecoder({
        output: (decodedFrame: VideoFrame) => {
            /*
              디코딩된 VideoFrame만 MediaStreamTrackGenerator로 보낼 수 있다.
              EncodedVideoChunk는 generator.writable에 넣을 수 없다.
            */
            if (!streamController) {
                decodedFrame.close();
                return;
            }

            streamController.enqueue(decodedFrame);
        },
        error: (error) => {
            console.error('[webcodec.ts] VideoDecoder error:', error);
        },
    });

    const encoder = new VideoEncoder({
        output: (chunk: EncodedVideoChunk, metadata?: EncodedVideoChunkMetadata) => {
            /*
              VP8은 보통 decoder.configure({ codec: 'vp8' })만으로 충분하다.
      
              H.264는 encoder output metadata에 decoderConfig가 붙어 나오는 경우가 있다.
              이 decoderConfig를 사용해야 avcC description 문제를 줄일 수 있다.
            */
            try {
                if (!decoderConfigured) {
                    const decoderConfigFromEncoder = metadata?.decoderConfig;

                    if (decoderConfigFromEncoder) {
                        console.log('[webcodec.ts] Configuring decoder from encoder metadata:', decoderConfigFromEncoder);
                        decoder.configure(decoderConfigFromEncoder);
                    } else {
                        console.log('[webcodec.ts] Configuring decoder with basic codec:', codec);
                        decoder.configure({
                            codec,
                            optimizeForLatency: true,
                        });
                    }

                    decoderConfigured = true;
                }

                if (decoder.state !== 'configured') {
                    console.warn('[webcodec.ts] Decoder is not configured. state =', decoder.state);
                    return;
                }

                decoder.decode(chunk);
            } catch (error) {
                console.error('[webcodec.ts] Failed to decode encoded chunk:', error);
            }
        },
        error: (error) => {
            console.error('[webcodec.ts] VideoEncoder error:', error);
        },
    });

    const encoderConfig: VideoEncoderConfig = {
        codec,
        width: options.width ?? defaultWidth,
        height: options.height ?? defaultHeight,
        framerate: options.framerate ?? defaultFramerate,
        bitrate: options.bitrate ?? 2_000_000,
        hardwareAcceleration: options.hardwareAcceleration ?? 'prefer-hardware',
        latencyMode: options.latencyMode ?? 'realtime',
    };

    console.log('[webcodec.ts] Checking encoder support:', encoderConfig);

    const support = await VideoEncoder.isConfigSupported(encoderConfig);

    if (!support.supported) {
        throw new Error(
            `[webcodec.ts] VideoEncoder config is not supported: ${JSON.stringify(encoderConfig)}`
        );
    }

    console.log('[webcodec.ts] Encoder config supported:', support.config);

    encoder.configure(encoderConfig);

const transformer = new TransformStream<VideoFrame, VideoFrame>({
    async transform(frame, controller) {
        streamController = controller;
        frameCount++;

        let processedFrame: VideoFrame | null = null;

        try {
            if (encoder.state !== 'configured') {
                console.warn('[webcodec.ts] Encoder is not configured. state =', encoder.state);
                return;
            }

            const width = frame.displayWidth || frame.codedWidth;
            const height = frame.displayHeight || frame.codedHeight;

            if (!canvas || canvas.width !== width || canvas.height !== height) {
                canvas = new OffscreenCanvas(width, height);
                ctx = canvas.getContext('2d');

                if (!ctx) {
                    throw new Error('[webcodec.ts] Failed to create OffscreenCanvas 2D context.');
                }

                console.log('[webcodec.ts] OffscreenCanvas initialized:', {
                    width,
                    height,
                });
            }

            /*
              1. 원본 VideoFrame을 canvas에 그림
            */
            ctx!.drawImage(frame, 0, 0, width, height);

            /*
              2. 테스트용 프레임 가공
            */
            ctx!.fillStyle = 'rgba(255, 0, 0, 0.65)';
            ctx!.fillRect(
                10,
                10,
                Math.min(220, width - 20),
                Math.min(70, height - 20)
            );

            ctx!.fillStyle = 'white';
            ctx!.font = '20px sans-serif';
            ctx!.fillText('WebCodec Pipeline', 20, 42);

            ctx!.font = '14px sans-serif';
            ctx!.fillText(`frame: ${frameCount}`, 20, 64);

            /*
              3. canvas 결과를 다시 VideoFrame으로 생성
            */
            processedFrame = new VideoFrame(canvas!, {
                timestamp: frame.timestamp,
                duration: frame.duration ?? undefined,
            });

            /*
              4. key frame 주기 설정
            */
            const forceKeyFrame = frameCount % keyFrameInterval === 1;

            /*
              5. 중요:
                 controller.enqueue(processedFrame)가 아니라
                 encoder.encode(processedFrame)를 해야 한다.

                 그래야 흐름이 아래처럼 된다.

                 조작된 VideoFrame
                   -> VideoEncoder
                   -> EncodedVideoChunk
                   -> VideoDecoder
                   -> decoded VideoFrame
                   -> controller.enqueue(decodedFrame)
                   -> MediaStreamTrackGenerator
            */
            encoder.encode(processedFrame, {
                keyFrame: forceKeyFrame,
            });

        } catch (error) {
            console.error('[webcodec.ts] transform encode failed:', error);
        } finally {
            /*
              원본 frame은 더 이상 필요 없으므로 닫는다.
            */
            frame.close();

            /*
              processedFrame도 encode()에 넘긴 뒤에는 닫아도 된다.
              decoder output callback에서 새 decodedFrame이 다시 나오기 때문에,
              이 processedFrame 자체를 generator로 직접 넘기면 안 된다.
            */
            if (processedFrame) {
                processedFrame.close();
            }
        }
    },

    async flush() {
        console.log('[webcodec.ts] Flushing encoder/decoder...');

        try {
            if (encoder.state === 'configured') {
                await encoder.flush();
            }

            if (decoder.state === 'configured') {
                await decoder.flush();
            }
        } catch (error) {
            console.warn('[webcodec.ts] flush warning:', error);
        }
    },
});

    processor.readable
        .pipeThrough(transformer, { signal: abortController.signal })
        .pipeTo(generator.writable)
        .catch((error) => {
            if (abortController.signal.aborted) {
                console.log('[webcodec.ts] Pipeline aborted normally.');
            } else {
                console.error('[webcodec.ts] Pipeline failed:', error);
            }
        });

    const processedStream = new MediaStream();

    /*
      video는 WebCodecs encode/decode round-trip을 거친 track
    */
    processedStream.addTrack(generator);

    /*
      audio는 WebCodecs와 무관하므로 원본 audio track을 그대로 유지
    */
    rawStream.getAudioTracks().forEach((audioTrack) => {
        processedStream.addTrack(audioTrack);
    });

    console.log('[webcodec.ts] WebCodec round-trip stream created:', {
        codec,
        rawStream,
        originalVideoTrack,
        processedVideoTrack: generator,
        audioTracks: rawStream.getAudioTracks(),
        processedStream,
    });

    return {
        processedStream,
        processedVideoTrack: generator,
        originalVideoTrack,
        abort: () => {
            console.log('[webcodec.ts] Aborting WebCodec round-trip pipeline.');

            abortController.abort();

            try {
                if (encoder.state !== 'closed') {
                    encoder.close();
                }
            } catch (error) {
                console.warn('[webcodec.ts] encoder.close() warning:', error);
            }

            try {
                if (decoder.state !== 'closed') {
                    decoder.close();
                }
            } catch (error) {
                console.warn('[webcodec.ts] decoder.close() warning:', error);
            }

            try {
                generator.stop();
            } catch (error) {
                console.warn('[webcodec.ts] generator.stop() warning:', error);
            }
        },
    };
}