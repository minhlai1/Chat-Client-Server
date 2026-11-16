package com.mycompany.bai4.net;

import com.github.sarxos.webcam.Webcam;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ máy truyền thông UDP trực tiếp (P2P LAN) cho Voice Call và Video Call.
 * Đảm nhận:
 * - Bắt tay UDP 2 chiều (PROBE/ACK) + Heartbeat + Timeout phát hiện rớt mạng.
 * - Thu âm Microphone khung 20ms, nén G.711 u-Law, Jitter Buffer và phát ra Loa.
 * - Thu hình Webcam thực tế, nén JPEG, cắt mảnh <= 1200 bytes, ghép mảnh hoàn chỉnh và hiển thị.
 */
public class UdpCallEngine {

    public interface CallEngineListener {
        void onUdpConnected();
        void onRemoteVideoFrame(BufferedImage frame);
        void onLocalVideoFrame(BufferedImage frame);
        void onRemoteCameraOff();
        void onConnectionLost(String reason);
        void onPeerEndedCall();
    }

    // Cấu hình Audio: 16kHz, 16-bit, Mono -> mỗi khung 20ms = 320 mẫu = 640 bytes PCM -> nén u-Law còn 320 bytes
    private static final float AUDIO_SAMPLE_RATE = 16000.0f;
    private static final int AUDIO_FRAME_SAMPLES = 320; // 20ms tại 16kHz
    private static final int PCM_FRAME_BYTES     = AUDIO_FRAME_SAMPLES * 2; // 640 bytes
    private static final int JITTER_BUFFER_PREFILL = 3; // Đệm 3 khung (60ms) để chống giật

    // Cấu hình Timeout & Heartbeat
    private static final long HANDSHAKE_TIMEOUT_MS = 12000;
    private static final long HEARTBEAT_INTERVAL_MS = 1000;
    private static final long PEER_TIMEOUT_MS = 6500;

    private final long callId;
    private final boolean videoCall;
    private final CallEngineListener listener;

    private DatagramSocket udpSocket;
    private volatile InetAddress peerAddress;
    private volatile int peerPort = -1;

    private final AtomicBoolean running       = new AtomicBoolean(false);
    private final AtomicBoolean udpVerified   = new AtomicBoolean(false);
    private final AtomicBoolean mediaStarted  = new AtomicBoolean(false);
    private final AtomicBoolean micMuted      = new AtomicBoolean(false);
    private final AtomicBoolean camEnabled    = new AtomicBoolean(true);

    private final AtomicInteger seqCounter    = new AtomicInteger(0);
    private final AtomicInteger audioFrameSeq = new AtomicInteger(0);
    private final AtomicInteger videoFrameSeq = new AtomicInteger(0);

    private volatile long lastPacketReceivedAt = 0L;

    // Thiết bị phần cứng
    private TargetDataLine microphoneLine;
    private SourceDataLine speakerLine;
    private Webcam webcam;

    // Jitter Buffer cho âm thanh và bộ ghép mảnh cho Video
    private final PriorityBlockingQueue<AudioChunk> audioJitterQueue =
            new PriorityBlockingQueue<>(32, Comparator.comparingInt(a -> a.seq));
    private final ConcurrentHashMap<Integer, VideoFrameAssembler> videoAssemblers = new ConcurrentHashMap<>();
    private volatile int lastRenderedFrameId = -1;

    private final List<Thread> workerThreads = new CopyOnWriteArrayList<>();

    private record AudioChunk(int seq, long timestamp, byte[] pcmData) {}

    private static class VideoFrameAssembler {
        final int frameId;
        final int totalFragments;
        final long createdAt;
        final byte[][] fragments;
        int receivedCount = 0;

        VideoFrameAssembler(int frameId, int totalFragments) {
            this.frameId = frameId;
            this.totalFragments = totalFragments;
            this.createdAt = System.currentTimeMillis();
            this.fragments = new byte[totalFragments][];
        }

