package com.mycompany.bai4.model;

import com.mycompany.bai4.util.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MessageStore {

    private static final int HISTORY_LIMIT = 50;

    public record Message(
            long id,
            String type,
            String fromUser,
            String fromDisplay,
            String toUser,
            String content,
            String sentAt,
            String reactions
    ) {}

    public static long saveGroupMessage(String fromUser, String fromDisplay, String content) {
        String sql = "INSERT INTO messages (type, from_user, from_display, content) VALUES ('group', ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fromUser);
            ps.setString(2, fromDisplay);
            ps.setString(3, content);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi lưu tin nhắn nhóm: " + e.getMessage());
        }
        return -1L;
    }

    public static long savePrivateMessage(String fromUser, String fromDisplay, String toUser, String content) {
        String sql = "INSERT INTO messages (type, from_user, from_display, to_user, content) VALUES ('private', ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fromUser);
            ps.setString(2, fromDisplay);
            ps.setString(3, toUser);
            ps.setString(4, content);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi lưu tin nhắn riêng: " + e.getMessage());
        }
        return -1L;
    }

    public static Message findMessageById(long messageId) {
        String sql = "SELECT id, type, from_user, from_display, to_user, content, sent_at FROM messages WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Message(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getString("from_user"),
                            rs.getString("from_display"),
                            rs.getString("to_user"),
                            rs.getString("content"),
                            rs.getString("sent_at"),
                            ""
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi truy vấn messageId: " + e.getMessage());
        }
        return null;
    }

    /**
     * Thêm, cập nhật hoặc gỡ phản ứng của một người dùng trên tin nhắn.
     * Đảm bảo mỗi (message_id, username) chỉ có tối đa 1 phản ứng đang hoạt động.
     */
    public static synchronized String toggleReaction(long messageId, String username, String emoji) {
        if (messageId <= 0 || username == null || username.isBlank() || emoji == null || emoji.isBlank()) {
            return getReactionSummary(messageId);
        }
        String checkSql  = "SELECT emoji FROM message_reactions WHERE message_id = ? AND username = ?";
        String deleteSql = "DELETE FROM message_reactions WHERE message_id = ? AND username = ?";
        String updateSql = "UPDATE message_reactions SET emoji = ? WHERE message_id = ? AND username = ?";
        String insertSql = "INSERT INTO message_reactions (message_id, username, emoji) VALUES (?, ?, ?)";

        try (Connection conn = DatabaseConnection.getConnection()) {
            String existing = null;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, messageId);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existing = rs.getString("emoji");
                }
            }

            if (existing != null && existing.equals(emoji)) {
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setLong(1, messageId);
                    ps.setString(2, username);
                    ps.executeUpdate();
                }
            } else if (existing != null) {
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, emoji);
                    ps.setLong(2, messageId);
                    ps.setString(3, username);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setLong(1, messageId);
                    ps.setString(2, username);
                    ps.setString(3, emoji);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi cập nhật cảm xúc (msgId=" + messageId + ", user=" + username + "): " + e.getMessage());
        }
        return getReactionSummary(messageId);
    }

    /**
     * Trả về chuỗi tổng hợp Reaction:
     * emoji1=count1=userA|DisplayA~userB|DisplayB,emoji2=count2=userC|DisplayC
     */
    public static String getReactionSummary(long messageId) {
        if (messageId <= 0) return "";
        String sql = """
                SELECT r.emoji, r.username, COALESCE(u.display_name, r.username) AS display_name
                FROM message_reactions r
                LEFT JOIN users u ON r.username COLLATE utf8mb4_unicode_ci = u.username COLLATE utf8mb4_unicode_ci
                WHERE r.message_id = ?
                """;
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String em   = rs.getString("emoji");
                    String user = rs.getString("username");
                    String dn   = rs.getString("display_name");
                    if (em == null || em.isEmpty()) continue;
                    String safeDn = (dn == null ? user : dn).replaceAll("[,=~|:]", " ");
                    grouped.computeIfAbsent(em, k -> new ArrayList<>())
                            .add(user + "|" + safeDn);
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi đọc danh sách cảm xúc (msgId=" + messageId + "): " + e.getMessage());
        }

        if (grouped.isEmpty()) return "";

        var entries = new ArrayList<>(grouped.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        StringBuilder sb = new StringBuilder();
        for (var entry : entries) {
            if (sb.length() > 0) sb.append(",");
            List<String> users = entry.getValue();
            sb.append(entry.getKey())
                    .append("=")
                    .append(users.size())
                    .append("=")
                    .append(String.join("~", users));
        }
        return sb.toString();
    }

    public static List<Message> loadGroupHistory() {
        String sql = """
                SELECT id, type, from_user, from_display, to_user, content,
                       DATE_FORMAT(sent_at, '%H.%i %d/%m/%Y') AS sent_at
                FROM messages
                WHERE type = 'group'
                ORDER BY id DESC
                LIMIT ?
                """;
        return queryMessages(sql, HISTORY_LIMIT);
    }

    public static List<Message> loadPrivateHistory(String user1, String user2) {
        String sql = """
                SELECT id, type, from_user, from_display, to_user, content,
                       DATE_FORMAT(sent_at, '%H.%i %d/%m/%Y') AS sent_at
                FROM messages
                WHERE type = 'private'
                  AND ((from_user = ? AND to_user = ?)
                    OR (from_user = ? AND to_user = ?))
                ORDER BY id DESC
                LIMIT ?
                """;
        List<Message> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user1);
            ps.setString(2, user2);
            ps.setString(3, user2);
            ps.setString(4, user1);
            ps.setInt(5, HISTORY_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long msgId = rs.getLong("id");
                    result.add(new Message(
                            msgId,
                            rs.getString("type"),
                            rs.getString("from_user"),
                            rs.getString("from_display"),
                            rs.getString("to_user"),
                            rs.getString("content"),
                            rs.getString("sent_at"),
                            getReactionSummary(msgId)
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi tải lịch sử riêng: " + e.getMessage());
        }
        java.util.Collections.reverse(result);
        return result;
    }

    public static List<Message> loadLatestPrivateMessagesForUser(String username) {
        String sql = """
                SELECT m.id, m.type, m.from_user, m.from_display, m.to_user, m.content,
                       DATE_FORMAT(m.sent_at, '%H.%i %d/%m/%Y') AS sent_at
                FROM messages m
                INNER JOIN (
                    SELECT MAX(id) AS max_id
                    FROM messages
                    WHERE type = 'private' AND (from_user = ? OR to_user = ?)
                    GROUP BY CASE WHEN from_user = ? THEN to_user ELSE from_user END
                ) latest ON m.id = latest.max_id
                ORDER BY m.id ASC
                """;
        List<Message> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Message(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getString("from_user"),
                            rs.getString("from_display"),
                            rs.getString("to_user"),
                            rs.getString("content"),
                            rs.getString("sent_at"),
                            ""
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi tải tin nhắn gần nhất: " + e.getMessage());
        }
        return result;
    }

    private static List<Message> queryMessages(String sql, int limit) {
        List<Message> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long msgId = rs.getLong("id");
                    result.add(new Message(
                            msgId,
                            rs.getString("type"),
                            rs.getString("from_user"),
                            rs.getString("from_display"),
                            rs.getString("to_user"),
                            rs.getString("content"),
                            rs.getString("sent_at"),
                            getReactionSummary(msgId)
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi tải lịch sử: " + e.getMessage());
        }
        java.util.Collections.reverse(result);
        return result;
    }
}