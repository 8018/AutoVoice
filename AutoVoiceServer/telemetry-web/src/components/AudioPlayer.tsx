interface Props {
  src: string;
  fileName?: string;
}

/** 音频回放：WAV 播放 + 下载链接（audioPath 为相对文件名，已拼好完整 URL）。 */
export default function AudioPlayer({ src, fileName }: Props) {
  return (
    <div className="audio-player">
      <h3>音频回放</h3>
      <audio controls preload="metadata" src={src} />
      <a className="dl" href={src} download={fileName}>
        下载 WAV
      </a>
    </div>
  );
}
