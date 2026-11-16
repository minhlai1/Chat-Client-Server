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

    /**
     * Trả về một Connection mới tới MySQL với cấu hình UTF-8 (utf8mb4) đầy đủ.
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /**
     * Tự động tạo bảng users và messages nếu chưa tồn tại, đồng thời đảm bảo
     * bảng hiện có hỗ trợ đầy đủ utf8mb4 cho Tiếng Việt có dấu và Emoji 4-byte.
     */
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

        String sqlAlterMessages = """
                ALTER TABLE messages
                CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
                MODIFY content LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                MODIFY from_display VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
                """;

        try (Connection conn = getConnection();
             Statement  stmt = conn.createStatement()) {
            stmt.execute("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci");
            stmt.execute(sqlUsers);
            stmt.execute(sqlMessages);
            try {
                stmt.execute(sqlAlterMessages);
            } catch (SQLException ignored) {}
            System.out.println("[DB] Schema khởi tạo thành công (utf8mb4).");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khởi tạo schema: " + e.getMessage());
        }
    }
}