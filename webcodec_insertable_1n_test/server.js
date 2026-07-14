const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const os = require('os');
const express = require('express');
const { Server } = require('socket.io');

const app = express();
const publicDir = path.join(__dirname, 'public');
const rooms = new Map();
const sockets = new Map();

app.use(express.static(publicDir));

function getRoom(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, {
      teacherId: null,
      viewers: new Set(),
    });
  }
  return rooms.get(roomId);
}

function createServer() {
  const certPath = 'C:\\Windows\\System32\\cert.pem';
  const keyPath = 'C:\\Windows\\System32\\key.pem';

  if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
    return {
      server: https.createServer({
        cert: fs.readFileSync(certPath),
        key: fs.readFileSync(keyPath),
      }, app),
      protocol: 'https',
      port: Number(process.env.PORT || 8443),
    };
  }

  return {
    server: http.createServer(app),
    protocol: 'http',
    port: Number(process.env.PORT || 8080),
  };
}

const { server, protocol, port } = createServer();
const listenHost = process.env.HOST || '0.0.0.0';
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
  },
});

io.on('connection', (socket) => {
  sockets.set(socket.id, socket);
  console.log('[1N] connected', socket.id);

  socket.on('join-1n', ({ roomId, role }) => {
    const room = getRoom(roomId);
    socket.data.roomId = roomId;
    socket.data.role = role;
    socket.join(roomId);

    if (role === 'teacher') {
      if (room.teacherId && room.teacherId !== socket.id) {
        socket.emit('join-error', { message: 'Teacher already exists in this room.' });
        return;
      }

      room.teacherId = socket.id;
      socket.emit('joined', { id: socket.id, role, roomId });
      socket.to(roomId).emit('teacher-ready', { teacherId: socket.id });

      for (const viewerId of room.viewers) {
        socket.emit('viewer-joined', { viewerId });
      }

      console.log('[1N] teacher joined', roomId, socket.id);
      return;
    }

    room.viewers.add(socket.id);
    socket.emit('joined', {
      id: socket.id,
      role,
      roomId,
      teacherId: room.teacherId,
    });

    if (room.teacherId) {
      sockets.get(room.teacherId)?.emit('viewer-joined', { viewerId: socket.id });
    }

    console.log('[1N] viewer joined', roomId, socket.id);
  });

  socket.on('offer', ({ to, data }) => {
    sockets.get(to)?.emit('offer', { from: socket.id, data });
  });

  socket.on('answer', ({ to, data }) => {
    sockets.get(to)?.emit('answer', { from: socket.id, data });
  });

  socket.on('candidate', ({ to, data }) => {
    sockets.get(to)?.emit('candidate', { from: socket.id, data });
  });

  socket.on('disconnect', () => {
    const roomId = socket.data.roomId;
    const role = socket.data.role;
    sockets.delete(socket.id);

    if (!roomId || !rooms.has(roomId)) {
      return;
    }

    const room = rooms.get(roomId);

    if (role === 'teacher' && room.teacherId === socket.id) {
      room.teacherId = null;
      io.to(roomId).emit('teacher-left');
    } else if (role === 'viewer') {
      room.viewers.delete(socket.id);
      if (room.teacherId) {
        sockets.get(room.teacherId)?.emit('viewer-left', { viewerId: socket.id });
      }
    }

    if (!room.teacherId && room.viewers.size === 0) {
      rooms.delete(roomId);
    }

    console.log('[1N] disconnected', socket.id);
  });
});

function getLanIPv4Addresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((item) => item && item.family === 'IPv4' && !item.internal)
    .map((item) => item.address);
}

server.listen(port, listenHost, () => {
  const lanUrls = getLanIPv4Addresses().map((address) => `${protocol}://${address}:${port}`);

  console.log(`[1N] ${protocol} server listening on ${listenHost}:${port}`);
  console.log(`[1N] local: ${protocol}://localhost:${port}`);

  for (const url of lanUrls) {
    console.log(`[1N] lan:   ${url}`);
  }

  if (protocol !== 'https') {
    console.warn('[1N] Warning: external browsers usually need HTTPS for getDisplayMedia/WebRTC APIs.');
  }
});
