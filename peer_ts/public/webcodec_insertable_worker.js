'use strict';

if (self.RTCTransformEvent) {
  self.onrtctransform = (event) => {
    const transformer = event.transformer;
    handleTransform(transformer.options.operation, transformer.readable, transformer.writable);
  };
}

self.onmessage = (event) => {
  const operation = event.data.operation;

  if (operation === 'webcodecEncodedChunk') {
    enqueueWebCodecPayload(event.data);
  } else if (operation === 'clearWebCodecQueue') {
    clearWebCodecPayloads();
  } else if (operation === 'encode' || operation === 'decode') {
    handleTransform(operation, event.data.readable, event.data.writable);
  }
};

const maxDumpCount = 10;
const webCodecPayloadWaitMs = 120;
const maxWebCodecPayloadsPerType = 30;

let sendDumpCount = 0;
let recvDumpCount = 0;

const webCodecPayloads = {
  key: [],
  delta: [],
};

const webCodecPayloadWaiters = {
  key: [],
  delta: [],
};

function handleTransform(operation, readable, writable) {
  const transformStream = new TransformStream({
    transform: operation === 'encode' ? encodeFunction : decodeFunction,
  });

  readable
    .pipeThrough(transformStream)
    .pipeTo(writable)
    .catch((error) => {
      console.error('[webcodec_insertable_worker] transform failed:', operation, error);
    });
}

async function encodeFunction(chunk, controller) {
  if (sendDumpCount++ < maxDumpCount) {
    dump(chunk, 'send');
  }

  if (chunk.type === 'key' || chunk.type === 'delta') {
    const encodedData = await takeWebCodecPayload(chunk.type);

    if (encodedData) {
      chunk.data = encodedData;

      if (sendDumpCount <= maxDumpCount || chunk.type === 'key') {
        dump(chunk, 'send-webcodec');
      }
    } else {
      requestWebCodecKeyFrameIfNeeded(chunk.type);
    }
  }

  controller.enqueue(chunk);
}

function decodeFunction(chunk, controller) {
  if (recvDumpCount++ < maxDumpCount) {
    dump(chunk, 'recv');
  }

  controller.enqueue(chunk);
}

function enqueueWebCodecPayload(message) {
  const type = message.type === 'key' ? 'key' : 'delta';
  const payload = {
    data: message.data,
    timestamp: message.timestamp,
    duration: message.duration,
  };
  const waiter = webCodecPayloadWaiters[type].shift();

  if (waiter) {
    waiter.resolve(payload.data);
    return;
  }

  webCodecPayloads[type].push(payload);

  while (webCodecPayloads[type].length > maxWebCodecPayloadsPerType) {
    webCodecPayloads[type].shift();
  }
}

function takeWebCodecPayload(type) {
  const queue = webCodecPayloads[type];

  if (queue && queue.length > 0) {
    return Promise.resolve(queue.shift().data);
  }

  return new Promise((resolve) => {
    const waiter = { resolve };
    const timeoutId = setTimeout(() => {
      const waiters = webCodecPayloadWaiters[type];
      const index = waiters.indexOf(waiter);

      if (index >= 0) {
        waiters.splice(index, 1);
      }

      resolve(null);
    }, webCodecPayloadWaitMs);

    waiter.resolve = (data) => {
      clearTimeout(timeoutId);
      resolve(data);
    };
    webCodecPayloadWaiters[type].push(waiter);
  });
}

function clearWebCodecPayloads() {
  webCodecPayloads.key.length = 0;
  webCodecPayloads.delta.length = 0;

  for (const waiter of webCodecPayloadWaiters.key.splice(0)) {
    waiter.resolve(null);
  }
  for (const waiter of webCodecPayloadWaiters.delta.splice(0)) {
    waiter.resolve(null);
  }

  sendDumpCount = 0;
  recvDumpCount = 0;
}

function requestWebCodecKeyFrameIfNeeded(type) {
  if (type !== 'key') {
    return;
  }

  self.postMessage({
    operation: 'requestWebCodecKeyFrame',
  });
}

function dump(chunk, direction, max = 16) {
  const data = new Uint8Array(chunk.data);
  let bytes = '';

  for (let i = 0; i < data.length && i < max; i++) {
    bytes += (data[i] < 16 ? '0' : '') + data[i].toString(16) + ' ';
  }

  console.log(
    performance.now().toFixed(2),
    direction,
    bytes.trim(),
    'len=' + chunk.data.byteLength,
    'type=' + (chunk.type || 'audio'),
    'ts=' + chunk.timestamp,
    'ssrc=' + chunk.synchronizationSource
  );
}
