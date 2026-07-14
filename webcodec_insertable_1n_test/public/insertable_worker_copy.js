'use strict';
// 업데이트 버전
// 항상 WebCodecs payload만 전송”이 아니라, WebCodecs payload가 준비된 경우에만 교체하고, 
// 부족하면 원본 WebRTC payload로 fallback
const maxDumpCount = 30;
const waitMs = 120;
const keyWaitMs = 500;
const maxQueueSize = 90;

let sendCount = 0;
let dropCount = 0;
let hasSentKey = false;
let payloadQueue = [];
let payloadWaiters = [];

self.onmessage = (event) => {
  const { operation } = event.data;

  if (operation === 'encode') {
    handleEncode(event.data.readable, event.data.writable);
  } else if (operation === 'webcodecEncodedChunk') {
    enqueuePayload(event.data);
  } else if (operation === 'clearWebCodecQueue') {
    resetState();
  }
};
// WebRTC encoded frame을 WebCodec encoded frame로 교체하는 작업은 encodeFrame에서 이뤄짐.
function handleEncode(readable, writable) {
  readable
    .pipeThrough(new TransformStream({ transform: encodeFrame }))
    .pipeTo(writable)
    .catch((error) => {
      console.error('[insertable_worker] encode pipe failed', error);
    });
}

async function encodeFrame(frame, controller) {
  const frameType = normalizeType(frame.type);
  const payload = await takePayloadForFrame(frameType);

  if (!payload || (!hasSentKey && payload.type !== 'key')) {
    hasSentKey = false;
    requestKeyFrames('payload-sync-needed');
    drop(frame, 'drop-underflow');
    return;
  }

  frame.data = payload.data;
  hasSentKey = hasSentKey || payload.type === 'key';

  if (sendCount < maxDumpCount || payload.type === 'key') {
    dump(frame, `send-webcodec-${payload.type}-in-${frameType}`);
  }

  sendCount++;
  controller.enqueue(frame);
}

function enqueuePayload(message) {
  const payload = {
    type: normalizeType(message.type),
    data: message.data,
    timestamp: message.timestamp,
    duration: message.duration,
    sequence: sendCount + payloadQueue.length,
  };
  const waiter = payloadWaiters.shift();

  if (waiter) {
    waiter.resolve(payload);
    return;
  }

  payloadQueue.push(payload);

  while (payloadQueue.length > maxQueueSize) {
    payloadQueue.shift();
    hasSentKey = false;
  }
}

async function takePayloadForFrame(frameType) {
  const requireKey = !hasSentKey || frameType === 'key';
  const deadline = performance.now() + (requireKey ? keyWaitMs : waitMs);
  let pendingPayload = null;

  while (performance.now() < deadline) {
    if (pendingPayload) {
      payloadQueue.push(pendingPayload);
      pendingPayload = null;
    }

    const payload = shiftUsablePayload(frameType, requireKey);

    if (payload) {
      return payload;
    }

    const remainingMs = Math.max(1, deadline - performance.now());
    const nextPayload = await waitForPayload(remainingMs);

    if (!nextPayload) {
      return null;
    }

    pendingPayload = nextPayload;
  }

  return null;
}

function shiftUsablePayload(frameType, requireKey) {
  while (payloadQueue.length > 0) {
    const payload = payloadQueue[0];

    if (requireKey && payload.type !== 'key') {
      payloadQueue.shift();
      continue;
    }

    if (!requireKey && payload.type === 'key') {
      return payloadQueue.shift();
    }

    if (!requireKey && frameType === 'delta' && payload.type === 'delta') {
      return payloadQueue.shift();
    }

    if (requireKey && payload.type === 'key') {
      return payloadQueue.shift();
    }

    return null;
  }

  return null;
}

function waitForPayload(timeoutMs) {
  return new Promise((resolve) => {
    const waiter = { resolve };
    const timeoutId = setTimeout(() => {
      const index = payloadWaiters.indexOf(waiter);

      if (index >= 0) {
        payloadWaiters.splice(index, 1);
      }

      resolve(null);
    }, timeoutMs);

    waiter.resolve = (payload) => {
      clearTimeout(timeoutId);
      resolve(payload);
    };

    payloadWaiters.push(waiter);
  });
}

function drop(frame, label) {
  dropCount++;

  if (dropCount <= maxDumpCount || normalizeType(frame.type) === 'key') {
    dump(frame, label);
  }
}

function requestKeyFrames(reason) {
  self.postMessage({
    operation: 'requestKeyFrames',
    reason,
  });
}

function resetState() {
  payloadQueue = [];

  for (const waiter of payloadWaiters.splice(0)) {
    waiter.resolve(null);
  }

  hasSentKey = false;
  sendCount = 0;
  dropCount = 0;
}

function normalizeType(type) {
  return type === 'key' ? 'key' : 'delta';
}

function dump(frame, label, max = 16) {
  const data = new Uint8Array(frame.data);
  let bytes = '';

  for (let i = 0; i < data.length && i < max; i++) {
    bytes += (data[i] < 16 ? '0' : '') + data[i].toString(16) + ' ';
  }

  console.log(
    performance.now().toFixed(2),
    label,
    bytes.trim(),
    'len=' + frame.data.byteLength,
    'type=' + frame.type,
    'ts=' + frame.timestamp,
    'ssrc=' + frame.synchronizationSource,
    'queue=' + payloadQueue.length,
    'drops=' + dropCount
  );
}
