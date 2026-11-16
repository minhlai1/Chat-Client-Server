package com.mycompany.bai4.util;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ChatBubble extends JPanel {

    private static final int ARC = 12;
    private static final int MAX_WIDTH = 420;
    private final boolean isSelf;
    private int likeCount = 0;
    private final JLabel reactionBtn;

    public ChatBubble(String text, boolean isSelf) {
        this(text, isSelf, "");
    }

    public ChatBubble(String text, boolean isSelf, String timeLabel) {
        this.isSelf = isSelf;
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(1, 2, 12, 6));

        JPanel bubbleBody = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelf ? ChatColor.BUBBLE_SELF : ChatColor.BUBBLE_OTHER);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
                g2.setColor(isSelf ? new Color(0xB8DCFF) : ChatColor.BUBBLE_OTHER_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        bubbleBody.setOpaque(false);
        bubbleBody.setBorder(new EmptyBorder(10, 14, 8, 14));

        Font msgFont = pickEmojiCapableFont(14);
        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setOpaque(false);
        textArea.setFont(msgFont);
        textArea.setForeground(isSelf ? ChatColor.BUBBLE_SELF_TEXT : ChatColor.BUBBLE_OTHER_TEXT);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        FontMetrics fm = textArea.getFontMetrics(msgFont);
        int maxLineW = 0;
        for (String line : text.split("\n")) {
            maxLineW = Math.max(maxLineW, fm.stringWidth(line));
        }
        if (maxLineW > MAX_WIDTH) {
            textArea.setSize(new Dimension(MAX_WIDTH, Short.MAX_VALUE));
            Dimension pref = textArea.getPreferredSize();
            textArea.setPreferredSize(new Dimension(MAX_WIDTH, pref.height));
        }

        bubbleBody.add(textArea, BorderLayout.CENTER);

        if (timeLabel != null && !timeLabel.isEmpty()) {
            JLabel timeLbl = new JLabel(timeLabel);
            timeLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            timeLbl.setForeground(ChatColor.TEXT_SUB);
            bubbleBody.add(timeLbl, BorderLayout.SOUTH);
        }

        reactionBtn = createReactionBadge();
        reactionBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                likeCount++;
                reactionBtn.setText("♥ " + likeCount);
                reactionBtn.setForeground(new Color(0xE53935));
                revalidate();
                repaint();
            }
        });

        JLayeredPane layered = new JLayeredPane();
        Dimension bodySize = bubbleBody.getPreferredSize();
        int w = Math.max(bodySize.width, 72);
        int h = bodySize.height;

        bubbleBody.setBounds(0, 0, w, h);
        Dimension reactSize = reactionBtn.getPreferredSize();
        int rw = Math.max(24, reactSize.width + 8);
        reactionBtn.setBounds(w - rw - 4, h - 10, rw, 20);

        layered.setPreferredSize(new Dimension(w + 4, h + 12));
        layered.add(bubbleBody, JLayeredPane.DEFAULT_LAYER);
        layered.add(reactionBtn, JLayeredPane.PALETTE_LAYER);

        add(layered, BorderLayout.CENTER);
    }

    /**
     * Trả về Logical Composite Font (Font.SANS_SERIF) của Java:
     * Trên Windows, Font.SANS_SERIF tự động kết hợp Segoe UI (hiển thị đầy đủ 100%
     * tiếng Việt có dấu như 'ạ', 'ệ', 'ự', 'ể'...) và Segoe UI Emoji (hiển thị đầy đủ
     * Emoji Unicode như 😂, ❤️, 👍) trên cả JLabel lẫn JTextArea mà không bao giờ bị lỗi ô vuông.
     */
    public static Font pickEmojiCapableFont(int size) {
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    public static Font pickEmojiCapableFont(int style, int size) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    /**
     * Chuyển đổi nội dung tin nhắn thô hoặc tên file thành nhãn xem trước chuẩn Zalo:
     * - Hình ảnh -> [Hình ảnh]
     * - Video -> [Video]
     * - Âm thanh / Thoại -> [Tin nhắn thoại]
     * - Tệp đính kèm khác -> [Tệp] <tên_tệp>
     * - Văn bản / Emoji -> Giữ nguyên 100% tiếng Việt và Emoji, loại bỏ xuống dòng thừa.
     */
    public static String formatPreviewContent(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) return "";

        if (rawContent.startsWith("[FILE_DATA]:")) {
            String[] fp = rawContent.split(":", 3);
            String fileName = (fp.length >= 2) ? fp[1].trim() : "";
            return formatFilePreviewLabel(fileName);
        }
        // Làm phẳng các ký tự xuống dòng trong tin nhắn nhiều dòng để hiển thị gọn trên 1 dòng preview
        return rawContent.replaceAll("[\\r\\n]+", " ").trim();
    }

    public static String formatFilePreviewLabel(String fileName) {
        if (fileName == null || fileName.isBlank()) return "[Tệp đính kèm]";
        String lower = fileName.toLowerCase();
        if (ChatFile.isImageFile(lower)) {
            return "[Hình ảnh]";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".mkv") || lower.endsWith(".webm")) {
            return "[Video]";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".m4a")) {
            return "[Tin nhắn thoại]";
        }
        return "[Tệp] " + fileName;
    }

    /**
     * Cắt chuỗi an toàn theo số lượng Unicode Code Point (không cắt theo byte hay chỉ số char UTF-16),
     * đảm bảo không bao giờ cắt đôi cặp Surrogate của Emoji (😂, ❤️, 👍) hoặc làm hỏng dấu tiếng Việt.
     */
    public static String truncateUnicodeSafe(String text, int maxCodePoints) {
        if (text == null || text.isEmpty() || maxCodePoints <= 0) return "";
        int totalCp = text.codePointCount(0, text.length());
        if (totalCp <= maxCodePoints) {
            return text;
        }
        int endCharIndex = text.offsetByCodePoints(0, maxCodePoints);
        // Nếu ngay sau vị trí cắt là Variation Selector (U+FE0F) hoặc dấu tổ hợp (Combining Mark),
        // mở rộng thêm để không làm gãy cụm ký tự hiển thị
        while (endCharIndex < text.length()) {
            int nextCp = text.codePointAt(endCharIndex);
            int type = Character.getType(nextCp);
            if (nextCp == 0xFE0F || nextCp == 0xFE0E || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                endCharIndex += Character.charCount(nextCp);
            } else {
                break;
            }
        }
        return text.substring(0, endCharIndex) + "...";
    }

    public static JLabel createReactionBadge() {
        JLabel badge = new JLabel("♡", SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(new Color(0xD0D7DE));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        badge.setForeground(new Color(0x7589A3));
        badge.setBorder(new EmptyBorder(2, 6, 2, 6));
        badge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return badge;
    }
}