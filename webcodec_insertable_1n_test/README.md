# WebCodecs Insertable 1:N Test

Minimal test project for replacing `RTCRtpSender` encoded frame `data` with WebCodecs `EncodedVideoChunk` bytes.

## Shape

- Teacher: `getUserMedia` -> `RTCRtpSender.createEncodedStreams()` -> worker replaces `RTCEncodedVideoFrame.data`.
- Viewer: receive-only WebRTC peer. No receiver-side Insertable Streams are created.
- Signaling: tiny Socket.IO server in this folder.
- Default codec: VP8.
- VP9 option: choose VP9 in the page before joining.

## Anti-Corruption Rules

The worker intentionally avoids mixing the browser WebRTC encoder stream and the side WebCodecs encoder stream:

- Original WebRTC encoded frames are never used as fallback.
- Frames are dropped until a WebCodecs keyframe can be inserted into a WebRTC keyframe.
- Payloads are kept in one ordered queue, not separate key/delta queues.
- Queue underflow or key/delta mismatch resets the pipeline to keyframe-wait mode.
- The worker asks both the WebCodecs encoder and, when available, `RTCRtpSender.generateKeyFrame()` for a fresh keyframe.

## Run

```powershell
cd C:\VScode\3RInnovation\webRTC_1toN_Mesh\WebRTC_v3\webcodec_insertable_1n_test
npm.cmd start
```

Open:

- `https://localhost:8443`
- `https://<server-lan-ip>:8443`
- If cert files are unavailable, the server falls back to `http://localhost:8080`.

When the server starts, it prints available LAN URLs:

```text
[1N] lan:   https://192.168.x.x:8443
```

Use that URL from external browser clients on the same network. For external clients, prefer HTTPS; browser capture and WebRTC APIs may be blocked on plain HTTP except localhost.

Use one tab or device as Teacher and one or more tabs/devices as Viewer in the same room.

## Why Receiver Transform Is Removed

The previous error:

```text
Failed to execute 'createEncodedStreams' on 'RTCRtpReceiver': Too late to create encoded streams
```

happens because receiver encoded streams must be created before the receiver starts receiving frames. This test does not need receiver encoded access, so the viewer path intentionally avoids `RTCRtpReceiver.createEncodedStreams()`.
