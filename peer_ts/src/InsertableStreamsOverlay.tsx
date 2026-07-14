/**
 * <DH> 인코딩 전 VideoFrame에 빨간 네모를 합성하는 Insertable Streams 파이프라인임.
 * <DH> MediaStreamTrackProcessor로 원본 프레임 읽고 Generator로 가공 트랙 생성함.
 */

export interface RedBoxOverlayPipeline {
    stream: MediaStream;
    stop: () => void;
}

const RED_BOX_MARGIN = 16;
const RED_BOX_WIDTH = 96;
const RED_BOX_HEIGHT = 64;

/**
 * <DH> 좌측 상단 빨간 네모가 합성된 비디오 트랙으로 새 MediaStream 생성함.
 * <DH> 오디오와 비디오 외 트랙은 원본 그대로 전달함.
 * <DH> 브라우저 미지원 시 기존 WebRTC 흐름 유지하도록 원본 스트림 반환함.
 */
export function createRedBoxOverlayStream(sourceStream: MediaStream): RedBoxOverlayPipeline {
    const videoTrack = sourceStream.getVideoTracks()[0];
    const Processor = (window as any).MediaStreamTrackProcessor;
    const Generator = (window as any).MediaStreamTrackGenerator;

    if (!videoTrack || !Processor || !Generator || typeof OffscreenCanvas === 'undefined') {
        console.warn('[Insertable Streams] VideoFrame processing is not supported; sending the original stream.');
        return { stream: sourceStream, stop: () => undefined };
    }

    const processor = new Processor({ track: videoTrack });
    const generator = new Generator({ kind: 'video' });
    const abortController = new AbortController();
    let stopped = false;

    // <DH> 원본 프레임을 캔버스에 그리고 좌측 상단에 빨간 네모 합성함.
    const transform = new TransformStream({
        transform: (frame: any, controller) => {
            const width = frame.displayWidth || frame.codedWidth;
            const height = frame.displayHeight || frame.codedHeight;
            const canvas = new OffscreenCanvas(width, height);
            const context = canvas.getContext('2d') as OffscreenCanvasRenderingContext2D | null;

            if (!context) {
                frame.close();
                return;
            }

            context.drawImage(frame, 0, 0, width, height);
            context.fillStyle = '#ff0000';
            context.fillRect(RED_BOX_MARGIN, RED_BOX_MARGIN, RED_BOX_WIDTH, RED_BOX_HEIGHT);

            const compositedFrame = new (window as any).VideoFrame(canvas, {
                timestamp: frame.timestamp,
                duration: frame.duration,
            });
            frame.close();
            controller.enqueue(compositedFrame);
        },
    });

    // <DH> Processor → Transform → Generator 순서로 프레임 가공 파이프라인 연결함.
    processor.readable
        .pipeThrough(transform, { signal: abortController.signal })
        .pipeTo(generator.writable, { signal: abortController.signal })
        .catch((error: unknown) => {
            if (!stopped) {
                console.error('[Insertable Streams] Red-box overlay pipeline failed.', error);
            }
        });

    const processedStream = new MediaStream([
        generator,
        ...sourceStream.getAudioTracks(),
        ...sourceStream.getTracks().filter(track => track.kind !== 'video' && track.kind !== 'audio'),
    ]);
    // <DH> 수신 지연된 오디오 트랙도 가공 스트림에 추가하여 릴레이 오디오 유지함.
    const addPassthroughTrack = (event: MediaStreamTrackEvent) => {
        if (event.track.kind !== 'video' && !processedStream.getTrackById(event.track.id)) {
            processedStream.addTrack(event.track);
        }
    };
    sourceStream.addEventListener('addtrack', addPassthroughTrack);

    return {
        stream: processedStream,
        stop: () => {
            if (stopped) return;
            stopped = true;
            // <DH> 스트림 교체 또는 컴포넌트 종료 시 파이프라인과 캡처 리소스 정리함.
            abortController.abort();
            sourceStream.removeEventListener('addtrack', addPassthroughTrack);
            generator.stop();
            sourceStream.getTracks().forEach(track => track.stop());
        },
    };
}
