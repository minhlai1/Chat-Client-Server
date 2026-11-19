package com.mycompany.bai4.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConnection {

    private static final String URL  = "jdbc:mysql://localhost:3306/chatapp"
            + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
            + "&useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci";
    private static final String USER = "root";
    private static final String PASS = "12345";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    public static void initSchema() {
        String sqlUsers = """
                CREATE TABLE IF NOT EXISTS users (
                    username      VARCHAR(50)  NOT NULL PRIMARY KEY,
                    password_hash VARCHAR(64)  NOT NULL,
                    display_name  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;

        String sqlMessages = """
                CREATE TABLE IF NOT EXISTS messages (
                    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    type         ENUM('group','private') NOT NULL DEFAULT 'group',
                    from_user    VARCHAR(50)  NOT NULL,
                    from_display VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                    to_user      VARCHAR(50)  NULL,
                    content      LONGTEXT     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                    sent_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;

        String sqlReactions = """
                CREATE TABLE IF NOT EXISTS message_reactions (
                    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    message_id BIGINT      NOT NULL,
                    username   VARCHAR(50) NOT NULL,
                    emoji      VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uq_msg_user (message_id, username),
                    INDEX idx_reaction_msg (message_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;

        String[] safeMigrations = {
                "ALTER TABLE users CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "ALTER TABLE messages CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "ALTER TABLE messages MODIFY content LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL",
                "ALTER TABLE messages MODIFY from_display VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL",
                "ALTER TABLE message_reactions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "ALTER TABLE message_reactions MODIFY emoji VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL",
                "ALTER TABLE message_reactions ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP",
                "ALTER TABLE message_reactions ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP",
                "ALTER TABLE message_reactions ADD UNIQUE KEY uq_msg_user (message_id, username)",
                "ALTER TABLE message_reactions ADD INDEX idx_reaction_msg (message_id)"
        };

        try (Connection conn = getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci");
            stmt.execute(sqlUsers);
            stmt.execute(sqlMessages);
            stmt.execute(sqlReactions);

            for (String alterSql : safeMigrations) {
                try {
                    stmt.execute(alterSql);
                } catch (SQLException ignored) {
                    // Cột hoặc index đã tồn tại từ trước -> bỏ qua an toàn
                }
            }
            System.out.println("[DB] Schema khởi tạo thành công (utf8mb4 + message_reactions).");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khởi tạo schema: " + e.getMessage());
        }
    }
}