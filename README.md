# WebRTC_v3

WebRTC_v3는 WebRTC_v2의 Socket.IO 기반 트리 릴레이 구조를 유지하면서, WebCodecs와 Insertable Streams를 이용한 영상 처리 실험을 추가한 버전입니다.

## v2와의 주요 차이점

| 항목 | WebRTC_v2 | WebRTC_v3 |
| --- | --- | --- |
| 로컬 영상 처리 | 화면 공유 트랙을 WebRTC로 릴레이 | 로컬 비디오를 WebCodecs `VideoEncoder → VideoDecoder` 왕복 처리 후 새 비디오 트랙 생성 |
| RTP encoded 처리 | 없음 | `RTCRtpSender.createEncodedStreams()`와 워커를 사용해 송신 encoded frame 처리 |
| 처리 모듈 | 기본 WebRTC 스트림 처리 | `webcodec_transform.ts`, `webcodec_insertable.ts`, `public/webcodec_insertable_worker.js` 추가 |
| 코덱 제어 | 기본 브라우저 협상 | VP9/VP8/H.264 선호 코덱 정책 및 송신 파이프라인 코덱 설정 지원 |
| 미디어 기본값 | 화면 공유 중심 | 카메라 `getUserMedia()` 기반 640×480, 30fps 처리 파이프라인 |
| 중계 노드 수용 인원 | 노드당 1명 | 노드당 최대 8명 |
| 시그널링 기본 포트 | 8000 | 8888 |

## 영상 처리 흐름

```text
getUserMedia()
  → MediaStreamTrackProcessor
  → VideoEncoder
  → VideoDecoder
  → MediaStreamTrackGenerator
  → processedStream
  → RTCPeerConnection.addTrack()
  → RTCRtpSender encoded Insertable Streams worker
```

`processedStream`은 로컬 미리보기와 다음 피어로의 릴레이에 사용됩니다. 오디오는 WebCodecs 왕복 처리 대상이 아니므로 원본 오디오 트랙을 유지합니다.

송신 sender에는 별도의 encoded Insertable Streams 워커가 연결됩니다. 워커는 WebCodecs가 생성한 encoded payload를 WebRTC 송신 encoded frame 경로에 적용합니다. 수신 측은 현재 passthrough 워커만 연결하며, 수신 encoded frame을 재구성하는 처리는 수행하지 않습니다.

## 실행

1. 루트 폴더에서 서버 의존성을 설치한 후 `npm start`를 실행합니다.
2. `peer_ts` 폴더에서 클라이언트 의존성을 설치한 후 `npm start`를 실행합니다.
3. `peer_ts/.env`와 `peer_ts/src/App.tsx`의 `SIGNALING_SERVER_URL`을 현재 HTTPS 서버 주소와 맞춥니다.

기본 시그널링 주소는 `https://192.168.1.50:8888`입니다. WebRTC 캡처와 Insertable Streams 사용을 위해 HTTPS 환경에서 실행하는 것을 권장합니다.

## 브라우저 요구 사항 및 참고

- Chromium 계열 브라우저에서 테스트하는 것을 권장합니다.
- `MediaStreamTrackProcessor`, `MediaStreamTrackGenerator`, `VideoEncoder`, `VideoDecoder` 및 encoded Insertable Streams 지원 여부를 확인해야 합니다.
- 브라우저 또는 코덱 조합에 따라 VP9/VP8/H.264 지원 여부가 다를 수 있습니다.
- encoded Insertable Streams의 독립 1:N 검증 프로젝트는 `webcodec_insertable_1n_test` 폴더에 있습니다.