        synchronized boolean addFragment(int index, byte[] data) {
            if (index < 0 || index >= totalFragments) return false;
            if (fragments[index] == null) {
                fragments[index] = data;
                receivedCount++;
            }
            return receivedCount == totalFragments;
        }

        synchronized byte[] assemble() {
            int totalBytes = 0;
            for (byte[] f : fragments) {
                if (f == null) return null;
                totalBytes += f.length;
            }
            byte[] full = new byte[totalBytes];
            int pos = 0;
            for (byte[] f : fragments) {
                System.arraycopy(f, 0, full, pos, f.length);
                pos += f.length;
            }
            return full;
        }
    }

    public UdpCallEngine(long callId, boolean videoCall, CallEngineListener listener) throws SocketException {
        this.callId = callId;
        this.videoCall = videoCall;
        this.listener = listener;
        this.udpSocket = new DatagramSocket(0);
        this.udpSocket.setReceiveBufferSize(512 * 1024);
        this.udpSocket.setSendBufferSize(512 * 1024);
    }

    public int getLocalUdpPort() {
        return udpSocket != null ? udpSocket.getLocalPort() : -1;
    }

    public boolean isMicMuted() {
        return micMuted.get();
    }

    public void setMicMuted(boolean muted) {
        micMuted.set(muted);
    }

    public boolean isCameraEnabled() {
        return camEnabled.get();
    }

    public void setCameraEnabled(boolean enabled) {
        camEnabled.set(enabled);
        if (!enabled) {
            sendControlPacket(UdpPacket.TYPE_CONTROL_CAM_OFF);
        }
    }

    /**
     * Bắt đầu lắng nghe UDP và thực hiện bắt tay 2 chiều với Peer.
     */
    public void startHandshakeAndMedia(String remoteIp, int remotePort) throws UnknownHostException {
        this.peerAddress = InetAddress.getByName(remoteIp);
        this.peerPort = remotePort;

        if (running.compareAndSet(false, true)) {
            lastPacketReceivedAt = System.currentTimeMillis();
            startThread("UDP-Receiver", this::receiveLoop);
            startThread("UDP-Handshake-Heartbeat", this::handshakeAndHeartbeatLoop);
        }
    }

