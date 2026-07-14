'use strict';

const maxDumpCount = 20;
const waitMs = 120;
const maxQueueSize = 60;
let sendCount = 0;

const payloads = {
  key: [],
  delta: [],
};

const waiters = {
  key: [],
  delta: [],
};

self.onmessage = (event) => {
  const { operation } = event.data;

  if (operation === 'encode') {
    handleEncode(event.data.readable, event.data.writable);
  } else if (operation === 'webcodecEncodedChunk') {
    enqueuePayload(event.data);
  } else if (operation === 'clearWebCodecQueue') {
    clearPayloads();
  }
};

function handleEncode(readable, writable) {
  readable
    .pipeThrough(new TransformStream({ transform: encodeFrame }))
    .pipeTo(writable)
    .catch((error) => {
      console.error('[insertable_worker] encode pipe failed', error);
    });
}

async function encodeFrame(frame, controller) {
  const replacement = await takePayload(frame.type);

  if (replacement) {
    frame.data = replacement;

    if (sendCount < maxDumpCount || frame.type === 'key') {
      dump(frame, 'send-webcodec');
    }
  } else {
    if (frame.type === 'key') {
      self.postMessage({ operation: 'requestWebCodecKeyFrame' });
    }

    if (sendCount < maxDumpCount) {
      dump(frame, 'send-original');
    }
  }

  sendCount++;
  controller.enqueue(frame);
}

function enqueuePayload(message) {
  const type = message.type === 'key' ? 'key' : 'delta';
  const waiter = waiters[type].shift();

  if (waiter) {
    waiter.resolve(message.data);
    return;
  }

  payloads[type].push(message.data);

  while (payloads[type].length > maxQueueSize) {
    payloads[type].shift();
  }
}

function takePayload(type) {
  const queueType = type === 'key' ? 'key' : 'delta';
  const queue = payloads[queueType];

  if (queue.length > 0) {
    return Promise.resolve(queue.shift());
  }

  return new Promise((resolve) => {
    const waiter = { resolve };
    const timeoutId = setTimeout(() => {
      const list = waiters[queueType];
      const index = list.indexOf(waiter);

      if (index >= 0) {
        list.splice(index, 1);
      }

      resolve(null);
    }, waitMs);

    waiter.resolve = (data) => {
      clearTimeout(timeoutId);
      resolve(data);
    };
    waiters[queueType].push(waiter);
  });
}

function clearPayloads() {
  payloads.key.length = 0;
  payloads.delta.length = 0;

  for (const waiter of waiters.key.splice(0)) {
    waiter.resolve(null);
  }
  for (const waiter of waiters.delta.splice(0)) {
    waiter.resolve(null);
  }

  sendCount = 0;
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
    'ssrc=' + frame.synchronizationSource
  );
}
