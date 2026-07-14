// src/webcodec.ts

/*
  목적:
  getUserMedia() 또는 getDisplayMedia()로 얻은 MediaStream에서
  video track만 꺼내서

  MediaStreamTrackProcessor
    -> VideoFrame 단위 가공
    -> MediaStreamTrackGenerator
    -> 새 MediaStream

  형태로 다시 만들어 WebRTC pc.addTrack()에 넣을 수 있게 한다.

  주의:
  여기서는 VideoEncoder / VideoDecoder를 직접 사용하지 않는다.
  즉, WebRTC RTP 송출용 인코딩은 기존처럼 RTCPeerConnection이 담당한다.
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

export interface WebCodecPipelineHandle {
  processedStream: MediaStream;
  processedVideoTrack: MediaStreamTrack;
  originalVideoTrack: MediaStreamTrack;
  abort: () => void;
}

/**
 * 브라우저가 MediaStreamTrackProcessor / MediaStreamTrackGenerator를 지원하는지 확인
 */
export function isWebCodecStreamPipelineSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.MediaStreamTrackProcessor !== 'undefined' &&
    typeof window.MediaStreamTrackGenerator !== 'undefined' &&
    typeof TransformStream !== 'undefined' &&
    typeof VideoFrame !== 'undefined' &&
    typeof OffscreenCanvas !== 'undefined'
  );
}

/**
 * MediaStream의 video track만 WebCodecs Insertable Streams 방식으로 가공한다.
 *
 * 입력:
 *   rawStream = getUserMedia() 또는 getDisplayMedia() 결과
 *
 * 출력:
 *   processedStream = video는 가공된 track, audio는 원본 audio track 유지
 */
export function createWebCodecProcessedStream(
  rawStream: MediaStream
): WebCodecPipelineHandle {
  if (!isWebCodecStreamPipelineSupported()) {
    throw new Error(
      '[webcodec.ts] This browser does not support MediaStreamTrackProcessor / MediaStreamTrackGenerator.'
    );
  }

  const videoTrack = rawStream.getVideoTracks()[0];

  if (!videoTrack) {
    throw new Error('[webcodec.ts] No video track found in rawStream.');
  }

  const Processor = window.MediaStreamTrackProcessor!;
  const Generator = window.MediaStreamTrackGenerator!;

  const processor = new Processor({ track: videoTrack });
  const generator = new Generator('video');

  const abortController = new AbortController();

  let canvas: OffscreenCanvas | null = null;
  let ctx: OffscreenCanvasRenderingContext2D | null = null;
  let frameCount = 0;

  const transformer = new TransformStream<VideoFrame, VideoFrame>({
    async transform(frame, controller) {
      frameCount++;

      try {
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
          2. 테스트용 프레임 가공 부분
             여기만 바꾸면 원하는 영상처리를 테스트할 수 있음.
        */
        ctx!.fillStyle = 'rgba(255, 0, 0, 0.65)';
        ctx!.fillRect(10, 10, Math.min(220, width - 20), Math.min(70, height - 20));

        ctx!.fillStyle = 'white';
        ctx!.font = '20px sans-serif';
        ctx!.fillText('WebCodec Pipeline', 20, 42);
        ctx!.font = '14px sans-serif';
        ctx!.fillText(`frame: ${frameCount}`, 20, 64);

        /*
          3. canvas 결과를 다시 VideoFrame으로 생성
             timestamp를 원본 frame.timestamp로 유지해야 WebRTC 쪽에서 시간 흐름이 자연스러움.
        */
        const processedFrame = new VideoFrame(canvas!, {
          timestamp: frame.timestamp,
          duration: frame.duration ?? undefined,
        });

        /*
          4. 다음 단계(MediaStreamTrackGenerator)로 전달
        */
        controller.enqueue(processedFrame);
      } catch (error) {
        console.error('[webcodec.ts] frame transform failed:', error);
      } finally {
        /*
          원본 frame은 반드시 close().
          close하지 않으면 메모리/그래픽 리소스가 계속 쌓일 수 있음.
        */
        frame.close();
      }
    },

    flush() {
      console.log('[webcodec.ts] TransformStream flushed.');
    },
  });

  processor.readable
    .pipeThrough(transformer, { signal: abortController.signal })
    .pipeTo(generator.writable)
    .catch((error) => {
      if (abortController.signal.aborted) {
        console.log('[webcodec.ts] Pipeline aborted normally.');
      } else {
        console.error('[webcodec.ts] Pipeline error:', error);
      }
    });

  /*
    새 MediaStream 구성:
    - video: 가공된 generator track
    - audio: 원본 rawStream의 audio track 그대로 추가

    이렇게 해야 기존 App.tsx의 pc.addTrack(track, localStreamRef.current!) 구조를 거의 그대로 유지 가능.
  */
  const processedStream = new MediaStream();

  processedStream.addTrack(generator);

  rawStream.getAudioTracks().forEach((audioTrack) => {
    processedStream.addTrack(audioTrack);
  });

  console.log('[webcodec.ts] Processed MediaStream created:', {
    rawVideoTrack: videoTrack,
    processedVideoTrack: generator,
    audioTracks: rawStream.getAudioTracks(),
    processedStream,
  });

  return {
    processedStream,
    processedVideoTrack: generator,
    originalVideoTrack: videoTrack,
    abort: () => {
      console.log('[webcodec.ts] Aborting WebCodec stream pipeline.');
      abortController.abort();

      try {
        generator.stop();
      } catch (error) {
        console.warn('[webcodec.ts] generator.stop() warning:', error);
      }
    },
  };
}