    private void startThread(String name, Runnable task) {
        Thread t = new Thread(task, name + "-" + callId);
        t.setDaemon(true);
        workerThreads.add(t);
        t.start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Luồng Bắt tay UDP 2 chiều (PROBE / ACK) & Kiểm tra kết nối (Heartbeat)
    // ══════════════════════════════════════════════════════════════════════════

    private void handshakeAndHeartbeatLoop() {
        long startWait = System.currentTimeMillis();
        try {
            // Giai đoạn 1: Gửi PROBE liên tục mỗi 150ms cho tới khi xác nhận UDP 2 chiều
            while (running.get() && !udpVerified.get()) {
                if (System.currentTimeMillis() - startWait > HANDSHAKE_TIMEOUT_MS) {
                    if (listener != null) {
                        listener.onConnectionLost("Không thể thiết lập đường truyền UDP trực tiếp (Timeout).");
                    }
                    stop();
                    return;
                }
                sendControlPacket(UdpPacket.TYPE_CONTROL_PROBE);
                Thread.sleep(150);
            }

            // Giai đoạn 2: Đã kết nối -> Gửi HEARTBEAT định kỳ và kiểm tra Timeout mất mạng
            while (running.get()) {
                sendControlPacket(UdpPacket.TYPE_CONTROL_HEARTBEAT);
                long silenceMs = System.currentTimeMillis() - lastPacketReceivedAt;
                if (silenceMs > PEER_TIMEOUT_MS) {
                    if (listener != null) {
                        listener.onConnectionLost("Mất kết nối mạng với người đang gọi.");
                    }
                    stop();
                    return;
                }
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            }
        } catch (InterruptedException ignored) {}
    }

    private void onHandshakeVerified() {
        if (udpVerified.compareAndSet(false, true)) {
            lastPacketReceivedAt = System.currentTimeMillis();
            if (listener != null) {
                listener.onUdpConnected();
            }
            startMediaStreams();
        }
    }

    private void startMediaStreams() {
        if (!mediaStarted.compareAndSet(false, true)) return;

        startThread("UDP-Audio-Capture", this::audioCaptureLoop);
        startThread("UDP-Audio-Playback", this::audioPlaybackLoop);

        if (videoCall) {
            startThread("UDP-Video-Capture", this::videoCaptureLoop);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Luồng Nhận gói tin UDP & Phân loại
    // ══════════════════════════════════════════════════════════════════════════

    private void receiveLoop() {
        byte[] buf = new byte[UdpPacket.MAX_PACKET_SIZE + 64];
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                udpSocket.receive(dp);

                // Bảo mật: Chỉ nhận gói từ đúng IP của Peer đã xác thực
                if (peerAddress != null && !dp.getAddress().equals(peerAddress)) {
                    continue;
                }

                UdpPacket pkt = UdpPacket.fromBytes(dp.getData(), dp.getOffset(), dp.getLength());
                if (pkt == null || pkt.getCallId() != this.callId) {
                    continue; // Bỏ qua gói rác hoặc gói từ cuộc gọi cũ
                }

                // Cập nhật cổng thực tế nếu có dịch chuyển cổng nội bộ
                if (peerPort != dp.getPort()) {
                    peerPort = dp.getPort();
                }

                lastPacketReceivedAt = System.currentTimeMillis();

                switch (pkt.getPacketType()) {
                    case UdpPacket.TYPE_CONTROL_PROBE -> {
                        sendControlPacket(UdpPacket.TYPE_CONTROL_ACK);
                        onHandshakeVerified();
                    }
                    case UdpPacket.TYPE_CONTROL_ACK -> {
                        onHandshakeVerified();
                    }
                    case UdpPacket.TYPE_CONTROL_HEARTBEAT -> {
                        // Đã cập nhật lastPacketReceivedAt ở trên
                        if (!udpVerified.get()) onHandshakeVerified();
                    }
                    case UdpPacket.TYPE_CONTROL_CAM_OFF -> {
                        if (listener != null) listener.onRemoteCameraOff();
                    }
                    case UdpPacket.TYPE_CONTROL_BYE -> {
                        if (listener != null) listener.onPeerEndedCall();
                        stop();
                    }
                    case UdpPacket.TYPE_AUDIO -> {
                        if (!udpVerified.get()) onHandshakeVerified();
                        handleIncomingAudio(pkt);
                    }
                    case UdpPacket.TYPE_VIDEO -> {
                        if (!udpVerified.get()) onHandshakeVerified();
                        handleIncomingVideo(pkt);
                    }
                }
            } catch (SocketException se) {
                break; // Socket đóng khi kết thúc cuộc gọi
            } catch (IOException ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Xử lý Âm thanh (Microphone -> G.711 u-Law -> UDP -> Jitter Buffer -> Loa)
    // ══════════════════════════════════════════════════════════════════════════

    private AudioFormat getAudioFormat() {
        return new AudioFormat(AUDIO_SAMPLE_RATE, 16, 1, true, false);
    }

    private void audioCaptureLoop() {
        try {
            AudioFormat fmt = getAudioFormat();
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("[UdpCallEngine] Thiết bị Microphone không hỗ trợ định dạng 16kHz PCM.");
                return;
            }
            microphoneLine = (TargetDataLine) AudioSystem.getLine(info);
            microphoneLine.open(fmt, PCM_FRAME_BYTES * 4);
            microphoneLine.start();

            byte[] pcmBuffer = new byte[PCM_FRAME_BYTES];
            while (running.get()) {
                int read = microphoneLine.read(pcmBuffer, 0, pcmBuffer.length);
                if (read <= 0) continue;

                // Nếu người dùng tắt Mic -> Không gửi âm thanh
                if (micMuted.get()) {
                    continue;
                }

                byte[] compressed = encodeMuLaw(pcmBuffer, read);
                int fId = audioFrameSeq.incrementAndGet();
                UdpPacket pkt = new UdpPacket(
                        callId,
                        UdpPacket.TYPE_AUDIO,
                        seqCounter.incrementAndGet(),
                        System.currentTimeMillis(),
                        fId,
                        0,
                        1,
                        compressed
                );
                sendPacket(pkt);
            }
        } catch (Exception e) {
            System.err.println("[UdpCallEngine] Lỗi thu âm Microphone: " + e.getMessage());
        } finally {
            closeMicrophone();
        }
    }

    private void handleIncomingAudio(UdpPacket pkt) {
        byte[] ulaw = pkt.getPayload();
        if (ulaw == null || ulaw.length == 0) return;

        byte[] pcm = decodeMuLaw(ulaw);
        // Tránh tràn Jitter Buffer nếu mạng bị dồn cục
        if (audioJitterQueue.size() > 25) {
            audioJitterQueue.poll();
        }
        audioJitterQueue.offer(new AudioChunk(pkt.getFrameId(), pkt.getTimestamp(), pcm));
    }

    private void audioPlaybackLoop() {
        try {
            AudioFormat fmt = getAudioFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("[UdpCallEngine] Thiết bị Loa không hỗ trợ định dạng 16kHz PCM.");
                return;
            }
            speakerLine = (SourceDataLine) AudioSystem.getLine(info);
            speakerLine.open(fmt, PCM_FRAME_BYTES * 6);
            speakerLine.start();

            int lastPlayedSeq = -1;
            boolean buffering = true;

            while (running.get()) {
                if (buffering) {
                    if (audioJitterQueue.size() < JITTER_BUFFER_PREFILL) {
                        Thread.sleep(10);
                        continue;
                    }
                    buffering = false;
                }

                AudioChunk chunk = audioJitterQueue.poll(25, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    // Thiếu dữ liệu tạm thời -> quay lại chế độ prefill nhẹ
                    buffering = true;
                    continue;
                }

                // Bỏ qua các gói âm thanh đến trễ hơn gói đã phát hoặc quá hạn > 350ms
                if (lastPlayedSeq != -1 && chunk.seq <= lastPlayedSeq) {
                    continue;
                }
                lastPlayedSeq = chunk.seq;
                speakerLine.write(chunk.pcmData, 0, chunk.pcmData.length);
            }
        } catch (Exception e) {
            System.err.println("[UdpCallEngine] Lỗi phát âm thanh: " + e.getMessage());
        } finally {
            closeSpeaker();
        }
    }

    // ── Nén / Giải nén âm thanh G.711 u-Law (giảm 50% băng thông, độ trễ 0ms) ──
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;

    private static byte[] encodeMuLaw(byte[] pcm16LE, int length) {
        int samples = length / 2;
        byte[] out = new byte[samples];
        for (int i = 0; i < samples; i++) {
            int lo = pcm16LE[i * 2] & 0xFF;
            int hi = pcm16LE[i * 2 + 1];
            short sample = (short) ((hi << 8) | lo);
            out[i] = linearToMuLaw(sample);
        }
        return out;
    }

    private static byte[] decodeMuLaw(byte[] ulaw) {
        byte[] pcm16LE = new byte[ulaw.length * 2];
        for (int i = 0; i < ulaw.length; i++) {
            short sample = muLawToLinear(ulaw[i]);
            pcm16LE[i * 2]     = (byte) (sample & 0xFF);
            pcm16LE[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm16LE;
    }

    private static byte linearToMuLaw(short sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > CLIP) sample = CLIP;
        sample = (short) (sample + BIAS);
        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {}
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        byte ulawByte = (byte) ~(sign | (exponent << 4) | mantissa);
        return ulawByte;
    }

    private static short muLawToLinear(byte ulawByte) {
        int u = ~ulawByte & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + BIAS) << exponent;
        sample -= BIAS;
        return (short) (sign != 0 ? -sample : sample);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Xử lý Video (Webcam 640x480 -> JPEG -> Phân mảnh 1200B -> Ghép frame)
    // ══════════════════════════════════════════════════════════════════════════

    private void videoCaptureLoop() {
        try {
            webcam = Webcam.getDefault();
            if (webcam == null) {
                System.err.println("[UdpCallEngine] Không tìm thấy thiết bị Webcam trên máy.");
                return;
            }

            Dimension targetSize = new Dimension(640, 480);
            Dimension[] supported = webcam.getViewSizes();
            boolean has640 = false;
            for (Dimension d : supported) {
                if (d.width == 640 && d.height == 480) {
                    has640 = true;
                    break;
                }
            }
            if (!has640) {
                webcam.setCustomViewSizes(new Dimension[]{targetSize});
            }
            webcam.setViewSize(targetSize);
            webcam.open(true);

            long frameIntervalMs = 1000 / 18; // ~18 FPS ổn định qua LAN

            while (running.get()) {
                long t0 = System.currentTimeMillis();

                if (!camEnabled.get()) {
                    Thread.sleep(80);
                    continue;
                }

                BufferedImage frame = webcam.getImage();
                if (frame != null) {
                    if (listener != null) {
                        listener.onLocalVideoFrame(frame);
                    }
                    byte[] jpegBytes = compressFrameJpeg(frame, 0.62f);
                    if (jpegBytes != null && jpegBytes.length > 0) {
                        sendVideoFrameFragments(jpegBytes);
                    }
                }

                long elapsed = System.currentTimeMillis() - t0;
                long sleep = frameIntervalMs - elapsed;
                if (sleep > 5) {
                    Thread.sleep(sleep);
                }
            }
        } catch (Exception e) {
            System.err.println("[UdpCallEngine] Lỗi Webcam: " + e.getMessage());
        } finally {
            closeWebcam();
        }
    }

    private byte[] compressFrameJpeg(BufferedImage image, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) return null;
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void sendVideoFrameFragments(byte[] jpegBytes) {
        int totalLen = jpegBytes.length;
        int fragCount = (int) Math.ceil((double) totalLen / UdpPacket.MAX_PAYLOAD_SIZE);
        if (fragCount <= 0 || fragCount > 120) return; // Bỏ qua nếu frame quá lớn bất thường

        int fId = videoFrameSeq.incrementAndGet();
        long now = System.currentTimeMillis();

        for (int idx = 0; idx < fragCount; idx++) {
            int offset = idx * UdpPacket.MAX_PAYLOAD_SIZE;
            int len = Math.min(UdpPacket.MAX_PAYLOAD_SIZE, totalLen - offset);
            byte[] slice = Arrays.copyOfRange(jpegBytes, offset, offset + len);

            UdpPacket pkt = new UdpPacket(
                    callId,
                    UdpPacket.TYPE_VIDEO,
                    seqCounter.incrementAndGet(),
                    now,
                    fId,
                    idx,
                    fragCount,
                    slice
            );
            sendPacket(pkt);
        }
    }

    private void handleIncomingVideo(UdpPacket pkt) {
        int fId = pkt.getFrameId();
        // Không nhận mảnh của frame cũ hơn frame đã hiển thị
        if (fId <= lastRenderedFrameId) {
            return;
        }

        int totalFrags = pkt.getFragmentCount();
        int fragIdx = pkt.getFragmentIndex();

        VideoFrameAssembler assembler = videoAssemblers.computeIfAbsent(
                fId, id -> new VideoFrameAssembler(id, totalFrags));

        if (assembler.addFragment(fragIdx, pkt.getPayload())) {
            videoAssemblers.remove(fId);
            lastRenderedFrameId = fId;

            byte[] fullJpeg = assembler.assemble();
            if (fullJpeg != null) {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(fullJpeg));
                    if (img != null && listener != null) {
                        listener.onRemoteVideoFrame(img);
                    }
                } catch (IOException ignored) {}
            }

            // Dọn dẹp các frame cũ bị rớt mảnh hoặc quá hạn (> 250ms)
            long now = System.currentTimeMillis();
            videoAssemblers.entrySet().removeIf(
                    e -> e.getKey() <= fId || (now - e.getValue().createdAt) > 250);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Gửi gói UDP, Xác định IPv4 LAN & Giải phóng tài nguyên
    // ══════════════════════════════════════════════════════════════════════════

    private void sendControlPacket(byte controlType) {
        UdpPacket pkt = new UdpPacket(
                callId,
                controlType,
                seqCounter.incrementAndGet(),
                System.currentTimeMillis(),
                0,
                0,
                1,
                new byte[0]
        );
        sendPacket(pkt);
    }

    private void sendPacket(UdpPacket pkt) {
        if (!running.get() || udpSocket == null || udpSocket.isClosed() || peerAddress == null || peerPort <= 0) {
            return;
        }
        try {
            byte[] raw = pkt.toBytes();
            DatagramPacket dp = new DatagramPacket(raw, raw.length, peerAddress, peerPort);
            udpSocket.send(dp);
        } catch (IOException ignored) {}
    }

    /**
     * Dừng toàn bộ luồng thu/phát, đóng Webcam, đóng Microphone/Loa, đóng Socket UDP và xóa bộ đệm.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            closeResourcesQuietly();
            return;
        }
        try {
            // Gửi báo hiệu BYE trực tiếp qua UDP trước khi đóng
            if (udpSocket != null && !udpSocket.isClosed() && peerAddress != null && peerPort > 0) {
                byte[] byeRaw = new UdpPacket(
                        callId, UdpPacket.TYPE_CONTROL_BYE, seqCounter.incrementAndGet(),
                        System.currentTimeMillis(), 0, 0, 1, new byte[0]
                ).toBytes();
                udpSocket.send(new DatagramPacket(byeRaw, byeRaw.length, peerAddress, peerPort));
            }
        } catch (IOException ignored) {}

        closeResourcesQuietly();

        for (Thread t : workerThreads) {
            t.interrupt();
        }
        workerThreads.clear();
        audioJitterQueue.clear();
        videoAssemblers.clear();
    }

    private void closeResourcesQuietly() {
        closeWebcam();
        closeMicrophone();
        closeSpeaker();
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }

    private void closeWebcam() {
        if (webcam != null) {
            try {
                if (webcam.isOpen()) webcam.close();
            } catch (Exception ignored) {}
            webcam = null;
        }
    }

    private void closeMicrophone() {
        if (microphoneLine != null) {
            try {
                microphoneLine.stop();
                microphoneLine.close();
            } catch (Exception ignored) {}
            microphoneLine = null;
        }
    }

    private void closeSpeaker() {
        if (speakerLine != null) {
            try {
                speakerLine.stop();
                speakerLine.close();
            } catch (Exception ignored) {}
            speakerLine = null;
        }
    }

    /**
     * Xác định địa chỉ IPv4 nội bộ (LAN) chính xác của máy Client.
     * Ưu tiên 1: Lấy từ chính localAddress của TCP Socket đang kết nối với Server.
     * Ưu tiên 2: Quét các card mạng vật lý đang hoạt động (bỏ qua Loopback 127.0.0.1 và card ảo).
     */
    public static String detectLocalLanIp(Socket activeTcpSocket) {
        if (activeTcpSocket != null && activeTcpSocket.isConnected()) {
            InetAddress localAddr = activeTcpSocket.getLocalAddress();
            if (localAddr instanceof Inet4Address && !localAddr.isLoopbackAddress()) {
                return localAddr.getHostAddress();
            }
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallbackSiteLocal = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String display = ni.getDisplayName().toLowerCase();
                if (display.contains("virtual") || display.contains("vmware")
                        || display.contains("vbox") || display.contains("wsl")
                        || display.contains("hyper-v") || display.contains("docker")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            return ip;
                        }
                        fallbackSiteLocal = ip;
                    }
                }
            }
            if (fallbackSiteLocal != null) return fallbackSiteLocal;
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}