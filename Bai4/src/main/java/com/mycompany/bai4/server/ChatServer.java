package com.mycompany.bai4.server;

import com.mycompany.bai4.model.UserStore;
import com.mycompany.bai4.model.MessageStore;
import com.mycompany.bai4.model.MessageStore.Message;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {

    public static final int PORT = 9999;

    private final UserStore userStore = new UserStore();
    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    // Quản lý các phiên cuộc gọi đang hoạt động theo callId và theo username
    private final ConcurrentHashMap<Long, CallSession> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> userCallMap      = new ConcurrentHashMap<>();

    private static class CallSession {
        final long callId;
        final String caller;
        final String callee;
        final String callType;
        final String callerIp;
        final int callerUdpPort;
        volatile String state;

        CallSession(long callId, String caller, String callee, String callType, String callerIp, int callerUdpPort) {
            this.callId = callId;
            this.caller = caller;
            this.callee = callee;
            this.callType = callType;
            this.callerIp = callerIp;
            this.callerUdpPort = callerUdpPort;
            this.state = "RINGING";
        }
    }

    public static void main(String[] args) throws IOException {
        new ChatServer().start();
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("[Server] Đang lắng nghe trên cổng " + PORT);
        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(new ClientHandler(socket)).start();
        }
    }

    private void broadcast(String line, String excludeUser) {
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            if (!e.getKey().equals(excludeUser)) e.getValue().sendLine(line);
        }
    }

    private void sendTo(String username, String line) {
        ClientHandler h = clients.get(username);
        if (h != null) h.sendLine(line);
    }

    private void broadcastUserList() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            String dn = userStore.getDisplayName(e.getKey());
            sb.append(dn).append("|").append(e.getKey());
        }
        String packet = "USERS:" + sb;
        for (ClientHandler h : clients.values()) h.sendLine(packet);
    }

    private synchronized void releaseCallSession(long callId) {
        CallSession s = activeCalls.remove(callId);
        if (s != null) {
            userCallMap.remove(s.caller, callId);
            userCallMap.remove(s.callee, callId);
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter    out;
        private String username;
        private String displayName;

        ClientHandler(Socket socket) { this.socket = socket; }

        void sendLine(String line) { if (out != null) out.println(line); }

        @Override
        public void run() {
            try {
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                if (!authenticate()) { socket.close(); return; }

                String joinLine = in.readLine();
                if (joinLine == null || !joinLine.startsWith("JOIN:")) { socket.close(); return; }
                if (!joinLine.substring(5).trim().equals(username))    { socket.close(); return; }

                String base = username; int suffix = 2;
                while (clients.containsKey(username)) username = base + "_" + suffix++;

                clients.put(username, this);
                System.out.printf("[Server] %s (%s) kết nối từ %s%n",
                        displayName, username, socket.getInetAddress());

                broadcast("SERVER:" + displayName + " đã tham gia phòng chat.", username);
                broadcastUserList();

                String line;
                while ((line = in.readLine()) != null) handleLine(line);

            } catch (IOException ignored) {
            } finally {
                cleanup();
            }
        }

        private boolean authenticate() throws IOException {
            String line = in.readLine();
            if (line == null) return false;

            if (line.startsWith("REGISTER:")) {
                String[] parts = line.substring(9).split(":", 3);
                if (parts.length < 3) { sendLine("AUTH_FAIL:Dữ liệu đăng ký không hợp lệ."); return false; }

                String user = parts[0].trim();
                String pwd  = parts[1];
                String dn   = parts[2].trim();

                if (user.isEmpty()) { sendLine("AUTH_FAIL:Tên đăng nhập không được để trống."); return false; }
                if (dn.isEmpty())   { sendLine("AUTH_FAIL:Tên người dùng không được để trống."); return false; }
                if (pwd.length() < 6) { sendLine("AUTH_FAIL:Mật khẩu phải có ít nhất 6 ký tự."); return false; }
                if (!user.matches("[\\w\\-\\.]{2,30}")) {
                    sendLine("AUTH_FAIL:Tên đăng nhập chỉ gồm chữ, số, _ - . (2-30 ký tự).");
                    return false;
                }
                if (!userStore.register(user, pwd, dn)) {
                    sendLine("AUTH_FAIL:Tên đăng nhập đã tồn tại.");
                    return false;
                }
                username    = user;
                displayName = dn;
                sendLine("AUTH_OK:" + username + ":" + displayName);
                return true;

            } else if (line.startsWith("LOGIN:")) {
                String[] parts = line.substring(6).split(":", 2);
                if (parts.length < 2) { sendLine("AUTH_FAIL:Dữ liệu đăng nhập không hợp lệ."); return false; }

                String user = parts[0].trim();
                String pwd  = parts[1];

                if (!userStore.login(user, pwd)) {
                    sendLine("AUTH_FAIL:Sai tên đăng nhập hoặc mật khẩu.");
                    return false;
                }
                if (clients.containsKey(user)) {
                    sendLine("AUTH_FAIL:Tài khoản đang được sử dụng ở nơi khác.");
                    return false;
                }
                username    = user;
                displayName = userStore.getDisplayName(user);
                sendLine("AUTH_OK:" + username + ":" + displayName);
                return true;

            } else {
                sendLine("AUTH_FAIL:Vui lòng đăng nhập hoặc đăng ký.");
                return false;
            }
        }

        private void handleLine(String line) {
            if (line.startsWith("MSG:")) {
                String[] p = line.split(":", 3);
                if (p.length == 3) {
                    String text        = p[2];
                    String forwardLine = "MSG:" + username + ":" + displayName + ":" + text;
                    MessageStore.saveGroupMessage(username, displayName, text);
                    broadcast(forwardLine, username);
                }
            } else if (line.startsWith("PM:")) {
                String[] p = line.split(":", 4);
                if (p.length == 4) {
                    String toUser      = p[2];
                    String text        = p[3];
                    String forwardLine = "PM:" + username + ":" + displayName + ":" + text;
                    MessageStore.savePrivateMessage(username, displayName, toUser, text);
                    sendTo(toUser, forwardLine);
                }
            } else if (line.equals("GET_HISTORY")) {
                sendGroupHistory();
                sendLatestPrivatePreviews();
            } else if (line.startsWith("GET_PM_HISTORY:")) {
                String otherUser = line.substring(15).trim();
                sendPrivateHistory(otherUser);
            } else if (line.startsWith("FILE_META:") || line.startsWith("FILE_DATA:")) {
                routeFilePacket(line);
            } else if (line.startsWith("CALL_")) {
                handleCallSignaling(line);
            }
        }

        private void handleCallSignaling(String line) {
            if (line.startsWith("CALL_REQ:")) {
                String[] p = line.split(":", 6);
                if (p.length < 6) return;
                long callId;
                int callerUdpPort;
                try {
                    callId = Long.parseLong(p[1]);
                    callerUdpPort = Integer.parseInt(p[5]);
                } catch (NumberFormatException e) { return; }

                String targetUser = p[2].trim();
                String callType   = p[3].trim();
                String reportedIp = p[4].trim();
                String callerIp   = resolveClientLanIp(reportedIp);

                if (targetUser.equals(username)) {
                    sendLine("CALL_FAIL:" + callId + ":Không thể tự gọi cho chính mình.");
                    return;
                }

                ClientHandler targetHandler = clients.get(targetUser);
                if (targetHandler == null) {
                    sendLine("CALL_OFFLINE:" + callId + ":" + targetUser);
                    return;
                }

                synchronized (ChatServer.this) {
                    if (userCallMap.containsKey(username) || userCallMap.containsKey(targetUser)) {
                        sendLine("CALL_BUSY:" + callId + ":" + targetUser);
                        return;
                    }
                    CallSession session = new CallSession(callId, username, targetUser, callType, callerIp, callerUdpPort);
                    activeCalls.put(callId, session);
                    userCallMap.put(username, callId);
                    userCallMap.put(targetUser, callId);
                }

                targetHandler.sendLine("CALL_INCOMING:" + callId + ":" + username + ":" + displayName
                        + ":" + callType + ":" + callerIp + ":" + callerUdpPort);

            } else if (line.startsWith("CALL_ACCEPT:")) {
                String[] p = line.split(":", 4);
                if (p.length < 4) return;
                long callId;
                int calleeUdpPort;
                try {
                    callId = Long.parseLong(p[1]);
                    calleeUdpPort = Integer.parseInt(p[3]);
                } catch (NumberFormatException e) { return; }

                CallSession session = activeCalls.get(callId);
                if (session == null || !session.callee.equals(username)) return;

                String calleeIp = resolveClientLanIp(p[2].trim());
                session.state = "ACCEPTED";
                sendTo(session.caller, "CALL_ACCEPTED:" + callId + ":" + calleeIp + ":" + calleeUdpPort);

            } else if (line.startsWith("CALL_REJECT:")) {
                long callId = parseCallId(line.substring(12));
                CallSession session = activeCalls.get(callId);
                if (session != null && session.callee.equals(username)) {
                    releaseCallSession(callId);
                    sendTo(session.caller, "CALL_REJECTED:" + callId);
                }

            } else if (line.startsWith("CALL_CANCEL:")) {
                long callId = parseCallId(line.substring(12));
                CallSession session = activeCalls.get(callId);
                if (session != null && session.caller.equals(username)) {
                    releaseCallSession(callId);
                    sendTo(session.callee, "CALL_CANCELLED:" + callId);
                }

            } else if (line.startsWith("CALL_END:")) {
                long callId = parseCallId(line.substring(9));
                CallSession session = activeCalls.get(callId);
                if (session != null && (session.caller.equals(username) || session.callee.equals(username))) {
                    releaseCallSession(callId);
                    String other = session.caller.equals(username) ? session.callee : session.caller;
                    sendTo(other, "CALL_ENDED:" + callId);
                }
            }
        }

        private long parseCallId(String s) {
            try { return Long.parseLong(s.trim()); }
            catch (NumberFormatException e) { return -1L; }
        }

        private String resolveClientLanIp(String reportedIp) {
            String socketRemoteIp = socket.getInetAddress().getHostAddress();
            if (!"127.0.0.1".equals(socketRemoteIp) && !"0:0:0:0:0:0:0:1".equals(socketRemoteIp)) {
                return socketRemoteIp;
            }
            return reportedIp;
        }

        private void sendGroupHistory() {
            List<Message> history = MessageStore.loadGroupHistory();
            if (history.isEmpty()) return;
            sendLine("HISTORY_START:GROUP");
            for (Message msg : history) {
                sendLine("HISTORY_MSG:GROUP:" + msg.fromUser() + ":" + msg.fromDisplay()
                        + ":" + msg.sentAt() + ":" + msg.content());
            }
            sendLine("HISTORY_END:GROUP");
        }

        /**
         * Gửi thông tin xem trước tin nhắn gần nhất của từng cuộc trò chuyện riêng
         * để cột bên trái hiển thị đầy đủ ngay khi mở ứng dụng mà không cần truyền toàn bộ Base64.
         */
        private void sendLatestPrivatePreviews() {
            List<Message> latestList = MessageStore.loadLatestPrivateMessagesForUser(username);
            for (Message msg : latestList) {
                String otherUser = msg.fromUser().equals(username) ? msg.toUser() : msg.fromUser();
                if (otherUser == null || otherUser.isEmpty()) continue;

                String previewContent = msg.content();
                // Nếu là dữ liệu file nhị phân, chỉ gửi tên file để tạo nhãn xem trước, không gửi chuỗi Base64
                if (previewContent != null && previewContent.startsWith("[FILE_DATA]:")) {
                    String[] fp = previewContent.split(":", 3);
                    if (fp.length >= 2) {
                        previewContent = "[FILE_DATA]:" + fp[1];
                    }
                }
                sendLine("LAST_PM_PREVIEW:" + otherUser + ":" + msg.fromUser() + ":"
                        + msg.fromDisplay() + ":" + msg.sentAt() + ":" + previewContent);
            }
        }

        private void sendPrivateHistory(String otherUser) {
            List<Message> history = MessageStore.loadPrivateHistory(username, otherUser);
            if (history.isEmpty()) return;
            sendLine("HISTORY_START:" + otherUser);
            for (Message msg : history) {
                sendLine("HISTORY_MSG:" + otherUser + ":" + msg.fromUser() + ":" + msg.fromDisplay()
                        + ":" + msg.sentAt() + ":" + msg.content());
            }
            sendLine("HISTORY_END:" + otherUser);
        }

        private void routeFilePacket(String line) {
            String[] parts = line.split(":", 5);
            if (parts.length < 4) return;
            String to = parts[2];

            if (line.startsWith("FILE_DATA:") && parts.length == 5) {
                String fileName = parts[3];
                String b64Data  = parts[4];
                String dbContent = "[FILE_DATA]:" + fileName + ":" + b64Data;
                if ("ALL".equalsIgnoreCase(to)) {
                    MessageStore.saveGroupMessage(username, displayName, dbContent);
                } else {
                    MessageStore.savePrivateMessage(username, displayName, to, dbContent);
                }
            }

            if ("ALL".equalsIgnoreCase(to)) broadcast(line, username);
            else sendTo(to, line);
        }

        private void cleanup() {
            if (username != null) {
                Long activeCallId = userCallMap.get(username);
                if (activeCallId != null) {
                    CallSession s = activeCalls.get(activeCallId);
                    releaseCallSession(activeCallId);
                    if (s != null) {
                        String other = s.caller.equals(username) ? s.callee : s.caller;
                        sendTo(other, "CALL_ENDED:" + activeCallId);
                    }
                }

                clients.remove(username);
                broadcast("SERVER:" + displayName + " đã rời phòng chat.", null);
                broadcast("LEAVE:" + username, null);
                broadcastUserList();
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}