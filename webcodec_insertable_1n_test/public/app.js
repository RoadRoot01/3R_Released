'use strict';

const pcConfig = {
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
};

const codecStrings = {
  vp8: 'vp8',
  vp9: 'vp09.00.10.08',
};

const codecMimeTypes = {
  vp8: 'video/VP8',
  vp9: 'video/VP9',
};

const socket = io();
const pcs = new Map();
const senderPipelines = new Map();
let role = null;
let myId = null;
let localStream = null;

const ui = {
  roomInput: document.getElementById('roomInput'),
  codecSelect: document.getElementById('codecSelect'),
  teacherButton: document.getElementById('teacherButton'),
  viewerButton: document.getElementById('viewerButton'),
  stopButton: document.getElementById('stopButton'),
  localVideo: document.getElementById('localVideo'),
  remoteVideo: document.getElementById('remoteVideo'),
  log: document.getElementById('log'),
};

ui.teacherButton.addEventListener('click', () => joinAsTeacher());
ui.viewerButton.addEventListener('click', () => joinAsViewer());
ui.stopButton.addEventListener('click', () => stopAll());

socket.on('connect', () => log('socket connected', socket.id));
socket.on('joined', (event) => {
  myId = event.id;
  role = event.role;
  log('joined', event);
});
socket.on('join-error', (event) => log('join-error', event.message));
socket.on('teacher-ready', (event) => log('teacher-ready', event.teacherId));
socket.on('teacher-left', () => {
  log('teacher-left');
  closeAllPeerConnections();
  ui.remoteVideo.srcObject = null;
});
socket.on('viewer-joined', ({ viewerId }) => {
  if (role === 'teacher') {
    void connectViewer(viewerId);
  }
});
socket.on('viewer-left', ({ viewerId }) => {
  log('viewer-left', viewerId);
  closePeer(viewerId);
});
socket.on('offer', ({ from, data }) => {
  if (role === 'viewer') {
    void handleOffer(from, data);
  }
});
socket.on('answer', ({ from, data }) => {
  const pc = pcs.get(from);
  if (pc) {
    void pc.setRemoteDescription(new RTCSessionDescription(data));
  }
});
socket.on('candidate', ({ from, data }) => {
  const pc = pcs.get(from);
  if (pc && data?.candidate) {
    void pc.addIceCandidate(new RTCIceCandidate(data.candidate)).catch((error) => {
      log('addIceCandidate failed', error.message);
    });
  }
});

// async function joinAsTeacher() {
//   await stopAll();
//   role = 'teacher';
//   localStream = await navigator.mediaDevices.getDisplayMedia({
//     video: {
//       width: { ideal: 360, max: 360 },
//       height: { ideal: 240, max: 240 },
//       frameRate: { ideal: 15, max: 15 },
//     },
//     audio: true,
//   });
//   ui.localVideo.srcObject = localStream;
//   socket.emit('join-1n', { roomId: roomId(), role: 'teacher' });
// }
async function joinAsTeacher() {
  await stopAll();
  role = 'teacher';
  localStream = await navigator.mediaDevices.getDisplayMedia({
    video: {
      width: { ideal: 640, max: 640 },
      height: { ideal: 480, max: 480 },
      frameRate: { ideal: 30, max: 30 },
    },
    audio: true,
  });
  ui.localVideo.srcObject = localStream;
  socket.emit('join-1n', { roomId: roomId(), role: 'teacher' });
}

async function joinAsViewer() {
  await stopAll();
  role = 'viewer';
  socket.emit('join-1n', { roomId: roomId(), role: 'viewer' });
}

