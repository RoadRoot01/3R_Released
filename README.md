# WebRTC_v2

React/TypeScript 클라이언트와 Socket.IO 시그널링 서버로 구성한 WebRTC 릴레이 프로젝트입니다.

## 실행

1. 서버 의존성을 설치하고 루트 폴더에서 `npm start`를 실행합니다.
2. 클라이언트 의존성을 설치하고 `peer_ts` 폴더에서 `npm start`를 실행합니다.
3. 필요하면 `peer_ts/.env`와 `peer_ts/src/App.tsx`의 시그널링 서버 주소를 환경에 맞게 설정합니다.

## Insertable Streams — 한국어

로컬 화면 공유 스트림에만 `InsertableStreamsOverlay.tsx` 파이프라인을 적용합니다. 원격 수신 스트림과 `remoteVideo.tsx` 경로는 변경하지 않습니다.

- `MediaStreamTrackProcessor`가 로컬 비디오 프레임을 읽습니다.
- `OffscreenCanvas`가 프레임 좌측 상단에 빨간 네모를 합성합니다.
- `MediaStreamTrackGenerator`가 가공된 비디오 트랙을 생성하고, 오디오는 그대로 유지합니다.
- 가공 스트림을 `localStreamRef`에 저장합니다. 새 자식 피어는 이 트랙을 `addTrack()`으로 송신합니다.
- 이미 연결된 자식 피어는 `RTCRtpSender.replaceTrack()`으로 가공 비디오 트랙을 즉시 송신합니다.
- Insertable Streams 또는 `OffscreenCanvas`를 지원하지 않는 브라우저에서는 원본 스트림을 사용하여 기존 WebRTC 흐름을 유지합니다.

파이프라인 파일: `peer_ts/src/InsertableStreamsOverlay.tsx`

## Insertable Streams — English

The `InsertableStreamsOverlay.tsx` pipeline is applied only to the local screen-sharing stream. The remote receiving path and `remoteVideo.tsx` are not changed.

- `MediaStreamTrackProcessor` reads local video frames.
- `OffscreenCanvas` composites a red rectangle in the upper-left corner of every frame.
- `MediaStreamTrackGenerator` creates the processed video track while audio is passed through unchanged.
- The processed stream is stored in `localStreamRef`. New child peers send it through `addTrack()`.
- Existing child peers immediately switch to the processed video track through `RTCRtpSender.replaceTrack()`.
- If Insertable Streams or `OffscreenCanvas` is unsupported, the original stream is used so the existing WebRTC flow continues.

Pipeline file: `peer_ts/src/InsertableStreamsOverlay.tsx`
