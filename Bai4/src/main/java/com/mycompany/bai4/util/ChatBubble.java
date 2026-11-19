package com.mycompany.bai4.util;

import com.mycompany.bai4.ui.MessageReactionBar;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.function.BiConsumer;

public class ChatBubble extends JPanel {

    private static final int ARC = 12;
    private static final int MAX_WIDTH = 420;
    private final boolean isSelf;
    private final MessageReactionBar reactionBar;

    public ChatBubble(String text, boolean isSelf) {
        this(text, isSelf, "", -1L, "", null, "");
    }

    public ChatBubble(String text, boolean isSelf, String timeLabel) {
        this(text, isSelf, timeLabel, -1L, "", null, "");
    }

    public ChatBubble(String text,
                      boolean isSelf,
                      String timeLabel,
                      long messageId,
                      String selfUsername,
                      BiConsumer<Long, String> onReactionToggle,
                      String initialReactions) {
        this.isSelf = isSelf;
        setOpaque(false);
        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(1, 2, 4, 6));

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
        Color fgColor = isSelf ? ChatColor.BUBBLE_SELF_TEXT : ChatColor.BUBBLE_OTHER_TEXT;

        List<EmojiManager.TextSegment> segments = EmojiManager.tokenizeMessage(text);
        boolean jumboMode = EmojiManager.isJumboEmojiOnly(segments);
        int emojiPixelSize = jumboMode ? 34 : 20;

        JTextPane textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBorder(null);
        textPane.setMargin(new Insets(0, 0, 0, 0));
        textPane.setFont(msgFont);
        textPane.setForeground(fgColor);

        EmojiManager.populateTextPaneWithColoredEmojis(textPane, text, msgFont, fgColor, emojiPixelSize);

        Dimension naturalPref = textPane.getPreferredSize();
        if (naturalPref.width > MAX_WIDTH) {
            textPane.setSize(new Dimension(MAX_WIDTH, Short.MAX_VALUE));
            Dimension wrappedPref = textPane.getPreferredSize();
            textPane.setPreferredSize(new Dimension(MAX_WIDTH, wrappedPref.height));
        } else {
            textPane.setPreferredSize(new Dimension(Math.max(24, naturalPref.width + 2), naturalPref.height));
        }

        bubbleBody.add(textPane, BorderLayout.CENTER);

        if (timeLabel != null && !timeLabel.isEmpty()) {
            JLabel timeLbl = new JLabel(timeLabel);
            timeLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            timeLbl.setForeground(ChatColor.TEXT_SUB);
            bubbleBody.add(timeLbl, BorderLayout.SOUTH);
        }

        reactionBar = new MessageReactionBar(messageId, isSelf, selfUsername, onReactionToggle);
        JLabel triggerBtn = reactionBar.getTriggerButton();

        JLayeredPane layered = new JLayeredPane();
        Dimension bodySize = bubbleBody.getPreferredSize();
        int w = Math.max(bodySize.width, 74);
        int h = bodySize.height;

        bubbleBody.setBounds(0, 0, w, h);
        triggerBtn.setBounds(w - 26, h - 13, 24, 22);

        layered.setPreferredSize(new Dimension(w + 4, h + 10));
        layered.add(bubbleBody, JLayeredPane.DEFAULT_LAYER);
        layered.add(triggerBtn, JLayeredPane.PALETTE_LAYER);

        reactionBar.attachHoverTargets(this, layered, bubbleBody, textPane);
        if (initialReactions != null && !initialReactions.isEmpty()) {
            reactionBar.updateReactionsFromSummary(initialReactions);
        }

        JPanel bubbleAlignWrap = new JPanel(new FlowLayout(isSelf ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        bubbleAlignWrap.setOpaque(false);
        bubbleAlignWrap.add(layered);

        add(bubbleAlignWrap, BorderLayout.CENTER);
        add(reactionBar.getPillsWrapper(), BorderLayout.SOUTH);
    }

    public void setMessageId(long messageId) {
        reactionBar.setMessageId(messageId);
    }

    public long getMessageId() {
        return reactionBar.getMessageId();
    }

    public void updateReactions(String reactionSummary) {
        reactionBar.updateReactionsFromSummary(reactionSummary);
        revalidate();
        repaint();
    }

    public static Font pickEmojiCapableFont(int size) {
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    public static Font pickEmojiCapableFont(int style, int size) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    public static String formatPreviewContent(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) return "";

        if (rawContent.startsWith("[FILE_DATA]:")) {
            String[] fp = rawContent.split(":", 3);
            String fileName = (fp.length >= 2) ? fp[1].trim() : "";
            return formatFilePreviewLabel(fileName);
        }
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

    public static String truncateUnicodeSafe(String text, int maxCodePoints) {
        if (text == null || text.isEmpty() || maxCodePoints <= 0) return "";
        int totalCp = text.codePointCount(0, text.length());
        if (totalCp <= maxCodePoints) {
            return text;
        }
        int endCharIndex = text.offsetByCodePoints(0, maxCodePoints);
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