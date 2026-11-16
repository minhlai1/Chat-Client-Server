package com.mycompany.bai4.client;

import com.mycompany.bai4.net.UdpCallEngine;
import com.mycompany.bai4.ui.CallWindow;
import com.mycompany.bai4.ui.ChatUI;
import com.mycompany.bai4.ui.LoginDialog;
import com.mycompany.bai4.ui.RegisterDialog;
import com.mycompany.bai4.server.ChatServer;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;

public class ChatClient {

    public enum CallState {
        IDLE, CALLING, RINGING, ACCEPTED, CONNECTING, CONNECTED,
        REJECTED, BUSY, CANCELLED, ENDED, FAILED, TIMEOUT
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new ChatClient().launch();
        });
    }

    private Socket         socket;
    private BufferedReader netIn;
    private PrintWriter    netOut;

    private String username;
    private String displayName;

    private ChatUI window;
    private final Map<String, String> userDisplayMap = new HashMap<>();
    private final File downloadDir;

    private volatile CallState callState = CallState.IDLE;
    private volatile long currentCallId = 0L;
    private volatile String callPeerUser = null;
    private volatile String callPeerDisplay = null;
    private volatile boolean currentVideoCall = false;
    private volatile String pendingRemoteIp = null;
    private volatile int pendingRemoteUdpPort = -1;

    private UdpCallEngine udpEngine;
    private CallWindow callWindow;
    private javax.swing.Timer callRingTimeoutTimer;

    public ChatClient() {
        downloadDir = new File(System.getProperty("user.home"),
                "Downloads" + File.separator + "ChatFiles");
        downloadDir.mkdirs();
    }

    public void launch() {
        showLogin("localhost", ChatServer.PORT, "");
    }

    private void showLogin(String host, int port, String prefillUser) {
        LoginDialog dlg = LoginDialog.showAndGet(host, port, prefillUser);

        if (dlg.openRegisterFlag) {
            String lastHost = (host != null && !host.isEmpty()) ? host : "localhost";
            showRegister(lastHost, ChatServer.PORT);
            return;
        }

        LoginDialog.Result r = dlg.getResult();
        if (r == null) return;

        try {
            openSocket(r.host, r.port);
            String err = doAuth(false, r.username, r.password, null);
            if (err != null) {
                closeSocket();
                JOptionPane.showMessageDialog(null, err, "Đăng nhập thất bại", JOptionPane.ERROR_MESSAGE);
                showLogin(r.host, r.port, r.username);
                return;
            }
            netOut.println("JOIN:" + username);
            window = new ChatUI(this, username, displayName, r.host);
            window.setVisible(true);
            startReceiver();
            sendLine("GET_HISTORY");

        } catch (IOException ex) {
            closeSocket();
            JOptionPane.showMessageDialog(null,
                    "Không thể kết nối tới " + r.host + ":" + r.port + "\n" + ex.getMessage(),
                    "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            showLogin(r.host, r.port, r.username);
        }
    }

    private void showRegister(String host, int port) {
        RegisterDialog dlg = RegisterDialog.showAndGet(host, port);

        if (dlg.openLoginFlag) {
            showLogin(host, port, "");
            return;
        }

        RegisterDialog.Result r = dlg.getResult();
        if (r == null) return;

        try {
            openSocket(r.host, r.port);
            String err = doAuth(true, r.username, r.password, r.displayName);
            if (err != null) {
                closeSocket();
                JOptionPane.showMessageDialog(null, err, "Đăng ký thất bại", JOptionPane.ERROR_MESSAGE);
                showRegister(r.host, r.port);
                return;
            }
            closeSocket();
            JOptionPane.showMessageDialog(null,
                    "Đăng ký tài khoản \"" + r.username + "\" thành công!\nVui lòng đăng nhập để vào phòng chat.",
                    "Đăng ký thành công", JOptionPane.INFORMATION_MESSAGE);
            showLogin(r.host, r.port, r.username);

        } catch (IOException ex) {
            closeSocket();
            JOptionPane.showMessageDialog(null,
                    "Không thể kết nối tới " + r.host + ":" + r.port + "\n" + ex.getMessage(),
                    "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            showRegister(r.host, r.port);
        }
    }

    private void openSocket(String host, int port) throws IOException {
        socket = new Socket(host, port);
        netIn  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));
        netOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; netIn = null; netOut = null;
    }

    private String doAuth(boolean isRegister, String user, String pwd, String dn) throws IOException {
        if (isRegister) {
            netOut.println("REGISTER:" + user + ":" + pwd + ":" + dn);
        } else {
            netOut.println("LOGIN:" + user + ":" + pwd);
        }
        String resp = netIn.readLine();
        if (resp == null) return "Server đóng kết nối bất ngờ.";
        if (resp.startsWith("AUTH_OK:")) {
            String[] parts = resp.substring(8).split(":", 2);
            username    = parts[0].trim();
            displayName = parts.length > 1 ? parts[1].trim() : username;
            return null;
        }
        if (resp.startsWith("AUTH_FAIL:")) return resp.substring(10);
        return "Phản hồi không hợp lệ: " + resp;
    }

    private void sendLine(String line) {
        if (netOut != null) netOut.println(line);
    }

    public void closeConnection() {
        cleanupCallResources(CallState.ENDED);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public void sendMessage(String text) {
        sendLine("MSG:" + username + ":" + text);
    }

    public void sendPrivateMessage(String toUsername, String text) {
        sendLine("PM:" + username + ":" + toUsername + ":" + text);
    }

    public void requestPrivateHistory(String withUsername) {
        sendLine("GET_PM_HISTORY:" + withUsername);
    }

    public void sendFile(File file, String toUsername) {
        new Thread(() -> doSendFile(file, toUsername)).start();
    }

    private void doSendFile(File file, String toUsername) {
        String convId = "ALL".equalsIgnoreCase(toUsername) ? ChatUI.GROUP_ID : toUsername;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String b64   = Base64.getEncoder().encodeToString(bytes);
            sendLine("FILE_META:" + username + ":" + toUsername + ":" + file.getName() + ":" + file.length());
            sendLine("FILE_DATA:" + username + ":" + toUsername + ":" + file.getName() + ":" + b64);
            SwingUtilities.invokeLater(() ->
                    window.appendFileCard(convId, displayName, file.getName(), file.length(), file, true));
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() ->
                    window.appendSystem(ChatUI.GROUP_ID, "Lỗi gửi tệp: " + ex.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Quản lý Cuộc gọi Thoại & Video (Signaling TCP + Media UDP)
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void startCall(String targetUser, String targetDisplay, boolean isVideo) {
        if (callState != CallState.IDLE) {
            JOptionPane.showMessageDialog(window,
                    "Bạn đang trong một cuộc gọi khác.",
                    "Không thể gọi", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            currentCallId    = System.currentTimeMillis() ^ System.nanoTime();
            callPeerUser     = targetUser;
            callPeerDisplay  = targetDisplay;
            currentVideoCall = isVideo;
            callState        = CallState.CALLING;

            udpEngine = new UdpCallEngine(currentCallId, isVideo, createEngineListener());
            int localUdpPort = udpEngine.getLocalUdpPort();
            String localLanIp = UdpCallEngine.detectLocalLanIp(socket);

            callWindow = new CallWindow(window, this, currentCallId, targetUser, targetDisplay, isVideo, false);
            callWindow.setVisible(true);

            String callTypeStr = isVideo ? "VIDEO" : "VOICE";
            sendLine("CALL_REQ:" + currentCallId + ":" + targetUser + ":" + callTypeStr
                    + ":" + localLanIp + ":" + localUdpPort);

            startRingTimeoutTimer();

        } catch (SocketException ex) {
            cleanupCallResources(CallState.FAILED);
            JOptionPane.showMessageDialog(window,
                    "Không thể khởi tạo UDP Socket: " + ex.getMessage(),
                    "Lỗi mạng", JOptionPane.ERROR_MESSAGE);
        }
    }

    public synchronized void acceptIncomingCall() {
        if (callState != CallState.RINGING) return;
        stopRingTimeoutTimer();
        try {
            callState = CallState.ACCEPTED;
            udpEngine = new UdpCallEngine(currentCallId, currentVideoCall, createEngineListener());
            int localUdpPort = udpEngine.getLocalUdpPort();
            String localLanIp = UdpCallEngine.detectLocalLanIp(socket);

            if (callWindow != null) {
                callWindow.showConnectingState();
            }

            callState = CallState.CONNECTING;
            sendLine("CALL_ACCEPT:" + currentCallId + ":" + localLanIp + ":" + localUdpPort);
            udpEngine.startHandshakeAndMedia(pendingRemoteIp, pendingRemoteUdpPort);

        } catch (Exception ex) {
            sendLine("CALL_REJECT:" + currentCallId);
            cleanupCallResources(CallState.FAILED);
            JOptionPane.showMessageDialog(window,
                    "Không thể thiết lập UDP Socket: " + ex.getMessage(),
                    "Lỗi cuộc gọi", JOptionPane.ERROR_MESSAGE);
        }
    }

    public synchronized void rejectIncomingCall() {
        if (callState != CallState.RINGING) return;
        long id = currentCallId;
        sendLine("CALL_REJECT:" + id);
        cleanupCallResources(CallState.REJECTED);
    }

    public synchronized void onUserHangupFromUI() {
        if (callState == CallState.IDLE) return;
        long id = currentCallId;
        String peer = callPeerUser;
        if (callState == CallState.CALLING) {
            sendLine("CALL_CANCEL:" + id);
            cleanupCallResources(CallState.CANCELLED);
            if (window != null && peer != null) {
                window.appendSystem(peer, "Đã hủy cuộc gọi");
            }
        } else if (callState == CallState.RINGING) {
            sendLine("CALL_REJECT:" + id);
            cleanupCallResources(CallState.REJECTED);
        } else {
            sendLine("CALL_END:" + id);
            cleanupCallResources(CallState.ENDED);
            if (window != null && peer != null) {
                window.appendSystem(peer, "Cuộc gọi đã kết thúc");
            }
        }
    }

    public void setCallMicMuted(boolean muted) {
        if (udpEngine != null) udpEngine.setMicMuted(muted);
    }

    public void setCallCameraEnabled(boolean enabled) {
        if (udpEngine != null) udpEngine.setCameraEnabled(enabled);
    }

    private UdpCallEngine.CallEngineListener createEngineListener() {
        return new UdpCallEngine.CallEngineListener() {
            @Override
            public void onUdpConnected() {
                callState = CallState.CONNECTED;
                if (callWindow != null) {
                    callWindow.showConnectedState();
                }
            }

            @Override
            public void onRemoteVideoFrame(BufferedImage frame) {
                if (callWindow != null) {
                    callWindow.updateRemoteVideo(frame);
                }
            }

            @Override
            public void onLocalVideoFrame(BufferedImage frame) {
                if (callWindow != null) {
                    callWindow.updateLocalVideo(frame);
                }
            }

            @Override
            public void onRemoteCameraOff() {
                if (callWindow != null) {
                    callWindow.showRemoteCameraOff();
                }
            }

            @Override
            public void onConnectionLost(String reason) {
                String peer = callPeerUser;
                long id = currentCallId;
                sendLine("CALL_END:" + id);
                cleanupCallResources(CallState.FAILED);
                SwingUtilities.invokeLater(() -> {
                    if (window != null && peer != null) {
                        window.appendSystem(peer, reason);
                    }
                    JOptionPane.showMessageDialog(window, reason, "Kết thúc cuộc gọi", JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void onPeerEndedCall() {
                String peer = callPeerUser;
                cleanupCallResources(CallState.ENDED);
                SwingUtilities.invokeLater(() -> {
                    if (window != null && peer != null) {
                        window.appendSystem(peer, "Cuộc gọi đã kết thúc");
                    }
                });
            }
        };
    }

    private void startRingTimeoutTimer() {
        stopRingTimeoutTimer();
        callRingTimeoutTimer = new javax.swing.Timer(35000, e -> {
            if (callState == CallState.CALLING || callState == CallState.RINGING) {
                long id = currentCallId;
                String peer = callPeerUser;
                if (callState == CallState.CALLING) {
                    sendLine("CALL_CANCEL:" + id);
                }
                cleanupCallResources(CallState.TIMEOUT);
                if (window != null && peer != null) {
                    window.appendSystem(peer, "Cuộc gọi nhỡ (Quá thời gian chờ)");
                }
            }
        });
        callRingTimeoutTimer.setRepeats(false);
        callRingTimeoutTimer.start();
    }

    private void stopRingTimeoutTimer() {
        if (callRingTimeoutTimer != null) {
            callRingTimeoutTimer.stop();
            callRingTimeoutTimer = null;
        }
    }

    private synchronized void cleanupCallResources(CallState finalState) {
        stopRingTimeoutTimer();
        callState = finalState;
        if (udpEngine != null) {
            udpEngine.stop();
            udpEngine = null;
        }
        if (callWindow != null) {
            callWindow.closeWindow();
            callWindow = null;
        }
        currentCallId = 0L;
        callPeerUser = null;
        callPeerDisplay = null;
        pendingRemoteIp = null;
        pendingRemoteUdpPort = -1;
        callState = CallState.IDLE;
    }

    private void startReceiver() {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = netIn.readLine()) != null) handleIncoming(line);
            } catch (IOException e) {
                cleanupCallResources(CallState.FAILED);
                SwingUtilities.invokeLater(() -> { if (window != null) window.setDisconnected(); });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void handleIncoming(String line) {
        if (line.startsWith("MSG:")) {
            String[] p = line.split(":", 4);
            if (p.length == 4) {
                String fromUser = p[1];
                String fromDN   = p[2];
                String text     = p[3];
                boolean isSelf  = fromUser.equals(this.username);
                String now      = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                SwingUtilities.invokeLater(() ->
                        window.appendMessage(ChatUI.GROUP_ID, isSelf ? displayName : fromDN, text, isSelf, now));
            }

        } else if (line.startsWith("PM:")) {
            String[] p = line.split(":", 4);
            if (p.length == 4) {
                String fromUser = p[1];
                String fromDN   = p[2];
                String text     = p[3];
                boolean isSelf  = fromUser.equals(this.username);
                String now      = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                SwingUtilities.invokeLater(() ->
                        window.appendMessage(fromUser, fromDN, text, isSelf, now));
            }

        } else if (line.startsWith("SERVER:")) {
            String msg = line.substring(7);
            SwingUtilities.invokeLater(() -> window.appendSystem(ChatUI.GROUP_ID, msg));

        } else if (line.startsWith("LAST_PM_PREVIEW:")) {
            // LAST_PM_PREVIEW:<otherUser>:<fromUser>:<fromDisplay>:<sentAt>:<content>
            String[] p = line.split(":", 6);
            if (p.length == 6) {
                String otherUser = p[1];
                String fromUser  = p[2];
                String fromDN    = p[3];
                String sentAt    = p[4];
                String content   = p[5];
                boolean isSelf   = fromUser.equals(this.username);
                String senderLbl = isSelf ? displayName : fromDN;
                String timeOnly  = sentAt.contains(" ") ? sentAt.split(" ")[0].replace(".", ":") : sentAt;
                SwingUtilities.invokeLater(() ->
                        window.updateConversationPreview(otherUser, senderLbl, content, isSelf, timeOnly));
            }

        } else if (line.startsWith("HISTORY_MSG:")) {
            String[] p = line.split(":", 6);
            if (p.length == 6) {
                String convKey  = p[1];
                String fromUser = p[2];
                String fromDN   = p[3];
                String sentAt   = p[4];
                String content  = p[5];
                boolean isSelf  = fromUser.equals(this.username);
                String convId   = "GROUP".equals(convKey) ? ChatUI.GROUP_ID : convKey;
                String label    = isSelf ? displayName : fromDN;

                if (content.startsWith("[FILE_DATA]:")) {
                    String[] fp = content.split(":", 3);
                    if (fp.length == 3) {
                        String fileName = fp[1];
                        String b64      = fp[2];
                        restoreHistoryFile(convId, label, fileName, b64, isSelf, sentAt);
                    }
                } else {
                    SwingUtilities.invokeLater(() ->
                            window.appendMessage(convId, label, content, isSelf, sentAt));
                }
            }

        } else if (line.startsWith("HISTORY_END:")) {
            String convKey = line.substring(12);
            String convId  = "GROUP".equals(convKey) ? ChatUI.GROUP_ID : convKey;
            SwingUtilities.invokeLater(() ->
                    window.appendSystem(convId, "Lịch sử tin nhắn"));

        } else if (line.startsWith("USERS:")) {
            String payload = line.substring(6);
            String[] entries = payload.isEmpty() ? new String[0] : payload.split(",");
            List<String[]> pairs = new ArrayList<>();
            for (String entry : entries) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) pairs.add(new String[]{parts[0], parts[1]});
            }
            userDisplayMap.clear();
            for (String[] pair : pairs) userDisplayMap.put(pair[1], pair[0]);
            SwingUtilities.invokeLater(() -> window.updateUserList(pairs));

        } else if (line.startsWith("FILE_DATA:")) {
            String[] p = line.split(":", 5);
            if (p.length == 5) {
                String fromUser = p[1];
                String toUser   = p[2];
                String convId   = "ALL".equalsIgnoreCase(toUser) ? ChatUI.GROUP_ID : fromUser;
                boolean isSelf  = fromUser.equals(this.username);
                receiveFile(convId, fromUser, p[3], p[4], isSelf);
            }

        } else if (line.startsWith("CALL_")) {
            handleIncomingCallSignal(line);
        }
    }

    private synchronized void handleIncomingCallSignal(String line) {
        if (line.startsWith("CALL_INCOMING:")) {
            String[] p = line.split(":", 7);
            if (p.length < 7) return;
            long callId = Long.parseLong(p[1]);
            String callerUser = p[2];
            String callerDN   = p[3];
            boolean isVideo   = "VIDEO".equalsIgnoreCase(p[4]);
            String callerIp   = p[5];
            int callerPort    = Integer.parseInt(p[6]);

            if (callState != CallState.IDLE) {
                sendLine("CALL_REJECT:" + callId);
                return;
            }

            currentCallId        = callId;
            callPeerUser         = callerUser;
            callPeerDisplay      = callerDN;
            currentVideoCall     = isVideo;
            pendingRemoteIp      = callerIp;
            pendingRemoteUdpPort = callerPort;
            callState            = CallState.RINGING;

            SwingUtilities.invokeLater(() -> {
                callWindow = new CallWindow(window, this, callId, callerUser, callerDN, isVideo, true);
                callWindow.setVisible(true);
            });
            startRingTimeoutTimer();

        } else if (line.startsWith("CALL_ACCEPTED:")) {
            String[] p = line.split(":", 4);
            if (p.length < 4) return;
            long callId = Long.parseLong(p[1]);
            if (callId != currentCallId || callState != CallState.CALLING) return;

            stopRingTimeoutTimer();
            String calleeIp = p[2];
            int calleePort  = Integer.parseInt(p[3]);
            callState = CallState.CONNECTING;

            if (callWindow != null) {
                callWindow.showConnectingState();
            }
            try {
                udpEngine.startHandshakeAndMedia(calleeIp, calleePort);
            } catch (UnknownHostException ex) {
                sendLine("CALL_END:" + callId);
                cleanupCallResources(CallState.FAILED);
            }

        } else if (line.startsWith("CALL_REJECTED:")) {
            long callId = Long.parseLong(line.substring(14).trim());
            if (callId != currentCallId) return;
            String peer = callPeerUser;
            String peerDN = callPeerDisplay;
            cleanupCallResources(CallState.REJECTED);
            SwingUtilities.invokeLater(() -> {
                if (window != null && peer != null) {
                    window.appendSystem(peer, peerDN + " đã từ chối cuộc gọi");
                }
                JOptionPane.showMessageDialog(window,
                        peerDN + " đã từ chối cuộc gọi.",
                        "Từ chối cuộc gọi", JOptionPane.INFORMATION_MESSAGE);
            });

        } else if (line.startsWith("CALL_BUSY:")) {
            String[] p = line.split(":", 3);
            long callId = Long.parseLong(p[1]);
            if (callId != currentCallId) return;
            String peer = callPeerUser;
            String peerDN = callPeerDisplay;
            cleanupCallResources(CallState.BUSY);
            SwingUtilities.invokeLater(() -> {
                if (window != null && peer != null) {
                    window.appendSystem(peer, peerDN + " đang bận");
                }
                JOptionPane.showMessageDialog(window,
                        peerDN + " hiện đang trong một cuộc gọi khác.",
                        "Người nhận đang bận", JOptionPane.WARNING_MESSAGE);
            });

        } else if (line.startsWith("CALL_OFFLINE:")) {
            String[] p = line.split(":", 3);
            long callId = Long.parseLong(p[1]);
            if (callId != currentCallId) return;
            String peerDN = callPeerDisplay;
            cleanupCallResources(CallState.FAILED);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window,
                    peerDN + " hiện không trực tuyến.",
                    "Không thể kết nối", JOptionPane.WARNING_MESSAGE));

        } else if (line.startsWith("CALL_CANCELLED:")) {
            long callId = Long.parseLong(line.substring(15).trim());
            if (callId != currentCallId) return;
            String peer = callPeerUser;
            String peerDN = callPeerDisplay;
            cleanupCallResources(CallState.CANCELLED);
            SwingUtilities.invokeLater(() -> {
                if (window != null && peer != null) {
                    window.appendSystem(peer, "Cuộc gọi nhỡ từ " + peerDN);
                }
            });

        } else if (line.startsWith("CALL_ENDED:")) {
            long callId = Long.parseLong(line.substring(11).trim());
            if (callId != currentCallId) return;
            String peer = callPeerUser;
            cleanupCallResources(CallState.ENDED);
            SwingUtilities.invokeLater(() -> {
                if (window != null && peer != null) {
                    window.appendSystem(peer, "Cuộc gọi đã kết thúc");
                }
            });
        }
    }

    private void receiveFile(String convId, String fromUser, String name, String b64, boolean isSelf) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            File   out   = resolveUniqueFile(downloadDir, name);
            Files.write(out.toPath(), bytes);
            String fromDN = userDisplayMap.getOrDefault(fromUser, fromUser);
            SwingUtilities.invokeLater(() ->
                    window.appendFileCard(convId, fromDN, out.getName(), out.length(), out, isSelf));
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() ->
                    window.appendSystem(ChatUI.GROUP_ID, "Lỗi lưu tệp: " + ex.getMessage()));
        }
    }

    private void restoreHistoryFile(String convId, String senderLabel, String name, String b64, boolean isSelf, String sentAt) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            File   out   = new File(downloadDir, name);
            if (!out.exists()) {
                Files.write(out.toPath(), bytes);
            }
            String timeOnly = sentAt.contains(" ") ? sentAt.split(" ")[0].replace(".", ":") : sentAt;
            SwingUtilities.invokeLater(() ->
                    window.appendFileCard(convId, senderLabel, out.getName(), out.length(), out, isSelf, timeOnly));
        } catch (Exception ignored) {}
    }

    private File resolveUniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.'))    : "";
        int i = 1;
        while (f.exists()) f = new File(dir, base + "_" + i++ + ext);
        return f;
    }
}