/*
기존에 가지고 있던 타입스크립트 WebRTC 화상 통화 프로그램을 이용해서 안드로이드 '네이티브' WebRTC를 이용하도록 포팅해보자.

기존 App.tsx 파일은 아래와 같다.
```
import React,{use,useCallback,useEffect,useRef,useState}from'react';import'./App.css';import io from'socket.io-client';import Video from'./component/remoteVideo';export interface WebRTCUser{id:string;socket:SocketIOClient.Socket;stream:MediaStream}typeBitrateLevel='min'|'medium'|'max';const BitrateConfig:Record<BitrateLevel, number>={min:50000,medium:1000000,max:2000000,};const BITRATE:number=50000;const MAX_REDIAL_ATTEMPTS=2;const displayMediaOptions={video:{displaySurface:"monitor",},audio:{suppressLocalAudioPlayback:false,},preferCurrentTab:false,selfBrowserSurface:"exclude",systemAudio:"include",surfaceSwitching:"include",monitorTypeSurfaces:"include",};const constraints={video:{width:{ideal:1920,max:1920},height:{ideal:1080,max:1080},frameRate:{ideal:60,max:60}},audio:true};export const SIGNALING_SERVER_URL='https://192.168.0.26:8000';const pcConfig:RTCConfiguration={iceServers:[{urls:['stun:stun.l.google.com:19302',]}]};const config={};function App(){console.log('Rendering... ');let changeCount=0;const room='testRoom';const socketRef=useRef<SocketIOClient.Socket|null>(null);const pcsRef=useRef<Record<string,RTCPeerConnection>>({});const redialCountsRef=useRef<Record<string,number>>({});const pendingCandRef=useRef<Record<string,RTCIceCandidate[]>>({});const iceCandidateGatheredArrayRef=useRef<Record<string,RTCIceCandidate[]>>({});const localStreamRef=useRef<MediaStream>(null);const localVideoRef=useRef<HTMLVideoElement>(null);const myidRef=useRef<string>('');const localStreamSortRef=useRef<string>('userMedia');const hasStreamChangedRef=useRef<boolean>(false);const pcTypesRef=useRef<Record<string,string>>({});const[users,setUsers]=useState<WebRTCUser[]>([]);const[myid,setMyid]=useState<string>('');const[notice,setNotice]=useState<string>('');const noticeTimerRef=useRef<number|null>(null);const isRootRef=useRef<boolean>(false);const forceDisconnectPeerRef=useRef<(peerId:string)=>boolean>(()=>false);const TIMEOUT_DURATION=0;const setVideoBitrate=useCallback(async(peerId:string,bitrate:number)=>{const pc=pcsRef.current[peerId];if(!pc){console.error(`[Peer] PeerConnection for ${peerId} not found.`);return}const senders=pc.getSenders();const videoSender=senders.find(sender=>sender.track?.kind==='video');if(videoSender){try{const parameters=videoSender.getParameters();console.log(`[Peer] ${peerId} senderParameters1 : `,parameters);if(!parameters.encodings||parameters.encodings.length===0){parameters.encodings=[{}];console.log('[Peer] ${peerId} senderParameters2 : ',parameters)}parameters.encodings[0].maxBitrate=bitrate;parameters.degradationPreference='maintain-resolution';await videoSender.setParameters(parameters);console.log(`[Peer] Video bitrate for ${peerId} set to ${bitrate/1000}bps.`)}catch(e){console.error(`[Peer] Failed to set video bitrate for ${peerId}:`,e)}}else{console.warn(`[Peer] No video sender found for ${peerId}.`)}},[]);function setMaxBandwidth(sdp:string,mediaType:string,maxbps:number):string{console.log(`[SDP] Setting max bandwidth for ${mediaType} to ${maxbps} bps`);if(!sdp){console.warn('[SDP] No SDP provided');return sdp}const lines:string[]=sdp.split('\r\n');const newSdp:string[]=[];const mediaPattern=`m=${mediaType}`;let insideTargetMediaSection:boolean=false;for(const lineof lines){newSdp.push(line);if(line.startsWith('m=')){insideTargetMediaSection=line.startsWith(mediaPattern);if(insideTargetMediaSection){newSdp.push(`b=AS:${maxbps}`);insideTargetMediaSection=false}}}return newSdp.join('\r\n')}const getLocalStream=useCallback(async()=>{try{console.log('getLocalStream....');localStreamRef.current=await navigator.mediaDevices.getDisplayMedia(constraints);if(localVideoRef.current){localVideoRef.current.srcObject=localStreamRef.current}}catch(error){console.error('Error accessing media devices.',error)}},[]);const changeStream=useCallback(async()=>{if(localStreamSortRef.current==='userMedia'){console.log(`[Peer] Current stream is not a display source. Changing stream...`);localStreamRef.current=await navigator.mediaDevices.getDisplayMedia(constraints);if(localVideoRef.current){localVideoRef.current.srcObject=localStreamRef.current}Object.values(pcsRef.current).forEach(pc=>{const senders=pc.getSenders();localStreamRef.current!.getTracks().forEach(track=>{const sender=senders.find(s=>s.track?.kind===track.kind);if(sender){sender.replaceTrack(track)}})})}else{console.log(`[Peer] Current stream is already a display source. Skipping changeStream.`)}localStreamSortRef.current='displayMedia'},[]);useEffect(()=>{socketRef.current=io.connect(SIGNALING_SERVER_URL,{autoConnect:false});socketRef.current?.connect();console.log('Local stream obtained:',localStreamRef.current);socketRef.current.on('connect',()=>{console.log('[Peer] Connected to signaling server');socketRef.current?.emit('join',{room:room,type:'broadcast'})});socketRef.current.on('root-broadcaster',()=>{isRootRef.current=true;console.log('[Peer] I am the root broadcaster in the room.');getLocalStream();});socketRef.current.on('my-id',(id:string)=>{console.log('[Peer] My ID:',id);myidRef.current=id;setMyid(id);console.log('My ID set to state:',myidRef.current)});socketRef.current.on('new-parent',async(parentid:string)=>{console.log('[Peer] my Parent in room:',parentid);if(pcsRef.current[parentid]){console.warn(`[Peer] Connection to ${parentid} already exists. Skipping duplicate existing-peers event.`);return;}const pc=createPeerConnection(parentid,'recvonly');pcsRef.current[parentid]=pc;const offer=await pc.createOffer();await pc.setLocalDescription(offer);socketRef.current?.emit('offer',{to:parentid,data:offer})});socketRef.current.on('offer',async({from,data}:{from:string,data:any})=>{console.log(`[Peer] Received offer from ${from}`,data);pcsRef.current[from]=createPeerConnection(from,'sendonly');const pc=pcsRef.current[from];await pc.setRemoteDescription(new RTCSessionDescription(data));await flushPendingCandidates(from);const answer=await pc.createAnswer();let finalSdp:any=answer;if(isRootRef.current){const newSdp=setMaxBandwidth(answer.sdp||'','video',BITRATE);finalSdp=newSdp?{type:answer.type,sdp:newSdp}:answer}await pc.setLocalDescription(finalSdp);socketRef.current?.emit('answer',{to:from,data:finalSdp});});socketRef.current.on('answer',async({from,data}:{from:string,data:any})=>{const pc=pcsRef.current[from];if(!pc){console.error('RTCPeerConnection is not initialized.');return}console.log(`[Peer] Received answer.${from}`,data);await pc.setRemoteDescription(new RTCSessionDescription(data));await flushPendingCandidates(from)});socketRef.current.on('candidateArray',async({from,data}:{from:string,data:any})=>{const pc=pcsRef.current[from];if(pc){console.log('[Peer/Test] ICE candidate Array:',data);const rd=pc.remoteDescription;if(!rd){console.log("[Peer/Test] pc's remoteDescription is Null");pendingCandRef.current[from]=data;return}else if(pc.signalingState==='closed'){console.warn(`[Peer] Ignored ICE candidateArray from ${from} because PC is closed.`);return}else{console.log("[Peer/Test] remoteDescription detected. ICECandidate is added");if(data){console.log("[Peer] pending candidates...");for(const candof data){try{await pc.addIceCandidate(cand)}catch(e){console.warn('[Peer] addIcecandidate failed',e)}}}}}});socketRef.current.on('candidate',async({from,data}:{from:string,data:any})=>{const pc=pcsRef.current[from];if(!pc||!data.candidate)return;if(pc.remoteDescription){try{await pc.addIceCandidate(new RTCIceCandidate(data.candidate));console.log(`[Peer] Added ICE candidate from ${from}`)}catch(e){console.warn(`[Peer] Failed to add ICE candidate from ${from}`,e)}}else{if(!pendingCandRef.current[from]){pendingCandRef.current[from]=[]}pendingCandRef.current[from].push(data.candidate);console.log(`[Peer] RemoteDescription not ready. Buffered candidate from ${from}. Total: ${pendingCandRef.current[from].length}`)}});socketRef.current.on('force-disconnect-room',()=>{console.log(`[Peer] Force disconnect by server for all peers.`);if(noticeTimerRef.current){clearTimeout(noticeTimerRef.current);noticeTimerRef.current=null}setNotice('선생님이 퇴장하였습니다 !');noticeTimerRef.current=window.setTimeout(()=>{setNotice('');noticeTimerRef.current=null},3000);Object.keys(pcsRef.current).forEach((key)=>{const targetPc=pcsRef.current[key];if(targetPc){targetPc.close()}pcsRef.current={};pcTypesRef.current={};pendingCandRef.current={};iceCandidateGatheredArrayRef.current={};redialCountsRef.current={};setUsers([]);console.log(`[Peer] Closed connection with ${key}`)})});socketRef.current.on('droppedOffer-redial',()=>{console.log(`[Peer] Redial request dropped by server.`);socketRef.current?.emit('join',{room:room,type:'redial'})});return()=>{console.log('[App] Cleaning up resources...');if(socketRef.current){socketRef.current.disconnect();}if(localStreamRef.current){localStreamRef.current.getTracks().forEach(track=>{track.stop();});localStreamRef.current=null}if(pcsRef.current){Object.keys(pcsRef.current).forEach((key)=>{const pc=pcsRef.current[key];if(pc){pc.onicecandidate=null;pc.ontrack=null;pc.oniceconnectionstatechange=null;pc.close();console.log(`[App] Closed connection with ${key}`)}delete pcsRef.current[key]})}if(noticeTimerRef.current){clearTimeout(noticeTimerRef.current);noticeTimerRef.current=null}}},[]);const createPeerConnection=useCallback((peerId:string,type:string):RTCPeerConnection=>{console.log(`[Peer] createPeerConnection ${peerId}`);pcTypesRef.current[peerId]=type;const pc=new RTCPeerConnection(pcConfig);if(type==='recvonly'){console.log(`[Peer] Setting up recvonly connection `);const videoTransceiver=pc.addTransceiver('video',{direction:'recvonly'});pc.addTransceiver('audio',{direction:'recvonly'});if(videoTransceiver&&'setCodecPreferences'in videoTransceiver){const capabilities=RTCRtpReceiver.getCapabilities('video');if(capabilities&&capabilities.codecs){const h264Codecs=capabilities.codecs.filter(c=>c.mimeType==='video/H264');if(h264Codecs.length>0){try{videoTransceiver.setCodecPreferences(h264Codecs);console.log(`[Peer] H.264 preference set for recvonly connection`)}catch(e){console.error('H264 preference failed for recvonly',e)}}}}}else{if(localStreamRef.current!==null){console.log('[Peer] Add local stream to peer connection');localStreamRef.current.getTracks().forEach(track=>{const sender=pc.addTrack(track,localStreamRef.current!);if(track.kind==='video'){if(isRootRef.current){const parameters=sender.getParameters();parameters.degradationPreference='maintain-resolution';sender.setParameters(parameters).then(()=>console.log(`[Peer] ${peerId} degradationPreference set to maintain-resolution`)).catch(e=>console.warn(`[Peer] Failed to set degradationPreference for ${peerId}`,e))}const transceiver=pc.getTransceivers().find(t=>t.sender===sender);if(transceiver&&'setCodecPreferences'in transceiver){const capabilities=RTCRtpReceiver.getCapabilities('video');if(capabilities&&capabilities.codecs){const h264Codecs=capabilities.codecs.filter(c=>c.mimeType==='video/H264');if(h264Codecs.length>0){try{transceiver.setCodecPreferences(h264Codecs);console.log(`[Peer] H.264 Hardware Encoder preference set for ${peerId}`)}catch(e){console.error('H264 preference failed',e)}}}}}})}else{console.error('Local media stream is null')}}pc.onicecandidate=event=>{const socket=socketRef.current;if(iceCandidateGatheredArrayRef.current[peerId]===undefined){iceCandidateGatheredArrayRef.current[peerId]=[];}if(event.candidate&&socket!=null){socket.emit('candidate',{to:peerId,data:{type:'candidate',candidate:event.candidate}});iceCandidateGatheredArrayRef.current[peerId].push(event.candidate);}};pc.onicecandidateerror=(e)=>{const err=e as RTCPeerConnectionIceErrorEvent;console.warn('ICE error',err.errorCode,err.errorText,err.url)};pc.onconnectionstatechange=async()=>{console.log(`[${peerId}] state:`,pc.connectionState);if(pc.connectionState==='disconnected'){console.log(`[${peerId}] Connection ${pc.connectionState}.`);const pcType=pcTypesRef.current[peerId];console.log(`[${peerId}] Connection disconnected. Attempting renegotiate.`);if(pcType==='recvonly'){console.log(`[${peerId}] recvonly connection lost. Attempting renegotiate.`);renegotiateSamePc(peerId)}}else if(pc.connectionState==='failed'||pc.connectionState==='closed'){console.log(`[${peerId}] Connection failed. Attempting to redial ICE...`);const targetPc=pcsRef.current[peerId];const pcType=pcTypesRef.current[peerId];if(targetPc){targetPc.close()}delete pcsRef.current[peerId];delete pcTypesRef.current[peerId];pendingCandRef.current[peerId]=[];iceCandidateGatheredArrayRef.current[peerId]=[];redialCountsRef.current[peerId]=(redialCountsRef.current[peerId]||0)+1;setUsers(prev=>prev.filter(u=>u.id!==peerId));if(pcType==='recvonly'){if(redialCountsRef.current[peerId]>MAX_REDIAL_ATTEMPTS){console.log(`[${peerId}] Max redial attempts reached. Not attempting further redials.`);return}else{console.log(`[${peerId}] recvonly connection lost. Attempting redial.${redialCountsRef.current[peerId]}`);socketRef.current?.emit('join',{room:room,type:'redial'})}}else{console.log(`[${peerId}] Non-recvonly connection failed. Closing peer connection.`)}}else if(pc.signalingState==='closed'){console.log(`[${peerId}] Signaling state closed.`)}else if(pc.iceConnectionState==='closed'){console.log(`[${peerId}] ICE connection state closed.`)}else if(pc.connectionState==='connected'){console.log(`[${peerId}] Connection established successfully.changeCount:${changeCount}`);redialCountsRef.current[peerId]=0;if(changeCount===0){changeCount++}}};pc.ontrack=event=>{const stream=event.streams[0];const socket=socketRef.current;if(socket){setUsers(prev=>prev.some(user=>user.id===peerId)?prev.map(user=>user.id===peerId?{...user,stream:event.streams[0]}:user):[...prev,{id:peerId,socket:socket,stream:event.streams[0]}]);localStreamRef.current=stream;if(localVideoRef.current){localVideoRef.current.srcObject=localStreamRef.current}}console.log(`[Peer] Received remote stream  from ${peerId}`,stream?.getVideoTracks());if(hasStreamChangedRef.current){console.log(`[Peer] Stream has been changed before for ${peerId}, replacing tracks...`);const newVideo=stream.getVideoTracks()[0]??null;const newAudio=stream.getAudioTracks()[0]??null;const pcs=pcsRef.current;if(!pcs)return;Object.entries(pcs).forEach(([remotePeerId,childPc])=>{if(childPc===pc)return;childPc.getSenders().forEach((sender)=>{if(!sender.track)return;if(sender.track.kind==="video"){sender.replaceTrack(newVideo)}if(sender.track.kind==="audio"){sender.replaceTrack(newAudio)}});console.log(`[P4] replaceTrack() to ${remotePeerId}: video=${!!newVideo}, audio=${!!newAudio}`)})}if(!hasStreamChangedRef.current)hasStreamChangedRef.current=true;};pc.onicegatheringstatechange=()=>{console.log(`[Peer] ICE gathering state changed: ${pc.iceGatheringState}`);if(pc.iceGatheringState==='complete'){console.log(`[Peer] ICE gathering complete for ${peerId}. Total candidates gathered: ${iceCandidateGatheredArrayRef.current[peerId]?.length}`)}}return pc},[socketRef.current]);const sendIceCandidate=useCallback((peerId:string)=>{const candArray=iceCandidateGatheredArrayRef.current[peerId]||[];const pc=pcsRef.current[peerId];console.log(`[Peer] ICE candidate gathering state before sent: ${pc.iceGatheringState}`);console.log(`[Peer] Sending ICE candidates to signaling server... count:${candArray.length}`," ",candArray);if(candArray.length===0){console.log(`[Peer] No ICE candidates to send.`);return}else{socketRef.current?.emit('candidateArray',{to:peerId,data:candArray});console.log(`[Peer] Sent ${candArray.length} ICE candidates to ${peerId}`)}},[]);const flushPendingCandidates=async(peerId:string)=>{const pc=pcsRef.current[peerId];const pendingCandidates=pendingCandRef.current[peerId];if(pc&&pendingCandidates&&pendingCandidates.length>0){console.log(`[Peer] Flushing ${pendingCandidates.length} candidates for ${peerId}`);for(const candidateof pendingCandidates){try{await pc.addIceCandidate(new RTCIceCandidate(candidate))}catch(e){console.warn(`[Peer] Failed to add buffered candidate`,e)}}pendingCandRef.current[peerId]=[]}};const renegotiateSamePc=useCallback(async(peerId:string)=>{const pc=pcsRef.current[peerId];const socket=socketRef.current;if(!pc||!socket)return;try{if(pc.signalingState!=="stable"){console.log(`[${peerId}] signalingState=${pc.signalingState}, waiting for stable.`);return}console.log(`[${peerId}] Recreating offer (iceRestart=true) on same pc...`);const offer=await pc.createOffer({iceRestart:true});let finalSdp:any=offer;if(isRootRef.current){const newSdp=setMaxBandwidth(offer.sdp||'','video',BITRATE);finalSdp=newSdp?{type:offer.type,sdp:newSdp}:offer}await pc.setLocalDescription(finalSdp);socket.emit('offer-renegotiate',{to:peerId,data:finalSdp});console.log(`[${peerId}] Renegotiation offer sent.`)}catch(e){console.error(`[${peerId}] renegotiation failed`,e)}},[]);const forceDisconnectPeer=(peerId:string)=>{const pc=pcsRef.current[peerId];if(!pc){console.warn(`[forceDisconnectPeer] no pc for peerId=${peerId}`);return false}try{pc.close()}catch(e){console.error(`[forceDisconnectPeer] error closing pc for ${peerId}`,e)}delete pcsRef.current[peerId];delete pcTypesRef.current[peerId];if(pendingCandRef.current)pendingCandRef.current[peerId]=[];console.log(`[forceDisconnectPeer] disconnected peerId=${peerId}`);return true};forceDisconnectPeerRef.current=forceDisconnectPeer;const reset=useCallback(async()=>{console.log(`[RESET] Disconnecting all ${Object.keys(pcsRef.current).length} peers...`);socketRef.current?.emit('disconnect_reset');if(socketRef.current){socketRef.current.disconnect();}const peerIds=Object.keys(pcsRef.current);let disconnectedCount=0;peerIds.forEach(peerId=>{if(forceDisconnectPeerRef.current(peerId)){disconnectedCount++}});pcsRef.current={};pcTypesRef.current={};pendingCandRef.current={};iceCandidateGatheredArrayRef.current={};redialCountsRef.current={};setUsers([]);console.log(`[RESET] Successfully disconnected ${disconnectedCount} peers`);await new Promise(resolve=>setTimeout(resolve,3000));socketRef.current?.connect();return disconnectedCount},[]);useEffect(()=>{(window as any).forceDisconnectPeer=(peerId:string)=>{return forceDisconnectPeerRef.current(peerId)};(window as any).pcsRef=pcsRef;(window as any).disconnectAllPeers=reset;return()=>{delete(window as any).forceDisconnectPeer;delete(window as any).pcsRef;delete(window as any).disconnectAllPeers}},[forceDisconnectPeerRef,reset]);return(<div style={{ padding: 16, fontFamily: "system-ui, sans-serif" }}><h2>WebRTC Peer(React)</h2>

            <div style={{ display: 'flex', width: 480, height: 240 }}>
                <video
                    ref={localVideoRef}
                    autoPlay
                    playsInline
                    muted
                    style={{ width: '100%', height: '100%', background: "#000" }}
                />{}<div style={{
                        position: 'absolute', // 부모 div를 기준으로 위치를 정함
                        top: '10px',          // 위에서 10px 떨어짐
                        left: '10px',         // 왼쪽에서 10px 떨어짐
                        color: 'white',       // 글자색
                        backgroundColor: 'rgba(0, 0, 0, 0.5)', // 반투명 배경
                        padding: '5px 10px',  // 안쪽 여백
                        borderRadius: '5px',  // 모서리 둥글게
                        fontSize: '14px'
                    }}>{myid}</div>
                <button onClick={() => (changeStream())}>Change Stream</button><button onClick={() =>reset()}style={{ marginLeft: 8, backgroundColor: '#ff4444', color: 'white', padding: '6px 12px', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>RESET</button>
            </div>{users.map((user)=>(<div key={user.id}><Video peerId={user.id}stream={user.stream}/><div style={{ marginTop: '5px' }}>{}{}{}{<button onClick={()=>sendIceCandidate(user.id)}>Send ICE</button>}

                        </div></div>))}<div style={{ marginTop: 16 }}><pre style={{ background: "#f6f6f6", padding: 12, maxHeight: 240, overflow: "auto" }}></pre>
            </div>{notice&&(<div style={{
                    position: 'fixed',
                    top: 20,
                    left: '50%',
                    transform: 'translateX(-50%)',
                    background: 'rgba(0,0,0,0.85)',
                    color: '#fff',
                    padding: '10px 16px',
                    borderRadius: 8,
                    zIndex: 10000,
                    fontSize: 16
                }}>{notice}</div>
            )}
        </div>)}export default App;
```

시그널링 서버(server.js) 코드는 아래와 같다.
```
const fs=require("fs"),https=require("https"),express=require("express"),app=express(),options={key:fs.readFileSync("C:\\Windows\\System32\\key.pem"),cert:fs.readFileSync("C:\\Windows\\System32\\cert.pem")},httpsServer=https.createServer(options,app);app.use(express.static(__dirname)),httpsServer.listen(8e3,"0.0.0.0",()=>{console.log(" HTTPS server running")});const{Server:Server}=require("socket.io"),io=new Server(httpsServer,{cors:{origin:"*",methods:["GET","POST"]}}),rooms=new Map,peers=new Map;var listOfBroadcasts={};const AVAILABLE_BROADCASTING_NUMBER=8;let seq=0;io.on("connection",e=>{let r=Math.random().toString(36).substr(2,9)+"_"+seq++;function o(e){const r=listOfBroadcasts[e];if(!r)return;const o=r.allpeers,s={};for(const e in o){const r=o[e],t=r.treeLevel;s[t]||(s[t]=[]),s[t].push(r)}const t=Object.keys(s).sort((e,r)=>e-r);let i="";for(const e of t){i+=`---LV${e}----\n`;i+=s[e].map(e=>e.isRoot?e.peerid:`${e.peerid}(${e.parentid})`).join(" ")+"\n"}console.log("\n========== TREE STRUCTURE ["+e+"] ==========\n"+i)}function s(e){var r,o=listOfBroadcasts[e.roomid].broadcasters;for(var s in o){var t=o[s];t.peerid!==e.peerid&&t.active&&(t.isFull?delete listOfBroadcasts[e.roomid].broadcasters[s]:(t.treeLevel<e.treeLevel||-1===e.treeLevel)&&(!r||t.treeLevel<r.treeLevel)&&(r=t))}return r}function t(e,r){const o=e;if(!o||!rooms.has(o))return void console.log("[Server] Invalid room on removePeer:",o);const s=peers.get(r);if(!s)return void console.log("[Server] No peer found with id ",r);const t=listOfBroadcasts[s.roomid];if(!t)return void console.log("[Server] No broadcast structure found for room ",s.roomid);if(console.log("[Server] Handling disconnection of peer ",s.peerid),s.parentid&&s.parentid!==s.peerid){const e=t.allpeers[s.parentid];e&&(e.numberOfViewers=Math.max(0,e.numberOfViewers-1),e.numberOfViewers<8&&(e.isFull=!1,listOfBroadcasts[s.roomid].broadcasters[e.peerid]=e),e.childrenids=e.childrenids.filter(e=>e!==s.peerid))}delete t.broadcasters[s.peerid],delete t.activeBroadcasters[s.peerid],delete t.allpeers[s.peerid],console.log("[Server] Removed peer from broadcasters and allpeers:");const i=rooms.get(o);i&&i.delete(r),peers.delete(r),console.log(`[Server] Peer ${r} disconnected from room ${o} Peers size :`,peers.size)}console.log(`[Server] New connection: ${r}`),e.on("join-mesh",o=>{rooms.has(o)||(console.log(`[Server] Room ${o} does not exist, creating new room.`),rooms.set(o,new Set)),e.join(o),e.emit("my-id",r);const s=[...peers.keys()].filter(e=>e!==r);console.log(`[Server] Existing peers in room ${o}:`,s),e.emit("existing-peers",s),e.to(o).emit("new-peer",r),console.log(`[Server] Peer ${r} joined room ${o}`),console.log(`[room:${o}] join ->`,r),e.data||(e.data={}),e.data.room=o}),e.on("join",i=>{const a="string"==typeof i?i:i.room,n="string"==typeof i?"broadcast":i.type;let d="string"==typeof i?r:i.myid??r;if(console.log(`[Server] join event received. room: ${a}, type: ${n}, id: ${d}`),rooms.has(a)?console.log(`[Server] Room ${a} exists, joining room.`):(console.log(`[Server] Room ${a} does not exist, creating new room.`),rooms.set(a,new Set)),e.join(a),e.data||(e.data={}),e.data.room=a,"broadcast"===n){const i={peerid:d,socket:e,roomid:a,parentid:null,childrenids:[],isBroadcaster:!1,isFull:!1,numberOfViewers:0,treeLevel:-1,active:!0,isRoot:!1};listOfBroadcasts[i.roomid]||(listOfBroadcasts[i.roomid]={broadcasters:{},activeBroadcasters:{},allpeers:{}});var c=listOfBroadcasts[i.roomid].allpeers;for(var l in c){console.log(`[Server] Considering peer !: ${l} for cleanup during broadcast of peer ${d}`),!1!==(f=c[l]).active||(console.log(`[Server] Removing inactive peer: ${f.peerid}`),t(f.roomid,f.peerid))}e.emit("my-id",d);var p=s(i);return p?(0===listOfBroadcasts[i.roomid].broadcasters[p.peerid].numberOfViewers&&(console.log("Setting firstBroadcaster",r,"as true"),i.isBroadcaster=!0,listOfBroadcasts[i.roomid].activeBroadcasters[i.peerid]=i),listOfBroadcasts[i.roomid].broadcasters[p.peerid].numberOfViewers++,listOfBroadcasts[i.roomid].broadcasters[p.peerid].childrenids.push(i.peerid),i.parentid=p.peerid,i.treeLevel=p.treeLevel+1,listOfBroadcasts[i.roomid].broadcasters[p.peerid].numberOfViewers>=8&&(listOfBroadcasts[i.roomid].broadcasters[p.peerid].isFull=!0)):(console.log("No available broadcaster found, setting as root broadcaster."),i.isBroadcaster=!0,i.treeLevel=0,listOfBroadcasts[i.roomid].activeBroadcasters[i.peerid]=i,i.parentid=d,i.isRoot=!0,e.emit("root-broadcaster")),listOfBroadcasts[i.roomid].broadcasters[i.peerid]=i,listOfBroadcasts[i.roomid].allpeers[i.peerid]=i,peers.set(d,i),console.log("id: ",d,"Parent id:",i.parentid),d!=i.parentid&&e.emit("new-parent",i.parentid),console.log(`[room:${a}] Tree size by broadcast`,peers.size),void o(a)}if("redial"===n){let r=peers.get(d);if(!r)return void console.log(`[Server] No existing peer found for redial with id: ${d}`);c=listOfBroadcasts[r.roomid].allpeers;for(var l in c){var f;console.log(`[Server] Considering peer !: ${l} for cleanup during broadcast of peer ${d}`),!1!==(f=c[l]).active||(console.log(`[Server] Removing inactive peer: ${f.peerid}`),t(f.roomid,f.peerid))}var m=s(r);return m?(0===listOfBroadcasts[r.roomid].broadcasters[m.peerid].numberOfViewers&&(console.log("Setting newBroadcaster ",d,"==",r.peerid,"as true"),r.isBroadcaster=!0,listOfBroadcasts[r.roomid].activeBroadcasters[r.peerid]=r),listOfBroadcasts[r.roomid].broadcasters[m.peerid].numberOfViewers++,listOfBroadcasts[r.roomid].broadcasters[m.peerid].childrenids.push(r.peerid),r.parentid=m.peerid,r.treeLevel=m.treeLevel+1,listOfBroadcasts[r.roomid].broadcasters[m.peerid].numberOfViewers>=8&&(listOfBroadcasts[r.roomid].broadcasters[m.peerid].isFull=!0)):console.log(`[Server] No available broadcaster found for redial of peer ${d} in room ${a}`),listOfBroadcasts[r.roomid].broadcasters[r.peerid]=r,listOfBroadcasts[r.roomid].allpeers[r.peerid]=r,peers.set(d,r),function(e,r){const o=listOfBroadcasts[e];if(!o)return;if(!o.allpeers[r])return;const s=[r],t=new Set([r]);console.log(`[Server-Subtree] Updating subtree levels starting from peer ${r} the queue ${s} `);for(;s.length;){const e=s.shift(),r=o.allpeers[e];if(!r)continue;const i=r.treeLevel,a=r.childrenids||[];for(const e of a){if(t.has(e))continue;const a=o.allpeers[e];a&&(a.parentid===r.peerid&&(a.treeLevel=i+1,console.log(`[Server] Updated treeLevel of peer ${a.peerid} to ${a.treeLevel} (parent: ${r.peerid} at level ${i})`),t.add(e),s.push(e)))}}}(r.roomid,r.peerid),console.log("id: ",d,"new Parent setid:",r.parentid),d!=r.parentid&&e.emit("new-parent",r.parentid),console.log(`[room:${a}] Tree size by redial`,peers.size),void o(a)}}),e.on("offer",({to:o,data:s})=>{const t=peers.get(o);if(!t||!t.socket||!t.active){const s=e.data?.peerid??"unknown";return console.warn(`[Server] Drop offer: target missing. from=${s}, to=${o}`),void e.emit("droppedOffer-redial",{from:r,to:o})}t.socket.emit("offer",{from:r,data:s}),console.log(`[Server] Offer from ${r} to ${o}`)}),e.on("offer-renegotiate",({to:o,data:s})=>{const t=peers.get(o);if(!t||!t.socket||!t.active){const r=e.data?.peerid??"unknown";return void console.warn(`[Server] Drop offer-renegotiate: target missing. from=${r}, to=${o}`)}t.socket.emit("offer",{from:r,data:s}),console.log(`[Server] Offer from ${r} to ${o}`)}),e.on("answer",({to:o,data:s})=>{const t=peers.get(o);if(!t||!t.socket||!t.active){const r=e.data?.peerid??"unknown";return void console.warn(`[Server] Drop offer: target missing. from=${r}, to=${o}`)}t.socket.emit("answer",{from:r,data:s})}),e.on("candidate",({to:o,data:s})=>{const t=peers.get(o);if(!t||!t.socket||!t.active){const r=e.data?.peerid??"unknown";return void console.warn(`[Server] Drop candidate: target missing. from=${r}, to=${o}`)}t.socket.emit("candidate",{from:r,data:s})}),e.on("candidateArray",({to:o,data:s})=>{const t=peers.get(o);if(!t||!t.socket||!t.active){const r=e.data?.peerid??"unknown";return void console.warn(`[Server] Drop candidateArray: target missing. from=${r}, to=${o}`)}t.socket.emit("candidateArray",{from:r,data:s}),console.log(`[Server] Candidate from ${r} to ${o}`)}),e.on("disconnect",()=>{console.log(`[Server] Peer ${r} disconnected.`);const o=e.data.room;if(!o||!rooms.has(o))return void console.log(`[Server] Invalid room on disconnect_reset: ${o}`);const s=peers.get(r);if(s){if(s.active=!1,s.isRoot){console.log(`[Server] Root peer ${r} disconnected, marking all peers in room ${o} as inactive.`),io.to(o).emit("force-disconnect-room");const e=listOfBroadcasts[s.roomid];for(const r in e.allpeers){e.allpeers[r].active=!1}}console.log(`[Server] Marked peer ${r} as inactive peer`)}else console.log(`[Server] No peer found with id ${r} on disconnect_reset`)}),e.on("disconnect_reset",()=>{console.log(`[Server] Peer ${r} RESET.`);const o=e.data.room;if(!o||!rooms.has(o))return void console.log(`[Server] Invalid room on disconnect_reset: ${o}`);const s=peers.get(r);if(s){if(s.active=!1,s.isRoot){console.log(`[Server] Root peer ${r} disconnected, marking all peers in room ${o} as inactive.`),io.to(o).emit("force-disconnect-room");const e=listOfBroadcasts[s.roomid];for(const r in e.allpeers){e.allpeers[r].active=!1}}console.log(`[Server] Marked peer ${r} as inactive peer`)}else console.log(`[Server] No peer found with id ${r} on disconnect_reset`)})});
```


브라우저 vs 네이티브 비교 (기대 효과) 도표:
---
항목,  브라우저 WebRTC (현재),  네이티브 WebRTC (목표)
화면 캡처,   getDisplayMedia (브라우저 제한),   MediaProjection / ReplayKit (시스템 레벨)
카메라 접근,  매번 권한 팝업,    한 번 허용 후 유지
---

네이티브 앱임에도 영상 처리를 브라우저(WebView)에 의존하여 하드웨어 인코더/디코더 활용 불가한 상황이 일어나면 안된다.
Android에서 하드웨어 가속을 활용한 네이티브 WebRTC 구현을 목표로 한다.

아래의 스택을 사용하려 한다.
 - libwebrtc (Google 네이티브 라이브러리)
 - MediaProjection API (시스템 레벨 화면 캡처)
 - Camera2 API (네이티브 카메라)

기존에 만들어놓은 server.js의 시그널링 프로토콜과 호환되는 네이티브 SDK를 개발한다.
focuspang-media-sdk/
├── android/
|   ├── MediaServerClient.kt      ← Socket.IO 시그널링 (기존 이벤트 규격 호환)
|   ├── NativeTransportManager.kt ← WebRTC Transport 생성/관리
|   ├── HardwareEncoderConfig.kt  ← MediaCodec 설정 (H.264/VP9/AV1)
|   ├── ScreenCapturer.kt         ← MediaProjection 화면 캡처
|   ├── CameraCapturer.kt         ← Camera2 API 카메라
|   └── AdaptiveBitrate.kt        ← 네트워크 상태 기반 품질 자동 조절
|
 */

