# WebRTC Android

안드로이드 네이티브 환경에서 WebRTC 화상 통화 및 화면 공유를 지원

## 주요 기능
* **하드웨어 가속 인코딩/디코딩**: `MediaCodec`을 활용하여 하드웨어 가속.
* **고성능 화면 캡처**: 시스템 레벨의 `MediaProjection` API를 통해 화면 공유 지원.
* **네이티브 카메라 연동**: `Camera2` API를 활용한 기기 카메라 캡처.
* **Socket.IO 시그널링**: WebRTC 시그널링 서버 프로토콜과 호환.

## 요구 사항
* Android 8.0 (API Level 26) 이상
* 카메라 및 마이크 접근 권한

## 주요 프로젝트 구조
* `MediaServerClient.kt`: Socket.IO 기반 시그널링 이벤트 처리 및 룸 관리.
* `NativeTransportManager.kt`: WebRTC PeerConnection 생성 및 미디어 트랙 제어.
* `HardwareEncoderConfig.kt`: 하드웨어 코덱(MediaCodec) 프로필 설정 및 해상도/프레임 제어.
* `ScreenCapturer.kt`: 화면 캡처 및 YUV 포맷 규격화 파이프라인.
* `TelemetryManager.kt`: 연결 상태 및 성능 지표(Stats) JSON 로깅.

## 빌드 및 실행 방법
1. Android Studio에서 프로젝트 열기.
2. 안드로이드 기기 연결, 빌드 및 실행.
3. 앱 최초 실행 시 요구되는 카메라 및 마이크 권한 허용
4. 시그널링 서버 주소 입력, 코덱 설정.
5. (안드로이드 기기가 송출자인 경우) 해상도, fps 옵션 설정. 화면 공유인 경우엔 화면 공유 시작 설정.
6. 서버 접속 버튼 누르기. 방에 접속되며 연결 시작.