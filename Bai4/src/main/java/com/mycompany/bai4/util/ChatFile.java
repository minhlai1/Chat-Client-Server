package com.mycompany.bai4.util;

import com.mycompany.bai4.ui.MessageReactionBar;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;

public class ChatFile extends JPanel {

    private static final int ARC = 12;
    private static final int MAX_IMG_WIDTH  = 380;
    private static final int MAX_IMG_HEIGHT = 300;
    private final MessageReactionBar reactionBar;

    public ChatFile(String name, long size, File file, boolean isSelf) {
        this(name, size, file, isSelf, java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), -1L, "", null, "");
    }

    public ChatFile(String name, long size, File file, boolean isSelf, String timeLabel) {
        this(name, size, file, isSelf, timeLabel, -1L, "", null, "");
    }

    public ChatFile(String name,
                    long size,
                    File file,
                    boolean isSelf,
                    String timeLabel,
                    long messageId,
                    String selfUsername,
                    BiConsumer<Long, String> onReactionToggle,
                    String initialReactions) {
        setOpaque(false);
        setLayout(new BorderLayout(0, 0));

        this.reactionBar = new MessageReactionBar(messageId, isSelf, selfUsername, onReactionToggle);

        if (isImageFile(name) && file != null && file.exists()) {
            buildImagePreview(name, file, isSelf, timeLabel);
        } else {
            buildStandardFileCard(name, size, file, isSelf, timeLabel);
        }

        if (initialReactions != null && !initialReactions.isEmpty()) {
            reactionBar.updateReactionsFromSummary(initialReactions);
        }
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

    public static boolean isImageFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    // ── Chế độ 1: Hiển thị Hình ảnh trực tiếp kèm Message Reactions ───────────
    private void buildImagePreview(String name, File file, boolean isSelf, String timeLabel) {
        setBorder(new EmptyBorder(2, 2, 4, 6));
        try {
            BufferedImage orig = ImageIO.read(file);
            if (orig == null) {
                buildStandardFileCard(name, file.length(), file, isSelf, timeLabel);
                return;
            }

            int ow = orig.getWidth();
            int oh = orig.getHeight();
            double scale = Math.min(1.0, Math.min((double) MAX_IMG_WIDTH / ow, (double) MAX_IMG_HEIGHT / oh));
            int dw = Math.max(120, (int) Math.round(ow * scale));
            int dh = Math.max(90,  (int) Math.round(oh * scale));

            Image scaledImg = orig.getScaledInstance(dw, dh, Image.SCALE_SMOOTH);

            JPanel imgCanvas = new JPanel(null) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Shape clip = new RoundRectangle2D.Double(0, 0, dw, dh, 12, 12);
                    g2.setClip(clip);
                    g2.drawImage(scaledImg, 0, 0, dw, dh, this);

                    g2.setClip(null);
                    g2.setColor(new Color(0, 0, 0, 28));
                    g2.drawRoundRect(0, 0, dw - 1, dh - 1, 12, 12);

                    g2.setColor(new Color(0, 0, 0, 130));
                    g2.fillRoundRect(8, 8, 28, 18, 6, 6);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 10));
                    g2.drawString("HD", 14, 21);
                    g2.dispose();
                }
            };
            imgCanvas.setOpaque(false);
            imgCanvas.setPreferredSize(new Dimension(dw, dh));
            imgCanvas.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            imgCanvas.setToolTipText("Nhấn để xem ảnh kích thước lớn (" + name + ")");
            imgCanvas.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openImageViewer(name, file, orig);
                }
            });

            JLabel triggerBtn = reactionBar.getTriggerButton();

            JLayeredPane layered = new JLayeredPane();
            imgCanvas.setBounds(0, 0, dw, dh);
            triggerBtn.setBounds(dw - 28, dh - 13, 24, 22);
            layered.setPreferredSize(new Dimension(dw + 4, dh + 10));
            layered.add(imgCanvas, JLayeredPane.DEFAULT_LAYER);
            layered.add(triggerBtn, JLayeredPane.PALETTE_LAYER);

            reactionBar.attachHoverTargets(this, layered, imgCanvas);

            add(layered, BorderLayout.CENTER);

            JPanel bottomStack = new JPanel();
            bottomStack.setOpaque(false);
            bottomStack.setLayout(new BoxLayout(bottomStack, BoxLayout.Y_AXIS));
            bottomStack.add(reactionBar.getPillsWrapper());

            if (timeLabel != null && !timeLabel.isEmpty()) {
                JPanel timeRow = new JPanel(new FlowLayout(isSelf ? FlowLayout.RIGHT : FlowLayout.LEFT, 2, 2));
                timeRow.setOpaque(false);
                JLabel timePill = new JLabel(timeLabel, SwingConstants.CENTER) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(0x9AA2AC));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                        g2.dispose();
                        super.paintComponent(g);
                    }
                };
                timePill.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                timePill.setForeground(Color.WHITE);
                timePill.setBorder(new EmptyBorder(1, 8, 1, 8));
                timeRow.add(timePill);
                bottomStack.add(timeRow);
            }
            add(bottomStack, BorderLayout.SOUTH);

        } catch (IOException ex) {
            buildStandardFileCard(name, file.length(), file, isSelf, timeLabel);
        }
    }

    public static void openImageViewer(String title, File file, BufferedImage image) {
        JDialog viewer = new JDialog((Frame) null, "Xem ảnh - " + title, true);
        viewer.setSize(900, 680);
        viewer.setLocationRelativeTo(null);
        viewer.setLayout(new BorderLayout());
        viewer.getContentPane().setBackground(new Color(0x18191A));

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(new Color(0x242526));
        topBar.setBorder(new EmptyBorder(10, 16, 10, 16));

        JLabel titleLbl = new JLabel(title + " (" + image.getWidth() + "x" + image.getHeight() + " px)");
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 14));
        titleLbl.setForeground(Color.WHITE);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnGroup.setOpaque(false);

        JButton openFolderBtn = new JButton("Mở thư mục");
        openFolderBtn.setFocusPainted(false);
        openFolderBtn.addActionListener(e -> {
            try { Desktop.getDesktop().open(file.getParentFile()); }
            catch (IOException ignored) {}
        });

        JButton closeBtn = new JButton("Đóng (ESC)");
        closeBtn.setFocusPainted(false);
        closeBtn.addActionListener(e -> viewer.dispose());

        btnGroup.add(openFolderBtn);
        btnGroup.add(closeBtn);

        topBar.add(titleLbl, BorderLayout.WEST);
        topBar.add(btnGroup, BorderLayout.EAST);

        JPanel canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int pw = getWidth() - 40;
                int ph = getHeight() - 40;
                double scale = Math.min(1.0, Math.min((double) pw / image.getWidth(), (double) ph / image.getHeight()));
                int w = (int) (image.getWidth() * scale);
                int h = (int) (image.getHeight() * scale);
                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;
                g2.drawImage(image, x, y, w, h, this);
                g2.dispose();
            }
        };
        canvas.setBackground(new Color(0x18191A));

        viewer.getRootPane().registerKeyboardAction(
                e -> viewer.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        viewer.add(topBar, BorderLayout.NORTH);
        viewer.add(canvas, BorderLayout.CENTER);
        viewer.setVisible(true);
    }

    // ── Chế độ 2: Thẻ File đính kèm (DOCX, PDF, ZIP, XLSX...) ─────────────────
    private void buildStandardFileCard(String name, long size, File file, boolean isSelf, String timeLabel) {
        setBorder(new EmptyBorder(2, 2, 4, 6));

        JPanel cardBody = new JPanel(new BorderLayout(12, 6)) {
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
        cardBody.setOpaque(false);
        cardBody.setBorder(new EmptyBorder(12, 14, 10, 14));
        cardBody.setPreferredSize(new Dimension(345, 84));

        JComponent fileBadge = buildFileTypeBadge(name);

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);

        JLabel nameLbl = new JLabel(name);
        nameLbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
        nameLbl.setForeground(ChatColor.TEXT_MAIN);

        JPanel subRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        subRow.setOpaque(false);

        JLabel sizeLbl = new JLabel(formatSize(size) + "   ");
        sizeLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sizeLbl.setForeground(ChatColor.TEXT_SUB);

        boolean existsOnDisk = file != null && file.exists();
        JLabel statusLbl = new JLabel(existsOnDisk ? "Đã có trên máy" : "Đang tải...");
        statusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLbl.setForeground(existsOnDisk ? new Color(0x2EA043) : ChatColor.TEXT_SUB);

        subRow.add(sizeLbl);
        subRow.add(statusLbl);

        infoPanel.add(nameLbl);
        infoPanel.add(Box.createVerticalStrut(4));
        infoPanel.add(subRow);

        if (timeLabel != null && !timeLabel.isEmpty()) {
            infoPanel.add(Box.createVerticalStrut(4));
            JLabel timeLbl = new JLabel(timeLabel);
            timeLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            timeLbl.setForeground(ChatColor.TEXT_SUB);
            infoPanel.add(timeLbl);
        }

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        actionBtns.setOpaque(false);

        JButton folderBtn = createVectorActionButton(true, "Mở thư mục chứa tệp");
        JButton openBtn   = createVectorActionButton(false, "Mở tệp");

        folderBtn.setEnabled(existsOnDisk);
        openBtn.setEnabled(existsOnDisk);

        if (existsOnDisk) {
            folderBtn.addActionListener(e -> {
                try { Desktop.getDesktop().open(file.getParentFile()); }
                catch (IOException ex) { JOptionPane.showMessageDialog(null, "Không mở được thư mục:\n" + ex.getMessage()); }
            });
            openBtn.addActionListener(e -> {
                try { Desktop.getDesktop().open(file); }
                catch (IOException ex) { JOptionPane.showMessageDialog(null, "Không mở được tệp:\n" + ex.getMessage()); }
            });
        }

        actionBtns.add(folderBtn);
        actionBtns.add(openBtn);

        cardBody.add(fileBadge,  BorderLayout.WEST);
        cardBody.add(infoPanel,  BorderLayout.CENTER);
        cardBody.add(actionBtns, BorderLayout.EAST);

        JLabel triggerBtn = reactionBar.getTriggerButton();

        JLayeredPane layered = new JLayeredPane();
        int w = 345, h = 84;
        cardBody.setBounds(0, 0, w, h);
        triggerBtn.setBounds(w - 28, h - 12, 24, 22);

        layered.setPreferredSize(new Dimension(w + 4, h + 10));
        layered.add(cardBody, JLayeredPane.DEFAULT_LAYER);
        layered.add(triggerBtn, JLayeredPane.PALETTE_LAYER);

        reactionBar.attachHoverTargets(this, layered, cardBody, infoPanel);

        add(layered, BorderLayout.CENTER);
        add(reactionBar.getPillsWrapper(), BorderLayout.SOUTH);
    }

    private JButton createVectorActionButton(boolean isFolder, String tooltip) {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x4A5568));
                g2.setStroke(new BasicStroke(1.4f));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                if (isFolder) {
                    g2.drawRoundRect(cx - 7, cy - 4, 14, 10, 2, 2);
                    g2.drawLine(cx - 7, cy - 6, cx - 2, cy - 6);
                } else {
                    g2.drawLine(cx, cy - 6, cx, cy + 3);
                    g2.drawLine(cx - 4, cy - 1, cx, cy + 3);
                    g2.drawLine(cx + 4, cy - 1, cx, cy + 3);
                    g2.drawLine(cx - 6, cy + 6, cx + 6, cy + 6);
                }
                g2.dispose();
            }
        };
        btn.setToolTipText(tooltip);
        btn.setBackground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(new LineBorder(new Color(0xD0D7DE), 1, true));
        btn.setPreferredSize(new Dimension(28, 26));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static JComponent buildFileTypeBadge(String filename) {
        String n = filename.toLowerCase();
        Color badgeColor;
        String label;

        if (n.endsWith(".doc") || n.endsWith(".docx")) {
            badgeColor = new Color(0x2B7CD3); label = "W";
        } else if (n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv")) {
            badgeColor = new Color(0x217346); label = "X";
        } else if (n.endsWith(".ppt") || n.endsWith(".pptx")) {
            badgeColor = new Color(0xD24726); label = "P";
        } else if (n.endsWith(".pdf")) {
            badgeColor = new Color(0xE53935); label = "PDF";
        } else if (n.matches(".*\\.(zip|rar|7z|tar|gz)")) {
            badgeColor = new Color(0xF59E0B); label = "ZIP";
        } else if (isImageFile(n)) {
            badgeColor = new Color(0x00897B); label = "IMG";
        } else {
            badgeColor = new Color(0x546E7A); label = "FILE";
        }

        JPanel badge = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(badgeColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, label.length() > 1 ? 11 : 16));
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(label)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(label, x, y);
                g2.dispose();
            }
        };
        badge.setOpaque(false);
        badge.setPreferredSize(new Dimension(40, 44));
        badge.setMinimumSize(new Dimension(40, 44));
        return badge;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024)               return bytes + " B";
        if (bytes < 1024 * 1024)        return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String getFileIcon(String name) {
        return "[Tệp]";
    }
}