package com.example.webrtcandroid

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo

import com.example.webrtcandroid.ui.theme.WebRTCAndroidTheme

import io.socket.client.IO
import io.socket.client.Socket

import okhttp3.OkHttpClient

import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

import java.net.URISyntaxException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 코덱 로그 조회용
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import android.os.Build

// [메인 화면]
class MainActivity : ComponentActivity() {

    private val rootEglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null

    // 클래스 멤버 레벨에서는 remember를 쓰지 않고 mutableStateOf만 사용합니다.
    private var videoTrackState = mutableStateOf<VideoTrack?>(null)
    private var isScreenSharing by mutableStateOf(false)

    private var currentCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var webRTCManager: NativeTransportManager? = null
    private lateinit var systemMonitor: com.example.webrtcandroid.monitor.SystemMonitor

    // [추가] VideoSource 추적을 위한 변수
    private var currentVideoSource: VideoSource? = null

    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    // 원격 비디오 트랙을 관리할 상태 맵 (Compose UI가 관찰함)
    private val remoteVideoTracks = mutableStateMapOf<String, VideoTrack>()
    private var isConnected by mutableStateOf(false)
    private var selectedCodec by mutableStateOf("None") // 코덱 선택 상태 추가 (기본값: None)
    private var selectedEncoderMode by mutableStateOf("Shared HW") // 인코더 모드 상태 추가 (Software, Hardware, Shared HW)
    private var selectedResolutionPreset by mutableStateOf("720p") // 해상도 프리셋 상태 추가
    private var selectedFps by mutableStateOf(60f) // FPS 설정 상태 추가
    private var forceKeyFrameInterval by mutableStateOf(61f) // 강제 키프레임 생성 주기 상태 추가 (61f = 수동)
    private var ipThird by mutableStateOf("0")
    private var ipFourth by mutableStateOf("2")
    private val signalingUrl: String
        get() = "https://192.168.${ipThird.trim()}.${ipFourth.trim()}:8000"



    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            val track = createVideoTrack(applicationContext)
            videoTrackState.value = track
            // 카메라 트랙 생성 후 매니저에 즉시 등록 (동기화 보강)
            webRTCManager?.changeVideoTrack(track)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCapture(result.data!!)
        }
    }

    private fun cleanupStep() {
        try {
            currentCapturer?.stopCapture()
            currentCapturer?.dispose()
            currentCapturer = null

            videoTrackState.value?.let { track ->
                track.setEnabled(false)
                track.dispose() // 기존 트랙의 C++ 네이티브 메모리 해제
            }
            videoTrackState.value = null

            surfaceHelper?.stopListening()
            surfaceHelper?.dispose()
            surfaceHelper = null

            currentVideoSource?.dispose() // 기존 영상 소스의 C++ 버퍼 해제
            currentVideoSource = null
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun initPeerConnectionFactory(appContext: Context) {
        // 이미 팩토리가 생성되어 있다면 건너뜀
        if (factory != null) return

        // 1. WebRTC 전역 초기화 (단 한 번만 실행되어야 함)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(true) // 디버깅 트레이서 활성화
                .createInitializationOptions()
        )

        // 2. 하드웨어 비디오 인코더/디코더 팩토리 생성
        // 첫 번째 true -  하드웨어 가속기 우선 사용 허용
        // 두 번째 true - Intel/Qualcomm 등 특정 벤더의 가속기도 허용
        //val baseFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        //val baseFactory = CustomMediaCodecVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)

        // 동적 인코더 팩토리 적용 (Software, Hardware, Shared HW 선택 가능)
        val encoderFactory = DynamicVideoEncoderFactory(rootEglBase.eglBaseContext) { selectedEncoderMode }

        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        // 디코더 역시 하드웨어 가속을 우선하도록 Default 대신 HardwareVideoDecoderFactory 사용 권장
        //val decoderFactory = HardwareVideoDecoderFactory(rootEglBase.eglBaseContext)

        // 3. 최종 PeerConnectionFactory 빌드
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // 팩토리 초기화 후 현재 설정된 강제 키프레임 주기를 공유 팩토리에 동기화함
        // onCreate 시점에는 팩토리가 생성되기 전이므로 미리 저장된 forceKeyFrameInterval 값을 생성 시점에 적용함
        val intervalSec = if (forceKeyFrameInterval >= 61f) 3600L else forceKeyFrameInterval.toLong()
        SharedVideoEncoderFactory.instance?.forceKeyFrameIntervalSec = intervalSec
        android.util.Log.d("WebRTC_Test", "PeerConnectionFactory 초기화 완료 및 forceKeyFrameIntervalSec(${intervalSec}초) 적용 완료")
    }


    private fun createVideoTrack(appContext: Context): VideoTrack? {
        cleanupStep()

        // [수정] 통합 초기화 함수 호출
        initPeerConnectionFactory(appContext)

        // 팩토리가 정상 생성되었는지 확인
        val pcFactory = factory ?: return null


        // 수정: CameraCapturerHelper 사용
        val videoCapturer = CameraCapturerHelper.createCameraCapturer(appContext) ?: return null
        currentCapturer = videoCapturer

        val videoSource = factory?.createVideoSource(false)
        currentVideoSource = videoSource // GC가 수거하기 전에 추적

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)

        // 수정: Capturer 초기화에도 appContext 주입
        videoCapturer.initialize(surfaceHelper, appContext, videoSource?.capturerObserver)
        videoCapturer.startCapture(HardwareEncoderConfig.videoWidth, HardwareEncoderConfig.videoHeight, HardwareEncoderConfig.videoFps)

        return factory?.createVideoTrack("VIDEO_TRACK_ID", videoSource)
    }

    // 캡처 시작 시 UI를 먼저 비우고 캡처를 시작하는 방식으로 순서 변경
    private fun startScreenCapture(mediaProjectionData: Intent) {
        // 기존에 흐르던 트랙이 있다면 데이터를 차단
        videoTrackState.value?.let { oldTrack ->
            oldTrack.setEnabled(false) // 트랙 비활성화 (데이터 흐름 차단)
            // 만약 VideoPlayer 렌더러가 붙어있다면 여기서 제거 로직이 동작함
        }

        // 1. UI 상태를 먼저 변경하여 렌더러를 제거
        isScreenSharing = true

        // 2. 약간의 딜레이를 주어 UI가 "화면 공유 중" 텍스트로 완전히 바뀐 뒤 캡처 로직 실행
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            cleanupStep() // 기존 카메라 자원 정리

            // 포그라운드 서비스가 완전히 시작된 후에 캡처를 시작하기 위해
            // 콜백을 먼저 등록한 뒤 서비스를 시작한다.
            ScreenCaptureService.onServiceReady = {
                // 이 콜백은 서비스의 onStartCommand → startForeground() 직후에 호출된다.
                // onStartCommand는 메인 스레드에서 실행되므로 여기서 UI 작업도 안전하다.
                android.util.Log.i("WebRTC_SCREEN", "포그라운드 서비스 준비 완료, 화면 캡처 시작")
                try {
                    val screenCapturer = ScreenCapturerAndroid(mediaProjectionData, object : MediaProjection.Callback() {
                        override fun onStop() {
                            isScreenSharing = false
                            stopService(Intent(applicationContext, ScreenCaptureService::class.java))
                        }
                    })
                    currentCapturer = screenCapturer

                    // factory 생성 시 applicationContext 사용
                    initPeerConnectionFactory(applicationContext)

                    val videoSource = factory?.createVideoSource(true)
                    currentVideoSource = videoSource // GC가 수거하기 전에 추적



                    videoSource?.adaptOutputFormat(HardwareEncoderConfig.videoWidth, HardwareEncoderConfig.videoHeight, HardwareEncoderConfig.videoFps) // 인코더로 넘어가기 전에 WebRTC 파이프라인(VideoSource)에서 강제로 프레임 Drop 지시.

                    /*
                    surfaceHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
                    screenCapturer.initialize(surfaceHelper, applicationContext, videoSource?.capturerObserver)
                    */

                    // 위에거 주석처리하고 아래 추가. ediaProjection이 뿜어내는 비표준 압축 텍스처가 인코더에 도달하기 전 CPU/GPU(libyuv) 단에서 표준 YUV 포맷으로 평탄화
                    surfaceHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
                    val originalObserver = videoSource?.capturerObserver
                    if (originalObserver != null) {
                        // 원본 옵저버를 I420ConversionObserver로 감싸서 하드웨어 인코더로 가기 전 포맷을 규격화함
                        val i420Observer = I420ConversionObserver(originalObserver)
                        screenCapturer.initialize(surfaceHelper, applicationContext, i420Observer)
                        android.util.Log.i("WebRTC_SCREEN", "I420 변환 파이프라인 연결 완료")
                    } else {
                        android.util.Log.e("WebRTC_SCREEN", "capturerObserver가 null입니다.")
                    }
                    // 해상도 조절. 854*480은 480p SD 해상도. (16:9)
                    // 세 번째 인자인 fps(24)는 내부적으로 사실상 무시되는 더미(Dummy) 값?
                    // 안드로이드의 화면 공유는 내부적으로 VirtualDisplay를 생성하여 동작합니다. 디스플레이는 "지정된 프레임"으로 캡처하는 것이 아니라, "화면에 변경 사항(UI 애니메이션, 스크롤 등)이 생길 때마다" 프레임을 밀어냅니다(Push). 따라서 기기 주사율이 60Hz이고 화면에 계속 움직임이 있다면, 시스템은 WebRTC 파이프라인으로 초당 60장의 프레임을 넣음.
                    // 이 문제 해결 위해서 캡처된 프레임이 인코더로 넘어가기 전(파이프라인의 중간 단계)에 프레임을 솎아내서 버리도록(Drop) 지시 (위에 비슷하게 생긴 코드)
                    screenCapturer.startCapture(HardwareEncoderConfig.videoWidth, HardwareEncoderConfig.videoHeight, HardwareEncoderConfig.videoFps)

                    //videoTrackState.value = factory?.createVideoTrack("SCREEN_TRACK_ID", videoSource)

                    //트랙 교체 부분.
                    // 1. 새로운 화면 공유 트랙 생성
                    val newTrack = factory?.createVideoTrack("SCREEN_TRACK_ID", videoSource)

                    // 2. Compose UI 갱신을 위해 상태 업데이트
                    videoTrackState.value = newTrack

                    // 3. WebRTC 통신망에 새 트랙 밀어넣기
                    webRTCManager?.changeVideoTrack(newTrack)


                } catch (e: Exception) {
                    android.util.Log.e("WebRTC_SCREEN", "화면 캡처 시작 실패", e)
                    e.printStackTrace()
                    isScreenSharing = false
                }
            }

            val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
            startForegroundService(serviceIntent)

        }, 300) // 0.3초의 유예 시간을 줌
    }

    private fun switchToCamera() {
        if (!isScreenSharing) return

        // 1. UI 상태를 카메라 공유 모드로 먼저 준비
        isScreenSharing = false

        // 2. 약간의 딜레이를 주어 UI가 안전하게 갱신된 뒤 카메라 실행
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 3. 기존 화면 공유 자원 정리 및 스크린 서비스 정지
            cleanupStep()
            stopService(Intent(applicationContext, ScreenCaptureService::class.java))

            // 4. 새로운 카메라 비디오 트랙 생성 및 시작
            val newTrack = createVideoTrack(applicationContext)
            videoTrackState.value = newTrack

            // 5. WebRTC 전송망의 비디오 트랙 교체
            webRTCManager?.changeVideoTrack(newTrack)
            android.util.Log.i("WebRTC_SCREEN", "카메라 스트리밍 전환 완료")
        }, 300)
    }

    private fun toggleStream() {
        if (isScreenSharing) {
            switchToCamera()
        } else {
            requestScreenCapture()
        }
    }

    private fun applyDynamicCaptureSettings() {
        // 1. WebRTC VideoSource adaptOutputFormat 갱신
        currentVideoSource?.adaptOutputFormat(
            HardwareEncoderConfig.videoWidth,
            HardwareEncoderConfig.videoHeight,
            HardwareEncoderConfig.videoFps
        )
        // 2. 현재 활성화된 Capturer의 startCapture 호출 (동적 포맷 재지정)
        if (currentCapturer != null) {
            try {
                currentCapturer?.startCapture(
                    HardwareEncoderConfig.videoWidth,
                    HardwareEncoderConfig.videoHeight,
                    HardwareEncoderConfig.videoFps
                )
                android.util.Log.d("WebRTC", "Dynamic capture parameters applied: Width=${HardwareEncoderConfig.videoWidth}, Height=${HardwareEncoderConfig.videoHeight}, FPS=${HardwareEncoderConfig.videoFps}")
            } catch (e: Exception) {
                android.util.Log.e("WebRTC", "Failed to apply dynamic capture parameters", e)
            }
        }
    }

    private fun updateResolutionPreset(preset: String) {
        selectedResolutionPreset = preset
        when (preset) {
            "180p" -> {
                HardwareEncoderConfig.videoWidth = 320
                HardwareEncoderConfig.videoHeight = 180
            }
            "480p" -> {
                HardwareEncoderConfig.videoWidth = 720
                HardwareEncoderConfig.videoHeight = 480
            }
            "720p" -> {
                HardwareEncoderConfig.videoWidth = 1280
                HardwareEncoderConfig.videoHeight = 720
            }
            "1080p" -> {
                HardwareEncoderConfig.videoWidth = 1920
                HardwareEncoderConfig.videoHeight = 1080
            }
        }
        applyDynamicCaptureSettings()
    }

    private fun updateFpsSetting(fps: Float) {
        selectedFps = fps
        HardwareEncoderConfig.videoFps = fps.toInt()
        applyDynamicCaptureSettings()
    }

    private fun updateKeyFrameIntervalSetting(interval: Float) {
        forceKeyFrameInterval = interval
        val intervalSec = if (interval >= 61f) 3600L else interval.toLong()
        SharedVideoEncoderFactory.instance?.forceKeyFrameIntervalSec = intervalSec
        android.util.Log.d("WebRTC", "Force Keyframe interval updated: ${if (intervalSec == 3600L) "Manual" else "$intervalSec seconds"}")
    }

    private fun requestScreenCapture() {
        Log.d("Log", "화면 캡쳐 시작")
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun connectToRoom() {
        //ensureFactoryCreated()
        initPeerConnectionFactory(applicationContext)
        systemMonitor.startMonitoring()

        webRTCManager = NativeTransportManager(
            factory = factory!!,
            serverUrl = signalingUrl,
            roomName = "testRoom",
            localVideoTrack = videoTrackState.value,
            preferredCodec = selectedCodec, // 여기에 코덱 설정 주입
            encoderMode = selectedEncoderMode, // 여기에 인코더 설정 주입
            onConnectedCallback = {
                // 백그라운드 소켓 스레드에서 호출되므로 메인 스레드로 전환
                runOnUiThread {
                    isConnected = true
                }
            },
            onRemoteTrackAdded = { peerId, track ->
                runOnUiThread {
                    remoteVideoTracks[peerId] = track
                }
            },
            onRemoteTrackRemoved = { peerId ->
                runOnUiThread {
                    remoteVideoTracks.remove(peerId)
                }
            }
        )
        webRTCManager?.connect()

        webRTCManager?.startStatsMonitoring() // 통계 추가.
    }

    private fun disconnectFromRoom() {
        systemMonitor.stopMonitoring()
        webRTCManager?.destroy()
        webRTCManager = null
        isConnected = false
        runOnUiThread {
            remoteVideoTracks.clear()
        }
        android.util.Log.i("WebRTC_SCREEN", "서버 연결 해제 완료")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.webrtcandroid.monitor.TelemetryManager.initialize(applicationContext)
        systemMonitor = com.example.webrtcandroid.monitor.SystemMonitor(applicationContext)

        // 카메라/마이크 기본 권한 팝업을 전면 가동함
        requestMultiplePermissionsLauncher.launch(permissions)

        setContent {
            WebRTCAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121824) // 다크 슬레이트 톤의 프리미엄 배경색
                ) {
                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val scrollState = rememberScrollState()

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { innerPadding ->
                        if (isLandscape) {
                            // ----------------- 가로 모드 (Landscape) -----------------
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // 왼쪽 영역 (비디오 스트림 카드들)
                                Column(
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "WebRTC 모니터링",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // 로컬 비디오 프리뷰 카드
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            val track = videoTrackState.value
                                            if (track != null && !isScreenSharing) {
                                                VideoPlayer(track, rootEglBase)
                                            } else if (isScreenSharing) {
                                                ScreenShareStatusOverlay(modifier = Modifier.align(Alignment.Center))
                                            } else {
                                                Text(
                                                    text = "카메라 준비 중...",
                                                    color = Color.Gray,
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }

                                            // 내 상태 배지 (오버레이)
                                            LocalStatusBadge(modifier = Modifier.align(Alignment.TopStart))
                                        }
                                    }

                                    // 원격 접속자 피어 가로 리스트
                                    Text(
                                        text = "원격 피어 (${remoteVideoTracks.size}명)",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    RemotePeersHorizontalList(modifier = Modifier.height(105.dp))
                                }

                                // 오른쪽 영역 (컨트롤러 - 스크롤 가능하게 처리)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ControlPanelCard()
                                }
                            }
                        } else {
                            // ----------------- 세로 모드 (Portrait) -----------------
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(16.dp)
                                    .verticalScroll(scrollState),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "WebRTC 방송 제어 콘솔",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // 로컬 비디오 프리뷰 카드 (세로모드는 비율 고정하여 배치)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 10f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        val track = videoTrackState.value
                                        if (track != null && !isScreenSharing) {
                                            VideoPlayer(track, rootEglBase)
                                        } else if (isScreenSharing) {
                                            ScreenShareStatusOverlay(modifier = Modifier.align(Alignment.Center))
                                        } else {
                                            Text(
                                                text = "카메라 준비 중...",
                                                color = Color.Gray,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }

                                        // 내 상태 배지 (오버레이)
                                        LocalStatusBadge(modifier = Modifier.align(Alignment.TopStart))
                                    }
                                }

                                // 컨트롤 패널 카드
                                ControlPanelCard()

                                // 원격 접속자 피어 목록
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "원격 피어 모니터 (${remoteVideoTracks.size}명 접속 중)",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    RemotePeersHorizontalList(modifier = Modifier.height(120.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LocalStatusBadge(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isConnected) "🟢" else "🔴",
                    fontSize = 12.sp
                )
                Text(
                    text = if (isConnected) "서버 연결됨 (testRoom)" else "연결 끊김",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    private fun ScreenShareStatusOverlay(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "💻",
                fontSize = 48.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "화면 공유 중",
                color = Color(0xFF64B5F6),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "시스템 화면이 실시간으로 송출되고 있습니다.",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    @Composable
    private fun RemotePeersHorizontalList(modifier: Modifier = Modifier) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                if (remoteVideoTracks.isEmpty()) {
                    Text(
                        text = "대기 중인 피어가 없습니다.",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = 13.sp
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(remoteVideoTracks.toList()) { (peerId, track) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1.33f),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Black)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    VideoPlayer(track, rootEglBase)

                                    // 피어 ID 라벨 오버레이
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = peerId,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ControlPanelCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "방송 스트림 컨트롤",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 시그널링 IP 주소 설정 입력 UI
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text("시그널링 서버 주소 설정:", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "https://192.168.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = ipThird,
                            onValueChange = { newValue ->
                                if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                    ipThird = newValue
                                }
                            },
                            modifier = Modifier
                                .width(65.dp)
                                .height(52.dp),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(
                            text = ".",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = ipFourth,
                            onValueChange = { newValue ->
                                if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                    ipFourth = newValue
                                }
                            },
                            modifier = Modifier
                                .width(65.dp)
                                .height(52.dp),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(
                            text = ":8000",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 0. 인코더 구조 선택기 (FlowRow 적용)
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text("인코더 구조:", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Software", "Hardware", "Shared HW").forEach { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = !isConnected) { selectedEncoderMode = mode }
                            ) {
                                RadioButton(
                                    selected = (selectedEncoderMode == mode),
                                    onClick = { selectedEncoderMode = mode },
                                    enabled = !isConnected,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFF59E0B),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Text(
                                    text = mode,
                                    color = if (selectedEncoderMode == mode) Color.White else Color.Gray,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedEncoderMode == mode) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 1. 우선순위 코덱 선택기 (FlowRow 적용으로 가로 짤림 방지 및 자동 줄바꿈)
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text("우선순위 코덱:", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("None", "H264", "H265", "VP8", "VP9").forEach { codec ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = !isConnected) { selectedCodec = codec }
                            ) {
                                RadioButton(
                                    selected = (selectedCodec == codec),
                                    onClick = { selectedCodec = codec },
                                    enabled = !isConnected,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF3B82F6),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Text(
                                    text = codec,
                                    color = if (selectedCodec == codec) Color.White else Color.Gray,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedCodec == codec) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 2. 해상도 프리셋 선택기 (FlowRow 적용으로 가로 짤림 방지 및 자동 줄바꿈)
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text("해상도 설정:", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("480p", "720p", "1080p").forEach { preset ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { updateResolutionPreset(preset) }
                            ) {
                                RadioButton(
                                    selected = (selectedResolutionPreset == preset),
                                    onClick = { updateResolutionPreset(preset) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF10B981),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Text(
                                    text = preset,
                                    color = if (selectedResolutionPreset == preset) Color.White else Color.Gray,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedResolutionPreset == preset) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 3. FPS 동적 조절 슬라이더
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("프레임 레이트 (FPS): ", color = Color.Gray, fontSize = 13.sp)
                        Text("${selectedFps.toInt()} FPS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = selectedFps,
                        onValueChange = { updateFpsSetting(it) },
                        valueRange = 15f..60f,
                        steps = 2,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF8B5CF6),
                            activeTrackColor = Color(0xFF8B5CF6),
                            inactiveTrackColor = Color.Gray
                        )
                    )
                }

                // 4. 강제 Keyframe 생성 주기 슬라이더
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("자동 Keyframe 강제 주기: ", color = Color.Gray, fontSize = 13.sp)
                        Text(
                            text = if (forceKeyFrameInterval >= 61f) "수동 생성 전용" else "${forceKeyFrameInterval.toInt()}초",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = forceKeyFrameInterval,
                        onValueChange = { updateKeyFrameIntervalSetting(it) },
                        valueRange = 1f..61f,
                        steps = 60,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFF59E0B),
                            activeTrackColor = Color(0xFFF59E0B),
                            inactiveTrackColor = Color.Gray
                        )
                    )
                }

                // 주요 액션 버튼들 - 가로 짤림 방지 및 자동 줄바꿈(FlowRow) 처리
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 서버 접속/해제 토글 버튼
                    Button(
                        onClick = {
                            if (isConnected) {
                                disconnectFromRoom()
                            } else {
                                connectToRoom()
                            }
                        },
                        modifier = Modifier.weight(1f).widthIn(min = 130.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color(0xFFEF4444) else Color(0xFF3B82F6)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isConnected) "🔌 연결 해제" else "⚡ 서버 접속",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            softWrap = true
                        )
                    }

                    // 소스 교체 (카메라 <-> 화면 공유) 토글 버튼
                    Button(
                        onClick = { toggleStream() },
                        modifier = Modifier.weight(1f).widthIn(min = 130.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScreenSharing) Color(0xFF8B5CF6) else Color(0xFF10B981)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isScreenSharing) "📷 카메라 전환" else "💻 화면 공유",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            softWrap = true
                        )
                    }

                    // 즉시 Keyframe (I-Frame) 생성 버튼
                    Button(
                        onClick = {
                            SharedVideoEncoderFactory.instance?.requestKeyFrame()
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f).widthIn(min = 220.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF59E0B),
                            disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "⚡ 즉시 Keyframe (I-Frame) 생성",
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color.White else Color.LightGray,
                            softWrap = true
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 포그라운드 복귀 시 카메라 재시작
        if (!isScreenSharing && currentCapturer != null) {
            try {
                // 해상도와 FPS를 초기 설정과 동일하게 맞춤
                currentCapturer?.startCapture(HardwareEncoderConfig.videoWidth, HardwareEncoderConfig.videoHeight, HardwareEncoderConfig.videoFps)
                videoTrackState.value?.setEnabled(true)
            } catch (e: Exception) {
                e.printStackTrace()
                // 만약 재시작에 실패했다면 트랙을 아예 완전히 재생성하는 폴백 로직을 추가하는 것도 좋ㄱ[다..?
                // videoTrackState.value = createVideoTrack(this)
            }
        }

        // ------------------------------------- 코덱 확인 코드 추가.
        HardwareEncoderConfig.logSupportedEncoders()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 앱이 백그라운드로 갈 때 자원을 정리
        if (!isScreenSharing) {
            try {
                // Thread 분리 없이 동기적으로 정지
                // WebRTC의 stopCapture는 약간의 블로킹(약 100~300ms)이 발생하지만,
                // 다른 앱에 카메라 권한을 정상적으로 넘겨주고 충돌을 막기 위해 onStop에서 대기하는 것이 안전?
                currentCapturer?.stopCapture()
                videoTrackState.value?.setEnabled(false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 앱 종료 시 텔레메트리 세션 수동 저장 (현재 연결된 원격 피어 수 전달)
            com.example.webrtcandroid.monitor.TelemetryManager.getInstance().stopSessionAndSave(remoteVideoTracks.size)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::systemMonitor.isInitialized) {
            systemMonitor.stopMonitoring()
        }
        webRTCManager?.destroy()
        cleanupStep()
        rootEglBase.release()
        factory?.dispose()
    }
}

// 런타임에 인코더 구조를 동적으로 전환하기 위한 프록시 비디오 인코더 팩토리
class DynamicVideoEncoderFactory(
    private val eglContext: EglBase.Context,
    private val getMode: () -> String
) : VideoEncoderFactory {

    private val softwareFactory = SoftwareVideoEncoderFactory()
    private val hardwareFactory = HardwareVideoEncoderFactory(eglContext, true, true)
    private val sharedFactory = SharedVideoEncoderFactory(hardwareFactory)

    private val currentFactory: VideoEncoderFactory
        get() = when (getMode()) {
            "Software" -> softwareFactory
            "Hardware" -> hardwareFactory
            else -> sharedFactory // "Shared HW"
        }

    override fun createEncoder(codecInfo: VideoCodecInfo?): VideoEncoder? {
        val mode = getMode()
        Log.d("WebRTC_Factory", "DynamicVideoEncoderFactory: 인코더 생성 요청됨. 모드: $mode, 코덱: ${codecInfo?.name}")
        return currentFactory.createEncoder(codecInfo)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val mode = getMode()
        val codecs = currentFactory.supportedCodecs
        Log.d("WebRTC_Factory", "DynamicVideoEncoderFactory: 지원 코덱 쿼리됨. 모드: $mode, 코덱 개수: ${codecs.size}")
        return codecs
    }
}

@Composable // UI 돌아가는거
fun VideoPlayer(videoTrack: VideoTrack?, eglBase: EglBase, modifier: Modifier = Modifier) {
    // 렌더러 인스턴스를 상태로 관리하여 Effect 블록에서 접근 가능하도록 함
    val rendererState = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView( // AndroidView의 factory에서 생성된 renderer를 직접 관리.
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(false)
                setMirror(false) // 셀카처럼 보이게하는 미러링 옵션. False로 해제 설정.
                rendererState.value = this
            }
        },
        modifier = modifier.fillMaxSize(),
        onRelease = { view ->
            rendererState.value = null
            view.release()
        }
    )

    // videoTrack이나 rendererState가 변경될 때마다 안전하게 결합 및 해제 수행
    DisposableEffect(videoTrack, rendererState.value) {
        val currentRenderer = rendererState.value

        if (videoTrack != null && currentRenderer != null) {
            videoTrack.addSink(currentRenderer)
        }

        onDispose {
            if (videoTrack != null && currentRenderer != null) {
                videoTrack.removeSink(currentRenderer)
            }
        }
    }
}