async function connectViewer(viewerId) {
  if (!localStream) {
    log('no local stream for teacher');
    return;
  }

  closePeer(viewerId);
  const pc = createPeerConnection(viewerId);
  pcs.set(viewerId, pc);

  for (const track of localStream.getTracks()) {
    const sender = pc.addTrack(track, localStream);

    if (track.kind === 'video') {
      preferCodec(pc, sender, selectedCodec());
      // WebRTC Encoded Insertable Streams API
      // 이 API는 RTCRtpSender가 WebRTC 전송 직전에 만든 encoded video frame 스트림을 꺼냄. 
      // 그 다음 worker로 readable, writable을 넘김
      // const pipeline = await setupWebCodecSenderTransform(sender, localStream, {
      //   codec: selectedCodec(),
      //   width: 360,
      //   height: 240,
      //   framerate: 15,
      //   bitrate: 120_000,
      //   keyFrameIntervalSeconds: 0,
      // });
      const pipeline = await setupWebCodecSenderTransform(sender, localStream, {
        codec: selectedCodec(),
        width: 640,
        height: 480,
        framerate: 30,
        bitrate: 1_200_000,
        keyFrameIntervalSeconds: 0,
      });
      senderPipelines.set(viewerId, pipeline);
    }
  }

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  socket.emit('offer', { to: viewerId, data: pc.localDescription });
  log('offer sent', viewerId, selectedCodec());
}

async function handleOffer(teacherId, offer) {
  closePeer(teacherId);
  const pc = createPeerConnection(teacherId);
  pcs.set(teacherId, pc);

  pc.ontrack = (event) => {
    if (!ui.remoteVideo.srcObject) {
      ui.remoteVideo.srcObject = event.streams[0];
      log('remote stream attached');
    }
  };

  await pc.setRemoteDescription(new RTCSessionDescription(offer));
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  socket.emit('answer', { to: teacherId, data: pc.localDescription });
  log('answer sent', teacherId);
}

function createPeerConnection(peerId) {
  const pc = new RTCPeerConnection(pcConfig);

  pc.onicecandidate = (event) => {
    if (event.candidate) {
      socket.emit('candidate', {
        to: peerId,
        data: { candidate: event.candidate },
      });
    }
  };
  pc.onconnectionstatechange = () => {
    log(`pc ${peerId} state`, pc.connectionState);
    if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
      closePeer(peerId);
    }
  };

  return pc;
}

function preferCodec(pc, sender, codec) {
  const transceiver = pc.getTransceivers().find((item) => item.sender === sender);
  const capabilities = RTCRtpReceiver.getCapabilities('video');
  const codecList = capabilities?.codecs?.filter((item) => item.mimeType === codecMimeTypes[codec]) || [];

  if (!transceiver || codecList.length === 0 || typeof transceiver.setCodecPreferences !== 'function') {
    log('codec preference skipped', codec);
    return;
  }

  transceiver.setCodecPreferences(codecList);
  log('codec preference set', codecMimeTypes[codec]);
}

async function setupWebCodecSenderTransform(sender, stream, options) {
  if (sender.track?.kind !== 'video') {
    return null;
  }

  if (typeof sender.createEncodedStreams !== 'function') {
    log('RTCRtpSender.createEncodedStreams is not supported');
    return null;
  }

  const worker = new Worker('./insertable_worker.js', {
    name: 'WebCodecs payload replacement worker',
  });
  const { readable, writable } = sender.createEncodedStreams();
  const onWorkerKeyFrameRequest = (event) => {
    if (event.data?.operation !== 'requestKeyFrames') {
      return;
    }

    log('worker requested keyframes', event.data.reason);

    if (typeof sender.generateKeyFrame === 'function') {
      sender.generateKeyFrame().catch((error) => {
        log('sender.generateKeyFrame failed', error.message);
      });
    }
  };

  worker.addEventListener('message', onWorkerKeyFrameRequest);

  worker.postMessage({
    operation: 'encode',
    readable,
    writable,
  }, [readable, writable]);

  const payloadEncoder = await startWebCodecPayloadEncoder(stream, worker, options);

  if (!payloadEncoder) {
    worker.terminate();
    return null;
  }

  log('sender insertable pipeline attached', options.codec);

  return {
    stop() {
      worker.removeEventListener('message', onWorkerKeyFrameRequest);
      payloadEncoder.stop();
      worker.terminate();
    },
  };
}

