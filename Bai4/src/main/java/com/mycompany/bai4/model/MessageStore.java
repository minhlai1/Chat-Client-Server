package com.mycompany.bai4.model;

import com.mycompany.bai4.util.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Lưu tin nhắn nhóm vào DB và trả về messageId vừa tạo.
     */
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

    /**
     * Lưu tin nhắn riêng vào DB và trả về messageId vừa tạo.
     */
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

    public static synchronized String toggleReaction(long messageId, String username, String emoji) {
        String checkSql  = "SELECT emoji FROM message_reactions WHERE message_id = ? AND username = ?";
        String deleteSql = "DELETE FROM message_reactions WHERE message_id = ? AND username = ?";
        String upsertSql = """
                INSERT INTO message_reactions (message_id, username, emoji)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE emoji = VALUES(emoji)
                """;
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
            } else {
                try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                    ps.setLong(1, messageId);
                    ps.setString(2, username);
                    ps.setString(3, emoji);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi cập nhật cảm xúc: " + e.getMessage());
        }
        return getReactionSummary(messageId);
    }

    public static String getReactionSummary(long messageId) {
        String sql = "SELECT emoji, COUNT(*) AS cnt FROM message_reactions WHERE message_id = ? GROUP BY emoji ORDER BY cnt DESC";
        StringBuilder sb = new StringBuilder();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(rs.getString("emoji")).append("=").append(rs.getInt("cnt"));
                }
            }
        } catch (SQLException ignored) {
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
                            ""
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageStore] Lỗi tải lịch sử riêng: " + e.getMessage());
        }
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * Lấy tin nhắn gần nhất của từng cuộc trò chuyện cá nhân liên quan đến username
     * để hiển thị chính xác phần xem trước (Last Message Preview) ngay khi mở ứng dụng.
     */
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
                            ""
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