import React, { useEffect, useRef } from 'react';

// Video 컴포넌트가 받을 props의 타입을 정의
interface VideoProps {
  peerId: string; // 학생 ID
  stream: MediaStream; // WebRTC 미디어 스트림 객체
}

/**
 * 개별 학생의 비디오 스트림을 렌더링하는 컴포넌트
 * @param peerId - 비디오의 소유자인 학생의 ID
 * @param stream - 표시할 MediaStream 객체
 */
// 리액트 컴포넌트는 대문자로 시작
// Video가 React의 함수형 컴포넌트라는 것을 타입으로 지정, FC: Function Component
const Video: React.FC<VideoProps> = ({ peerId: peerId, stream}) => {
  // video HTML 요소에 접근하기 위해 useRef를 사용
    const videoRef = useRef<HTMLVideoElement>(null);

  // stream이 변경될 때마다 video 요소에 스트림을 연결합니다.
  useEffect(() => {
    if (videoRef.current && stream) {
      videoRef.current.srcObject = stream;
    }
  }, [stream]);

return (
  <div
    style={{
      position: 'relative',
      width: '1280px', // 수신 원본 비디오 너비
      height: '720px', // 수신 원본 비디오 높이
      backgroundColor: '#2c2c2c',
      borderRadius: '8px',
      overflow: 'hidden',
      margin: '10px',
      boxShadow: '0 4px 8px rgba(0,0,0,0.2)',
    }}
  >
    {(
      // 비디오가 활성화된 경우, video 태그를 렌더링합니다.
      <video
        ref={videoRef}
        autoPlay // 스트림이 연결되면 자동으로 재생합니다.
        playsInline // iOS에서 전체 화면으로 전환되지 않도록 합니다.
        muted
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'contain', // 원본 비율을 유지하고 전체 프레임을 표시합니다.
        }}
      />
    )}

    {/* 왼쪽 상단에 '내가 수신한 Stream' 표시 */}
    <div
      style={{
        position: 'absolute',
        top: '8px',
        left: '8px',
        backgroundColor: 'rgba(0, 0, 0, 0.5)',
        color: 'white',
        padding: '4px 8px',
        borderRadius: '4px',
        fontSize: '0.9rem',
      }}
    >
      내가 수신한 Stream
    </div>

    {/* 비디오 하단에 피어 ID를 표시 */}
    <div
      style={{
        position: 'absolute',
        bottom: '8px',
        left: '8px',
        backgroundColor: 'rgba(0, 0, 0, 0.5)',
        color: 'white',
        padding: '4px 8px',
        borderRadius: '4px',
        fontSize: '0.9rem',
      }}
    >
      {peerId}
    </div>
  </div>
);

};

export default Video;