async function startWebCodecPayloadEncoder(stream, worker, options) {
  if (!('VideoEncoder' in window) || !('MediaStreamTrackProcessor' in window)) {
    log('WebCodecs payload encoder is not supported');
    return null;
  }

  const sourceTrack = stream.getVideoTracks()[0];

  if (!sourceTrack) {
    log('no video track for payload encoder');
    return null;
  }

  const track = sourceTrack.clone();
  const settings = sourceTrack.getSettings();
  const config = {
    codec: codecStrings[options.codec],
    width: options.width || settings.width || 640,
    height: options.height || settings.height || 480,
    framerate: options.framerate || settings.frameRate || 30,
    bitrate: options.bitrate || 1_200_000,
    latencyMode: 'realtime',
    hardwareAcceleration: 'prefer-software',
  };
  const support = await VideoEncoder.isConfigSupported(config);

  if (!support.supported) {
    log('VideoEncoder config unsupported', config);
    track.stop();
    return null;
  }

  const processor = new MediaStreamTrackProcessor({ track });
  const reader = processor.readable.getReader();
  const keyFrameInterval = options.keyFrameIntervalSeconds > 0
    ? Math.max(1, Math.floor(config.framerate * options.keyFrameIntervalSeconds))
    : 0;
  let stopped = false;
  let frameIndex = 0;
  let forceKeyFrame = true;

  // 인코딩 결과 EncodedVideoChunk는 output(chunk)에서 worker로 전달
  const encoder = new VideoEncoder({
    output(chunk) {
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
    error(error) {
      log('VideoEncoder error', error.message);
    },
  });

  const onWorkerMessage = (event) => {
    if (
      event.data?.operation === 'requestWebCodecKeyFrame'
      || event.data?.operation === 'requestKeyFrames'
    ) {
      forceKeyFrame = true;
    }
  };

  worker.addEventListener('message', onWorkerMessage);
  worker.postMessage({ operation: 'clearWebCodecQueue' });
  encoder.configure(support.config || config);
  log('WebCodecs encoder configured', support.config || config);
  void pump();

  async function pump() {
    while (!stopped) {
      const { done, value: frame } = await reader.read();

      if (done || !frame) {
        break;
      }

      frameIndex++;

      try {
        encoder.encode(frame, {
          keyFrame: forceKeyFrame || (keyFrameInterval > 0 && frameIndex % keyFrameInterval === 1),
        });
        forceKeyFrame = false;
      } catch (error) {
        log('encode failed', error.message);
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
      reader.cancel().catch(() => {});
      track.stop();

      if (encoder.state !== 'closed') {
        encoder.close();
      }
    },
  };
}

function closePeer(peerId) {
  senderPipelines.get(peerId)?.stop();
  senderPipelines.delete(peerId);

  const pc = pcs.get(peerId);

  if (pc) {
    pc.onicecandidate = null;
    pc.ontrack = null;
    pc.close();
    pcs.delete(peerId);
  }
}

function closeAllPeerConnections() {
  for (const peerId of [...pcs.keys()]) {
    closePeer(peerId);
  }
}

async function stopAll() {
  closeAllPeerConnections();

  if (localStream) {
    localStream.getTracks().forEach((track) => track.stop());
    localStream = null;
  }

  ui.localVideo.srcObject = null;
  ui.remoteVideo.srcObject = null;
}

function selectedCodec() {
  return ui.codecSelect.value;
}

function roomId() {
  return ui.roomInput.value.trim() || 'insertable-test';
}

function log(...items) {
  const line = items.map((item) => {
    if (typeof item === 'string') {
      return item;
    }
    try {
      return JSON.stringify(item);
    } catch {
      return String(item);
    }
  }).join(' ');

  console.log('[1N]', ...items);
  ui.log.textContent = `${new Date().toLocaleTimeString()} ${line}\n${ui.log.textContent}`;
}
