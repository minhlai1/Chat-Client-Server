package com.mycompany.bai4.ui;

import com.mycompany.bai4.util.ChatColor;
import com.mycompany.bai4.util.ChatFile;
import com.mycompany.bai4.util.ChatBubble;
import com.mycompany.bai4.client.ChatClient;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatUI extends JFrame {

    public static final String GROUP_ID     = "GROUP";
    private static final String GROUP_LABEL = "Nhóm chat";
    private static final int PREVIEW_MAX_CODEPOINTS = 28;

    private final ChatClient client;
    private final String selfUsername;
    private final String selfDisplayName;
    private final String serverHost;

    private final Map<String, MessageFeedPanel> conversationFeeds   = new LinkedHashMap<>();
    private final Map<String, String>           lastSenderPerConv   = new HashMap<>();
    private final Map<String, Integer>          unreadCounts        = new HashMap<>();
    private final Map<String, String>           lastDatePerConv     = new HashMap<>();
    private final Map<String, String>           lastMsgPreview      = new HashMap<>();
    private final Map<String, String>           lastMsgTime         = new HashMap<>();
    private final Map<String, List<FileEntry>>  sharedFilesMap      = new HashMap<>();

    // Quản lý tham chiếu Message ID để đồng bộ Reaction theo thời gian thực
    private final Map<Long, JComponent>         messageComponentMap = new HashMap<>();
    private final Map<Long, JPanel>             messageRowMap       = new HashMap<>();
    private final Map<String, Deque<JComponent>> pendingSelfMessages = new HashMap<>();

    private String activeConversation = GROUP_ID;
    private String activeLabel        = GROUP_LABEL;
    private boolean filterUnreadOnly  = false;
    private String searchQuery        = "";
    private List<String[]> cachedUsers = new ArrayList<>();

    // UI refs
    private JScrollPane      chatScroll;
    private JTextField       inputField;
    private JButton          sendOrLikeBtn;
    private JLabel           chatTitleLbl;
    private JLabel           chatStatusLbl;
    private AvatarLabel      headerAvatar;
    private JPanel           rightInfoPanel;
    private AvatarLabel      infoAvatar;
    private JLabel           infoNameLbl;
    private JPanel           infoFileListPanel;
    private JPanel           infoMediaGridPanel;
    private DefaultListModel<ConvItem> convListModel;
    private JList<ConvItem>            convList;
    private EmojiPickerPopup           emojiPickerPopup;

    private record FileEntry(String name, long size, File file, String time) {}

    public ChatUI(ChatClient client, String selfUsername, String selfDisplayName, String serverHost) {
        super("Zalo - " + selfDisplayName);
        this.client          = client;
        this.selfUsername    = selfUsername;
        this.selfDisplayName = selfDisplayName;
        this.serverHost      = serverHost;
        buildUI();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Inner classes
    // ══════════════════════════════════════════════════════════════════════════

    private static class MessageFeedPanel extends JPanel implements Scrollable {
        MessageFeedPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(ChatColor.BG_PAGE);
            setBorder(new EmptyBorder(14, 16, 14, 16));
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 120; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static class ConvItem {
        final String id, label;
        final boolean group;
        ConvItem(String id, String label, boolean group) {
            this.id = id; this.label = label; this.group = group;
        }
        @Override public boolean equals(Object o) {
            return o instanceof ConvItem c && c.id.equals(id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static class AvatarLabel extends JLabel {
        private Color bg;
        private String initials;
        private final int size;

        private static final Color[] PALETTE = {
                new Color(0x0068FF), new Color(0x06C755), new Color(0xFF6B35),
                new Color(0xA855F7), new Color(0xEC4899), new Color(0x14B8A6)
        };

        AvatarLabel(String name, boolean isGroup, int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
            setOpaque(false);
            updateIdentity(name, isGroup);
        }

        void updateIdentity(String name, boolean isGroup) {
            if (isGroup) {
                initials = "G";
                bg = new Color(0x0068FF);
            } else {
                String clean = (name == null || name.isBlank()) ? "?" : name.trim();
                String[] parts = clean.split("\\s+");
                if (parts.length >= 2) {
                    initials = ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
                } else {
                    initials = clean.substring(0, Math.min(2, clean.length())).toUpperCase();
                }
                int idx = Math.abs(clean.hashCode()) % PALETTE.length;
                bg = PALETTE[idx];
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillOval(0, 0, size - 1, size - 1);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, size / 3)));
            FontMetrics fm = g2.getFontMetrics();
            int x = (size - fm.stringWidth(initials)) / 2;
            int y = (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initials, x, y);
            g2.dispose();
        }
    }

    private static class UnreadBadge extends JLabel {
        UnreadBadge(int count) {
            super(count > 99 ? "5+" : String.valueOf(count), SwingConstants.CENTER);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            setForeground(Color.WHITE);
            setOpaque(false);
            int w = count > 9 ? 22 : 18;
            setPreferredSize(new Dimension(w, 18));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ChatColor.BADGE_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Hệ thống Vector Icon chuẩn Fluent UI / Tabler Duotone (vẽ 100% bằng Graphics2D,
     * sắc nét trên mọi độ phân giải, có lớp màu nền duotone + nét chính sắc sảo).
     */
    private static class ZaloVectorIcon implements Icon {
        enum Type {
            // Left NavBar
            NAV_MESSAGE, NAV_CONTACTS, NAV_TASKS, NAV_CLOUD, NAV_SETTINGS,
            // Header & Input Toolbar
            SMILE, IMAGE, ATTACH, CARD, CUT, FORMAT, MORE,
            CALL, VIDEO, SEARCH, PANEL_SPLIT, SEND_LIKE,
            // Right Info Panel
            BELL, PIN, GROUP_ADD, REMINDER, MEDIA_GALLERY, FILE_FOLDER
        }

        private final Type type;
        private final int size;
        private final Color color;
        private final boolean hovered;

        ZaloVectorIcon(Type type, int size, Color color) {
            this(type, size, color, false);
        }

        ZaloVectorIcon(Type type, int size, Color color, boolean hovered) {
            this.type = type;
            this.size = size;
            this.color = color;
            this.hovered = hovered;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        private Color withAlpha(Color c, int alpha) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
        }

        private Color darken(Color c, float factor) {
            int r = Math.max(0, (int) (c.getRed() * (1f - factor)));
            int g = Math.max(0, (int) (c.getGreen() * (1f - factor)));
            int b = Math.max(0, (int) (c.getBlue() * (1f - factor)));
            return new Color(r, g, b, c.getAlpha());
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);

            Color strokeCol = hovered ? darken(color, 0.12f) : color;
            Color softFill  = withAlpha(strokeCol, hovered ? 58 : 34);
            g2.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (type) {
                // ── 1. Thanh điều hướng bên trái (NavBar Icons) ──
                case NAV_MESSAGE -> {
                    g2.setColor(withAlpha(strokeCol, hovered ? 75 : 42));
                    g2.fillRoundRect(2, 3, size - 4, size - 7, 8, 8);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 3, size - 4, size - 7, 8, 8);
                    int[] px = {6, 10, 6};
                    int[] py = {size - 4, size - 4, size - 1};
                    g2.fillPolygon(px, py, 3);
                    g2.drawLine(6, 8, size - 6, 8);
                    g2.drawLine(6, 12, size - 9, 12);
                }
                case NAV_CONTACTS -> {
                    g2.setColor(withAlpha(strokeCol, hovered ? 75 : 42));
                    g2.fillRoundRect(4, 2, size - 7, size - 4, 5, 5);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(4, 2, size - 7, size - 4, 5, 5);
                    g2.drawLine(2, 6, 4, 6);
                    g2.drawLine(2, 11, 4, 11);
                    g2.drawLine(2, 16, 4, 16);
                    g2.fillOval(9, 6, 4, 4);
                    g2.drawArc(7, 11, 8, 6, 0, 180);
                }
                case NAV_TASKS -> {
                    g2.setColor(withAlpha(strokeCol, hovered ? 75 : 42));
                    g2.fillRoundRect(3, 3, size - 6, size - 6, 6, 6);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(3, 3, size - 6, size - 6, 6, 6);
                    g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(7, size / 2 + 1, 10, size / 2 + 4);
                    g2.drawLine(10, size / 2 + 4, size - 6, 8);
                }
                case NAV_CLOUD -> {
                    GeneralPath cloud = new GeneralPath();
                    cloud.moveTo(6, size - 5);
                    cloud.lineTo(size - 5, size - 5);
                    cloud.curveTo(size - 2, size - 5, size - 2, size - 11, size - 5, size - 11);
                    cloud.curveTo(size - 5, 5, 9, 4, 8, 9);
                    cloud.curveTo(3, 9, 3, size - 5, 6, size - 5);
                    cloud.closePath();
                    g2.setColor(withAlpha(strokeCol, hovered ? 75 : 42));
                    g2.fill(cloud);
                    g2.setColor(strokeCol);
                    g2.draw(cloud);
                }
                case NAV_SETTINGS -> {
                    int cx = size / 2;
                    int cy = size / 2;
                    g2.setColor(withAlpha(strokeCol, hovered ? 75 : 42));
                    g2.fillOval(cx - 6, cy - 6, 12, 12);
                    g2.setColor(strokeCol);
                    g2.drawOval(cx - 6, cy - 6, 12, 12);
                    g2.fillOval(cx - 2, cy - 2, 4, 4);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        int x1 = cx + (int) Math.round(Math.cos(a) * 6);
                        int y1 = cy + (int) Math.round(Math.sin(a) * 6);
                        int x2 = cx + (int) Math.round(Math.cos(a) * 8.5);
                        int y2 = cy + (int) Math.round(Math.sin(a) * 8.5);
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }

                // ── 2. Thanh công cụ nhập tin nhắn (Input Toolbar Icons) ──
                case SMILE -> {
                    g2.setColor(withAlpha(ChatColor.ICON_EMOJI, hovered ? 75 : 45));
                    g2.fillOval(2, 2, size - 4, size - 4);
                    g2.setColor(strokeCol);
                    g2.drawOval(2, 2, size - 4, size - 4);
                    g2.fillOval(6, 7, 2, 2);
                    g2.fillOval(size - 8, 7, 2, 2);
                    g2.drawArc(6, 7, size - 12, size - 11, 200, 140);
                }
                case IMAGE -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 3, size - 4, size - 6, 5, 5);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 3, size - 4, size - 6, 5, 5);
                    g2.setColor(ChatColor.ICON_EMOJI);
                    g2.fillOval(5, 6, 3, 3);
                    g2.setColor(strokeCol);
                    int[] px = {3, 8, 12, size - 3};
                    int[] py = {size - 4, 10, 13, 8};
                    g2.drawPolyline(px, py, 4);
                }
                case ATTACH -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(3, 2, size - 6, size - 4, 6, 6);
                    g2.setColor(strokeCol);
                    g2.drawArc(6, 3, 7, 7, 0, 180);
                    g2.drawLine(6, 6, 6, size - 6);
                    g2.drawArc(6, size - 9, 5, 5, 180, 180);
                    g2.drawLine(11, size - 6, 11, 7);
                }
                case CARD -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 4, size - 4, size - 8, 5, 5);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 4, size - 4, size - 8, 5, 5);
                    g2.fillOval(5, 7, 4, 4);
                    g2.drawLine(11, 8, size - 5, 8);
                    g2.drawLine(11, 12, size - 6, 12);
                }
                case CUT -> {
                    g2.setColor(softFill);
                    g2.fillOval(2, size - 9, 6, 6);
                    g2.fillOval(size - 8, size - 9, 6, 6);
                    g2.setColor(strokeCol);
                    g2.drawOval(2, size - 9, 6, 6);
                    g2.drawOval(size - 8, size - 9, 6, 6);
                    g2.drawLine(7, size - 5, size - 4, 3);
                    g2.drawLine(size - 7, size - 5, 4, 3);
                }
                case FORMAT -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 3, size - 4, size - 6, 5, 5);
                    g2.setColor(strokeCol);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    g2.drawString("A", 3, size - 5);
                    g2.setColor(ChatColor.ICON_CUT);
                    g2.drawLine(12, size - 5, 16, 6);
                }
                case MORE -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 4, size - 4, size - 8, 6, 6);
                    g2.setColor(strokeCol);
                    g2.fillOval(4, size / 2 - 1, 3, 3);
                    g2.fillOval(size / 2 - 1, size / 2 - 1, 3, 3);
                    g2.fillOval(size - 7, size / 2 - 1, 3, 3);
                }

                // ── 3. Thanh công cụ phía trên khung chat (Header Icons) ──
                case CALL -> {
                    GeneralPath phone = new GeneralPath();
                    phone.moveTo(5, 4);
                    phone.quadTo(3, 4, 3, 7);
                    phone.quadTo(3, 15, 11, 15);
                    phone.quadTo(14, 15, 14, 13);
                    phone.lineTo(12, 11);
                    phone.lineTo(10, 12);
                    phone.quadTo(6, 11, 6, 8);
                    phone.lineTo(7, 6);
                    phone.closePath();
                    g2.setColor(softFill);
                    g2.fill(phone);
                    g2.setColor(strokeCol);
                    g2.draw(phone);
                    g2.drawArc(9, 3, 6, 6, 0, 90);
                }
                case VIDEO -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 5, size - 8, size - 10, 4, 4);
                    int[] vx = {size - 6, size - 2, size - 2, size - 6};
                    int[] vy = {8, 5, size - 5, size - 8};
                    g2.fillPolygon(vx, vy, 4);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 5, size - 8, size - 10, 4, 4);
                    g2.drawPolygon(vx, vy, 4);
                }
                case SEARCH -> {
                    g2.setColor(softFill);
                    g2.fillOval(3, 3, size - 8, size - 8);
                    g2.setColor(strokeCol);
                    g2.drawOval(3, 3, size - 8, size - 8);
                    g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(size - 6, size - 6, size - 2, size - 2);
                }
                case PANEL_SPLIT -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 3, size - 4, size - 6, 4, 4);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 3, size - 4, size - 6, 4, 4);
                    g2.setColor(withAlpha(strokeCol, 190));
                    g2.fillRoundRect(size - 8, 4, 5, size - 8, 2, 2);
                }
                case SEND_LIKE -> {
                    Color gold = hovered ? darken(ChatColor.ICON_EMOJI, 0.1f) : ChatColor.ICON_EMOJI;
                    g2.setColor(withAlpha(gold, 65));
                    int[] tx = {7, 10, 12, 12, 17, 16, 7};
                    int[] ty = {8, 2, 2, 7, 7, 16, 16};
                    g2.fillPolygon(tx, ty, 7);
                    g2.setColor(gold);
                    g2.fillRoundRect(2, 8, 4, 8, 2, 2);
                    g2.drawPolygon(tx, ty, 7);
                }

                // ── 4. Bảng thông tin hội thoại bên phải (Right Info Panel Icons) ──
                case BELL -> {
                    g2.setColor(softFill);
                    g2.fillArc(4, 3, size - 8, size - 6, 0, 180);
                    g2.fillRect(4, size / 2, size - 8, 4);
                    g2.setColor(strokeCol);
                    g2.drawArc(4, 3, size - 8, size - 6, 0, 180);
                    g2.drawLine(4, size / 2, 4, size - 5);
                    g2.drawLine(size - 4, size / 2, size - 4, size - 5);
                    g2.drawLine(2, size - 5, size - 2, size - 5);
                    g2.fillArc(size / 2 - 2, size - 5, 4, 4, 180, 180);
                }
                case PIN -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(5, 3, size - 10, 7, 3, 3);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(5, 3, size - 10, 7, 3, 3);
                    g2.drawLine(4, 10, size - 4, 10);
                    g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(size / 2, 10, size / 2, size - 2);
                }
                case GROUP_ADD -> {
                    g2.setColor(softFill);
                    g2.fillOval(4, 3, 6, 6);
                    g2.fillArc(2, 10, 10, 7, 0, 180);
                    g2.setColor(strokeCol);
                    g2.drawOval(4, 3, 6, 6);
                    g2.drawArc(2, 10, 10, 7, 0, 180);
                    g2.setColor(ChatColor.ICON_CALL);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(size - 6, 6, size - 2, 6);
                    g2.drawLine(size - 4, 4, size - 4, 8);
                }
                case REMINDER -> {
                    g2.setColor(softFill);
                    g2.fillOval(2, 2, size - 4, size - 4);
                    g2.setColor(strokeCol);
                    g2.drawOval(2, 2, size - 4, size - 4);
                    g2.drawLine(size / 2, 5, size / 2, size / 2);
                    g2.drawLine(size / 2, size / 2, size - 5, size / 2 + 2);
                }
                case MEDIA_GALLERY -> {
                    g2.setColor(softFill);
                    g2.fillRoundRect(2, 3, size - 4, size - 6, 4, 4);
                    g2.setColor(strokeCol);
                    g2.drawRoundRect(2, 3, size - 4, size - 6, 4, 4);
                    g2.setColor(ChatColor.ICON_EMOJI);
                    g2.fillOval(5, 6, 3, 3);
                    g2.setColor(strokeCol);
                    int[] px = {3, 7, 11, size - 3};
                    int[] py = {size - 4, 9, 12, 7};
                    g2.drawPolyline(px, py, 4);
                }
                case FILE_FOLDER -> {
                    GeneralPath folder = new GeneralPath();
                    folder.moveTo(2, 5);
                    folder.lineTo(7, 5);
                    folder.lineTo(9, 7);
                    folder.lineTo(size - 2, 7);
                    folder.lineTo(size - 2, size - 3);
                    folder.lineTo(2, size - 3);
                    folder.closePath();
                    g2.setColor(softFill);
                    g2.fill(folder);
                    g2.setColor(strokeCol);
                    g2.draw(folder);
                }
            }
            g2.dispose();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Build UI (4 cột chuẩn Zalo PC)
    // ══════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1260, 740);
        setMinimumSize(new Dimension(920, 560));
        setLocationRelativeTo(null);
        getContentPane().setBackground(ChatColor.BG_PAGE);
        setLayout(new BorderLayout(0, 0));

        conversationFeeds.put(GROUP_ID, new MessageFeedPanel());
        lastMsgPreview.put(GROUP_ID, "Nhóm chat chung");
        lastMsgTime.put(GROUP_ID, "Vừa xong");

        convListModel = new DefaultListModel<>();
        convListModel.addElement(new ConvItem(GROUP_ID, GROUP_LABEL, true));

        JPanel leftCol = new JPanel(new BorderLayout());
        leftCol.setPreferredSize(new Dimension(350, 0));
        leftCol.add(buildNavBar(),  BorderLayout.WEST);
        leftCol.add(buildSidebar(), BorderLayout.CENTER);

        JPanel centerAndRight = new JPanel(new BorderLayout());
        centerAndRight.add(buildChatPanel(), BorderLayout.CENTER);
        rightInfoPanel = buildRightInfoPanel();
        centerAndRight.add(rightInfoPanel,   BorderLayout.EAST);

        add(leftCol,        BorderLayout.WEST);
        add(centerAndRight, BorderLayout.CENTER);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { client.closeConnection(); }
        });

        convList.setSelectedIndex(0);
    }

    private JPanel buildNavBar() {
        JPanel nav = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(ChatColor.NAV_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setPreferredSize(new Dimension(64, 0));
        nav.setBorder(new EmptyBorder(14, 0, 14, 0));

        JPanel avatarWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        avatarWrap.setOpaque(false);
        avatarWrap.setMaximumSize(new Dimension(64, 60));
        AvatarLabel selfAvatar = new AvatarLabel(selfDisplayName, false, 44);
        avatarWrap.add(selfAvatar);

        nav.add(avatarWrap);
        nav.add(Box.createVerticalStrut(10));
        nav.add(navIcon(ZaloVectorIcon.Type.NAV_MESSAGE,  new Color(0xFFFFFF), "Tin nhắn", true));
        nav.add(navIcon(ZaloVectorIcon.Type.NAV_CONTACTS, new Color(0x7CE7DF), "Danh bạ", false));
        nav.add(navIcon(ZaloVectorIcon.Type.NAV_TASKS,    new Color(0xA7F3D0), "Công việc", false));
        nav.add(Box.createVerticalGlue());
        nav.add(navIcon(ZaloVectorIcon.Type.NAV_CLOUD,    new Color(0xBAE6FD), "Cloud của tôi", false));
        nav.add(navIcon(ZaloVectorIcon.Type.NAV_SETTINGS, new Color(0xFDE68A), "Cài đặt (" + serverHost + ")", false));

        return nav;
    }

    private JPanel navIcon(ZaloVectorIcon.Type iconType, Color tintColor, String tooltip, boolean active) {
        final boolean[] hovered = {false};
        JLabel lbl = new JLabel(new ZaloVectorIcon(iconType, 22, active ? Color.WHITE : tintColor, false), SwingConstants.CENTER);

        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                if (active || hovered[0]) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(active ? new Color(0x0052CC) : new Color(255, 255, 255, 38));
                    g2.fillRoundRect(10, 4, getWidth() - 20, getHeight() - 8, 12, 12);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        wrap.setOpaque(false);
        wrap.setToolTipText(tooltip);
        wrap.setMaximumSize(new Dimension(64, 52));
        wrap.setPreferredSize(new Dimension(64, 52));
        wrap.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        wrap.add(lbl, BorderLayout.CENTER);

        wrap.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                lbl.setIcon(new ZaloVectorIcon(iconType, 22, Color.WHITE, true));
                wrap.repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                lbl.setIcon(new ZaloVectorIcon(iconType, 22, active ? Color.WHITE : tintColor, false));
                wrap.repaint();
            }
        });
        return wrap;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(ChatColor.BG_SIDEBAR);
        sidebar.setBorder(new MatteBorder(0, 0, 0, 1, ChatColor.DIVIDER));

        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setBackground(ChatColor.BG_SIDEBAR);
        topContainer.setBorder(new MatteBorder(0, 0, 1, 0, ChatColor.DIVIDER));

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setBackground(ChatColor.BG_SIDEBAR);
        searchRow.setBorder(new EmptyBorder(12, 12, 8, 12));

        JPanel searchWrap = new JPanel(new BorderLayout(6, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xEAEDF0));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
            }
        };
        searchWrap.setOpaque(false);
        searchWrap.setBorder(new EmptyBorder(6, 10, 6, 10));

        JLabel searchIcon = new JLabel(new ZaloVectorIcon(ZaloVectorIcon.Type.SEARCH, 16, ChatColor.ICON_MSG));

        JTextField searchField = new JTextField() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ChatColor.TEXT_SUB);
                    g2.setFont(getFont());
                    g2.drawString("Tìm kiếm", 2, getHeight() - 4);
                    g2.dispose();
                }
            }
        };
        searchField.setBorder(null);
        searchField.setOpaque(false);
        searchField.setFont(ChatBubble.pickEmojiCapableFont(12));
        searchField.setForeground(ChatColor.TEXT_MAIN);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { updateFilter(); }
            public void removeUpdate(DocumentEvent e)  { updateFilter(); }
            public void changedUpdate(DocumentEvent e) { updateFilter(); }
            private void updateFilter() {
                searchQuery = searchField.getText().trim().toLowerCase();
                refreshConversationListModel();
            }
        });

        searchWrap.add(searchIcon,  BorderLayout.WEST);
        searchWrap.add(searchField, BorderLayout.CENTER);
        searchRow.add(searchWrap,   BorderLayout.CENTER);

        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.setBackground(ChatColor.BG_SIDEBAR);
        filterRow.setBorder(new EmptyBorder(2, 12, 0, 12));

        JPanel leftTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        leftTabs.setOpaque(false);

        JLabel tabAll    = new JLabel("Tất cả");
        JLabel tabUnread = new JLabel("Chưa đọc");
        styleFilterTab(tabAll, true);
        styleFilterTab(tabUnread, false);

        tabAll.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                filterUnreadOnly = false;
                styleFilterTab(tabAll, true);
                styleFilterTab(tabUnread, false);
                refreshConversationListModel();
            }
        });
        tabUnread.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                filterUnreadOnly = true;
                styleFilterTab(tabAll, false);
                styleFilterTab(tabUnread, true);
                refreshConversationListModel();
            }
        });

        leftTabs.add(tabAll);
        leftTabs.add(tabUnread);

        JLabel categoryLbl = new JLabel("Phân loại v   ...");
        categoryLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        categoryLbl.setForeground(ChatColor.TEXT_SUB);
        categoryLbl.setBorder(new EmptyBorder(0, 0, 6, 0));

        filterRow.add(leftTabs,    BorderLayout.WEST);
        filterRow.add(categoryLbl, BorderLayout.EAST);

        topContainer.add(searchRow);
        topContainer.add(filterRow);

        convList = new JList<>(convListModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int idx = locationToIndex(event.getPoint());
                if (idx >= 0) {
                    ConvItem item = getModel().getElementAt(idx);
                    String fullPreview = lastMsgPreview.get(item.id);
                    if (fullPreview != null && !fullPreview.isEmpty()) {
                        return fullPreview;
                    }
                }
                return null;
            }
        };
        ToolTipManager.sharedInstance().registerComponent(convList);
        convList.setBackground(ChatColor.BG_SIDEBAR);
        convList.setFixedCellHeight(68);
        convList.setBorder(null);
        convList.setCellRenderer(new ConvCellRenderer());
        convList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            ConvItem sel = convList.getSelectedValue();
            if (sel != null) {
                unreadCounts.remove(sel.id);
                switchConversation(sel.id, sel.label);
                convList.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(convList);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        sidebar.add(topContainer, BorderLayout.NORTH);
        sidebar.add(scroll,       BorderLayout.CENTER);
        return sidebar;
    }

    private void styleFilterTab(JLabel lbl, boolean selected) {
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        lbl.setForeground(selected ? ChatColor.ACCENT : ChatColor.TEXT_SUB);
        lbl.setBorder(selected
                ? new CompoundBorder(new MatteBorder(0, 0, 2, 0, ChatColor.ACCENT), new EmptyBorder(4, 0, 6, 0))
                : new EmptyBorder(4, 0, 8, 0));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private class ConvCellRenderer implements ListCellRenderer<ConvItem> {
        @Override
        public Component getListCellRendererComponent(JList<? extends ConvItem> list, ConvItem value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBorder(new EmptyBorder(10, 12, 10, 12));
            row.setBackground(isSelected ? ChatColor.SIDEBAR_SEL : ChatColor.BG_SIDEBAR);

            AvatarLabel avatar = new AvatarLabel(value.label, value.group, 46);
            JPanel avatarWrap = new JPanel(new BorderLayout());
            avatarWrap.setOpaque(false);
            avatarWrap.setPreferredSize(new Dimension(48, 48));

            if (!value.group) {
                JPanel dotLayer = new JPanel(null) {
                    @Override protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(ChatColor.ACCENT2);
                        g2.fillOval(34, 34, 11, 11);
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(2));
                        g2.drawOval(34, 34, 11, 11);
                        g2.dispose();
                    }
                };
                dotLayer.setOpaque(false);
                dotLayer.setPreferredSize(new Dimension(48, 48));
                dotLayer.setLayout(new BorderLayout());
                dotLayer.add(avatar, BorderLayout.CENTER);
                avatarWrap.add(dotLayer, BorderLayout.CENTER);
            } else {
                avatarWrap.add(avatar, BorderLayout.CENTER);
            }

            JPanel centerCol = new JPanel(new BorderLayout(0, 4));
            centerCol.setOpaque(false);

            JPanel topRow = new JPanel(new BorderLayout(4, 0));
            topRow.setOpaque(false);

            int unread = unreadCounts.getOrDefault(value.id, 0);
            JLabel nameLbl = new JLabel(value.label);
            nameLbl.setFont(ChatBubble.pickEmojiCapableFont(unread > 0 ? Font.BOLD : Font.PLAIN, 14));
            nameLbl.setForeground(ChatColor.TEXT_MAIN);

            String timeStr = lastMsgTime.getOrDefault(value.id, "");
            JLabel timeLbl = new JLabel(timeStr);
            timeLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            timeLbl.setForeground(ChatColor.TEXT_SUB);

            topRow.add(nameLbl, BorderLayout.WEST);
            topRow.add(timeLbl, BorderLayout.EAST);

            JPanel botRow = new JPanel(new BorderLayout(4, 0));
            botRow.setOpaque(false);

            String rawPreview = lastMsgPreview.getOrDefault(value.id,
                    value.group ? "Nhóm chat chung" : "Bắt đầu cuộc trò chuyện");
            String safePreview = ChatBubble.truncateUnicodeSafe(rawPreview, PREVIEW_MAX_CODEPOINTS);

            JLabel previewLbl = new JLabel(safePreview);
            previewLbl.setFont(ChatBubble.pickEmojiCapableFont(unread > 0 ? Font.BOLD : Font.PLAIN, 12));
            previewLbl.setForeground(unread > 0 ? ChatColor.TEXT_MAIN : ChatColor.TEXT_SUB);

            botRow.add(previewLbl, BorderLayout.CENTER);
            if (unread > 0) {
                botRow.add(new UnreadBadge(unread), BorderLayout.EAST);
            }

            centerCol.add(topRow, BorderLayout.NORTH);
            centerCol.add(botRow, BorderLayout.SOUTH);

            row.add(avatarWrap, BorderLayout.WEST);
            row.add(centerCol,  BorderLayout.CENTER);
            return row;
        }
    }

    private JPanel buildChatPanel() {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBackground(ChatColor.BG_PAGE);

        JPanel titleBar = new JPanel(new BorderLayout(12, 0));
        titleBar.setBackground(ChatColor.BG_PANEL);
        titleBar.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, ChatColor.DIVIDER),
                new EmptyBorder(10, 16, 10, 16)));

        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftHeader.setOpaque(false);

        headerAvatar = new AvatarLabel(GROUP_LABEL, true, 42);

        JPanel nameCol = new JPanel();
        nameCol.setOpaque(false);
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));

        chatTitleLbl = new JLabel(GROUP_LABEL);
        chatTitleLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        chatTitleLbl.setForeground(ChatColor.TEXT_MAIN);

        chatStatusLbl = new JLabel("Vừa truy cập");
        chatStatusLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        chatStatusLbl.setForeground(ChatColor.TEXT_SUB);

        nameCol.add(chatTitleLbl);
        nameCol.add(Box.createVerticalStrut(2));
        nameCol.add(chatStatusLbl);

        leftHeader.add(headerAvatar);
        leftHeader.add(nameCol);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        actions.setOpaque(false);

        JButton callBtn       = createHeaderVectorButton(ZaloVectorIcon.Type.CALL,        ChatColor.ICON_CALL,    "Cuộc gọi thoại", false);
        JButton videoBtn      = createHeaderVectorButton(ZaloVectorIcon.Type.VIDEO,       ChatColor.ICON_VIDEO,   "Cuộc gọi video", false);
        JButton searchBtn     = createHeaderVectorButton(ZaloVectorIcon.Type.SEARCH,      ChatColor.ICON_DEFAULT, "Tìm kiếm tin nhắn", false);
        JButton toggleInfoBtn = createHeaderVectorButton(ZaloVectorIcon.Type.PANEL_SPLIT, ChatColor.ACCENT,       "Thông tin hội thoại", true);

        callBtn.addActionListener(e -> initiateCallFromHeader(false));
        videoBtn.addActionListener(e -> initiateCallFromHeader(true));

        toggleInfoBtn.addActionListener(e -> {
            boolean vis = !rightInfoPanel.isVisible();
            rightInfoPanel.setVisible(vis);
            toggleInfoBtn.putClientProperty("activeState", vis);
            toggleInfoBtn.repaint();
        });

        actions.add(callBtn);
        actions.add(videoBtn);
        actions.add(searchBtn);
        actions.add(toggleInfoBtn);

        titleBar.add(leftHeader, BorderLayout.WEST);
        titleBar.add(actions,    BorderLayout.EAST);
        chatPanel.add(titleBar,   BorderLayout.NORTH);

        chatScroll = new JScrollPane(conversationFeeds.get(GROUP_ID));
        chatScroll.setBorder(null);
        chatScroll.getViewport().setBackground(ChatColor.BG_PAGE);
        chatScroll.getVerticalScrollBar().setUnitIncrement(20);
        chatScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatPanel.add(chatScroll, BorderLayout.CENTER);

        JPanel inputBar = new JPanel(new BorderLayout(0, 0));
        inputBar.setBackground(ChatColor.BG_PANEL);
        inputBar.setBorder(new MatteBorder(1, 0, 0, 0, ChatColor.DIVIDER));

        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        toolRow.setBackground(ChatColor.BG_PANEL);
        toolRow.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, ChatColor.DIVIDER),
                new EmptyBorder(0, 8, 0, 8)));

        JLabel stickerTool = createToolbarVectorIcon(ZaloVectorIcon.Type.SMILE,  ChatColor.ICON_EMOJI,   "Biểu tượng cảm xúc");
        JLabel imageTool   = createToolbarVectorIcon(ZaloVectorIcon.Type.IMAGE,  ChatColor.ICON_IMAGE,   "Gửi hình ảnh (PNG, JPG, GIF...)");
        JLabel attachTool  = createToolbarVectorIcon(ZaloVectorIcon.Type.ATTACH, ChatColor.ICON_ATTACH,  "Đính kèm Tệp");
        JLabel cardTool    = createToolbarVectorIcon(ZaloVectorIcon.Type.CARD,   ChatColor.ICON_CARD,    "Gửi danh thiếp");
        JLabel captureTool = createToolbarVectorIcon(ZaloVectorIcon.Type.CUT,    ChatColor.ICON_CUT,     "Chụp màn hình");
        JLabel formatTool  = createToolbarVectorIcon(ZaloVectorIcon.Type.FORMAT, ChatColor.ICON_MSG,     "Định dạng tin nhắn");
        JLabel moreTool    = createToolbarVectorIcon(ZaloVectorIcon.Type.MORE,   ChatColor.ICON_DEFAULT, "Tùy chọn khác");

        stickerTool.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showEmojiPopup(stickerTool); }
        });
        imageTool.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { sendImageOnly(); }
        });
        attachTool.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { sendFile(); }
        });

        toolRow.add(stickerTool);
        toolRow.add(imageTool);
        toolRow.add(attachTool);
        toolRow.add(cardTool);
        toolRow.add(captureTool);
        toolRow.add(formatTool);
        toolRow.add(moreTool);

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBackground(Color.WHITE);
        inputRow.setBorder(new EmptyBorder(10, 16, 12, 16));

        inputField = new JTextField() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ChatColor.TEXT_DIM);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                    g2.drawString("Nhập @, tin nhắn tới " + activeLabel, 2, getHeight() - 8);
                    g2.dispose();
                }
            }
        };
        inputField.setOpaque(false);
        inputField.setBorder(null);
        inputField.setFont(ChatBubble.pickEmojiCapableFont(14));
        inputField.setForeground(ChatColor.TEXT_MAIN);
        inputField.setCaretColor(ChatColor.ACCENT);
        inputField.setPreferredSize(new Dimension(0, 32));

        JPanel rightInputActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightInputActions.setOpaque(false);

        JLabel inlineEmojiBtn = createToolbarVectorIcon(ZaloVectorIcon.Type.SMILE, ChatColor.ICON_EMOJI, "Chọn Emoji");
        inlineEmojiBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showEmojiPopup(inlineEmojiBtn); }
        });

        Icon thumbIcon      = new ZaloVectorIcon(ZaloVectorIcon.Type.SEND_LIKE, 20, ChatColor.ICON_EMOJI, false);
        Icon thumbHoverIcon = new ZaloVectorIcon(ZaloVectorIcon.Type.SEND_LIKE, 20, ChatColor.ICON_EMOJI, true);
        sendOrLikeBtn = new JButton(thumbIcon);
        sendOrLikeBtn.setRolloverIcon(thumbHoverIcon);
        sendOrLikeBtn.setContentAreaFilled(false);
        sendOrLikeBtn.setBorderPainted(false);
        sendOrLikeBtn.setFocusPainted(false);
        sendOrLikeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        inputField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { toggleBtn(); }
            public void removeUpdate(DocumentEvent e)  { toggleBtn(); }
            public void changedUpdate(DocumentEvent e) { toggleBtn(); }
            private void toggleBtn() {
                boolean hasText = !inputField.getText().trim().isEmpty();
                if (hasText) {
                    sendOrLikeBtn.setIcon(null);
                    sendOrLikeBtn.setRolloverIcon(null);
                    sendOrLikeBtn.setText("GỬI");
                    sendOrLikeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                    sendOrLikeBtn.setForeground(ChatColor.ACCENT);
                } else {
                    sendOrLikeBtn.setText("");
                    sendOrLikeBtn.setIcon(thumbIcon);
                    sendOrLikeBtn.setRolloverIcon(thumbHoverIcon);
                }
            }
        });

        sendOrLikeBtn.addActionListener(e -> {
            if (inputField.getText().trim().isEmpty()) {
                inputField.setText("👍");
            }
            sendMessage();
        });
        inputField.addActionListener(e -> sendMessage());

        rightInputActions.add(inlineEmojiBtn);
        rightInputActions.add(sendOrLikeBtn);

        inputRow.add(inputField,        BorderLayout.CENTER);
        inputRow.add(rightInputActions, BorderLayout.EAST);

        inputBar.add(toolRow,  BorderLayout.NORTH);
        inputBar.add(inputRow, BorderLayout.CENTER);
        chatPanel.add(inputBar, BorderLayout.SOUTH);

        return chatPanel;
    }

    private void initiateCallFromHeader(boolean isVideo) {
        if (GROUP_ID.equals(activeConversation)) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng chọn một tài khoản cụ thể ở danh sách bên trái để thực hiện cuộc gọi 1-1.",
                    "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        client.startCall(activeConversation, activeLabel, isVideo);
    }

    private JButton createHeaderVectorButton(ZaloVectorIcon.Type type, Color iconColor, String tooltip, boolean initialActive) {
        Icon normalIcon = new ZaloVectorIcon(type, 18, iconColor, false);
        Icon hoverIcon  = new ZaloVectorIcon(type, 18, iconColor, true);

        JButton btn = new JButton(normalIcon) {
            @Override protected void paintComponent(Graphics g) {
                Boolean actProp = (Boolean) getClientProperty("activeState");
                boolean isAct = (actProp != null) ? actProp : false;
                boolean isHov = getModel().isRollover();
                if (isAct || isHov) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (isAct) {
                        g2.setColor(ChatColor.SIDEBAR_SEL);
                    } else {
                        g2.setColor(new Color(iconColor.getRed(), iconColor.getGreen(), iconColor.getBlue(), 28));
                    }
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        btn.putClientProperty("activeState", initialActive);
        btn.setRolloverIcon(hoverIcon);
        btn.setToolTipText(tooltip);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setPreferredSize(new Dimension(34, 32));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JLabel createToolbarVectorIcon(ZaloVectorIcon.Type type, Color color, String tooltip) {
        final boolean[] hovered = {false};
        Icon normalIcon = new ZaloVectorIcon(type, 20, color, false);
        Icon hoverIcon  = new ZaloVectorIcon(type, 20, color, true);

        JLabel lbl = new JLabel(normalIcon, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                if (hovered[0]) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 26));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        lbl.setPreferredSize(new Dimension(32, 32));
        lbl.setToolTipText(tooltip);
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                lbl.setIcon(hoverIcon);
                lbl.repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                lbl.setIcon(normalIcon);
                lbl.repaint();
            }
        });
        return lbl;
    }

    private void showEmojiPopup(Component invoker) {
        if (emojiPickerPopup == null) {
            emojiPickerPopup = new EmojiPickerPopup(selfUsername, this::insertEmojiAtCaret);
        }
        Dimension pref = emojiPickerPopup.getPreferredSize();
        int x = 0;
        if (invoker.isShowing()) {
            int screenX = invoker.getLocationOnScreen().x;
            int screenW = Toolkit.getDefaultToolkit().getScreenSize().width;
            if (screenX + pref.width > screenW - 20) {
                x = invoker.getWidth() - pref.width;
            }
        }
        int y = -pref.height - 6;
        emojiPickerPopup.show(invoker, x, y);
    }

    /**
     * Chèn chuỗi Unicode Emoji vào đúng vị trí con trỏ (caret) hiện tại trong ô nhập tin nhắn,
     * giữ nguyên nội dung văn bản đã nhập và cho phép chọn nhiều Emoji liên tiếp.
     */
    private void insertEmojiAtCaret(String emoji) {
        if (emoji == null || emoji.isEmpty() || inputField == null) return;
        try {
            int start = inputField.getSelectionStart();
            int end   = inputField.getSelectionEnd();
            String current = inputField.getText();
            int safeStart = Math.max(0, Math.min(start, current.length()));
            int safeEnd   = Math.max(safeStart, Math.min(end, current.length()));

            String updated = current.substring(0, safeStart) + emoji + current.substring(safeEnd);
            inputField.setText(updated);
            int newCaretPos = safeStart + emoji.length();
            inputField.setCaretPosition(newCaretPos);
        } catch (Exception ex) {
            inputField.setText(inputField.getText() + emoji);
        }
    }

    private JPanel buildRightInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setPreferredSize(new Dimension(300, 0));
        panel.setBorder(new MatteBorder(0, 1, 0, 0, ChatColor.DIVIDER));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setPreferredSize(new Dimension(300, 63));
        header.setBorder(new MatteBorder(0, 0, 1, 0, ChatColor.DIVIDER));
        JLabel title = new JLabel("Thông tin hội thoại", SwingConstants.CENTER);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        title.setForeground(ChatColor.TEXT_MAIN);
        header.add(title, BorderLayout.CENTER);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Color.WHITE);

        JPanel profileSec = new JPanel();
        profileSec.setLayout(new BoxLayout(profileSec, BoxLayout.Y_AXIS));
        profileSec.setBackground(Color.WHITE);
        profileSec.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 6, 0, ChatColor.BG_PAGE),
                new EmptyBorder(20, 16, 16, 16)));

        infoAvatar = new AvatarLabel(GROUP_LABEL, true, 60);
        infoAvatar.setAlignmentX(Component.CENTER_ALIGNMENT);

        infoNameLbl = new JLabel(GROUP_LABEL);
        infoNameLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        infoNameLbl.setForeground(ChatColor.TEXT_MAIN);
        infoNameLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel quickActions = new JPanel(new GridLayout(1, 3, 8, 0));
        quickActions.setOpaque(false);
        quickActions.setMaximumSize(new Dimension(260, 68));
        quickActions.add(buildCircleActionItem(ZaloVectorIcon.Type.BELL,      ChatColor.ICON_EMOJI, "<html><center>Tắt thông<br>báo</center></html>"));
        quickActions.add(buildCircleActionItem(ZaloVectorIcon.Type.PIN,       ChatColor.ICON_CUT,   "<html><center>Ghim hội<br>thoại</center></html>"));
        quickActions.add(buildCircleActionItem(ZaloVectorIcon.Type.GROUP_ADD, ChatColor.ICON_GROUP, "<html><center>Tạo nhóm<br>trò chuyện</center></html>"));

        profileSec.add(infoAvatar);
        profileSec.add(Box.createVerticalStrut(10));
        profileSec.add(infoNameLbl);
        profileSec.add(Box.createVerticalStrut(14));
        profileSec.add(quickActions);

        JPanel metaSec = new JPanel();
        metaSec.setLayout(new BoxLayout(metaSec, BoxLayout.Y_AXIS));
        metaSec.setBackground(Color.WHITE);
        metaSec.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 6, 0, ChatColor.BG_PAGE),
                new EmptyBorder(12, 16, 12, 16)));

        JLabel reminderRow = new JLabel("Danh sách nhắc hẹn",
                new ZaloVectorIcon(ZaloVectorIcon.Type.REMINDER, 18, ChatColor.ICON_CUT), SwingConstants.LEFT);
        reminderRow.setIconTextGap(10);
        reminderRow.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        reminderRow.setForeground(ChatColor.TEXT_MAIN);

        JLabel commonGroupRow = new JLabel("1 nhóm chung",
                new ZaloVectorIcon(ZaloVectorIcon.Type.GROUP_ADD, 18, ChatColor.ICON_GROUP), SwingConstants.LEFT);
        commonGroupRow.setIconTextGap(10);
        commonGroupRow.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        commonGroupRow.setForeground(ChatColor.TEXT_MAIN);

        metaSec.add(reminderRow);
        metaSec.add(Box.createVerticalStrut(10));
        metaSec.add(commonGroupRow);

        JPanel mediaSec = new JPanel(new BorderLayout(0, 10));
        mediaSec.setBackground(Color.WHITE);
        mediaSec.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 6, 0, ChatColor.BG_PAGE),
                new EmptyBorder(12, 16, 14, 16)));

        JLabel mediaHeader = new JLabel("Ảnh/Video",
                new ZaloVectorIcon(ZaloVectorIcon.Type.MEDIA_GALLERY, 18, ChatColor.ICON_IMAGE), SwingConstants.LEFT);
        mediaHeader.setIconTextGap(8);
        mediaHeader.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        mediaHeader.setForeground(ChatColor.TEXT_MAIN);

        infoMediaGridPanel = new JPanel(new GridLayout(2, 4, 6, 6));
        infoMediaGridPanel.setOpaque(false);

        JButton viewAllMediaBtn = new JButton("Xem tất cả");
        viewAllMediaBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        viewAllMediaBtn.setForeground(ChatColor.TEXT_MAIN);
        viewAllMediaBtn.setBackground(new Color(0xEAEDF0));
        viewAllMediaBtn.setBorderPainted(false);
        viewAllMediaBtn.setFocusPainted(false);
        viewAllMediaBtn.setPreferredSize(new Dimension(0, 32));

        mediaSec.add(mediaHeader,        BorderLayout.NORTH);
        mediaSec.add(infoMediaGridPanel, BorderLayout.CENTER);
        mediaSec.add(viewAllMediaBtn,    BorderLayout.SOUTH);

        JPanel fileSec = new JPanel(new BorderLayout(0, 10));
        fileSec.setBackground(Color.WHITE);
        fileSec.setBorder(new EmptyBorder(12, 16, 16, 16));

        JLabel fileHeader = new JLabel("File",
                new ZaloVectorIcon(ZaloVectorIcon.Type.FILE_FOLDER, 18, ChatColor.ICON_ATTACH), SwingConstants.LEFT);
        fileHeader.setIconTextGap(8);
        fileHeader.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        fileHeader.setForeground(ChatColor.TEXT_MAIN);

        infoFileListPanel = new JPanel();
        infoFileListPanel.setLayout(new BoxLayout(infoFileListPanel, BoxLayout.Y_AXIS));
        infoFileListPanel.setOpaque(false);

        fileSec.add(fileHeader,        BorderLayout.NORTH);
        fileSec.add(infoFileListPanel, BorderLayout.CENTER);

        body.add(profileSec);
        body.add(metaSec);
        body.add(mediaSec);
        body.add(fileSec);

        refreshRightInfoSharedContent();

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildCircleActionItem(ZaloVectorIcon.Type iconType, Color color, String htmlText) {
        JPanel item = new JPanel();
        item.setLayout(new BoxLayout(item, BoxLayout.Y_AXIS));
        item.setOpaque(false);
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        final boolean[] hovered = {false};
        Icon normalIcon = new ZaloVectorIcon(iconType, 18, color, false);
        Icon hoverIcon  = new ZaloVectorIcon(iconType, 18, color, true);

        JLabel circle = new JLabel(normalIcon, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered[0]) {
                    g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 38));
                } else {
                    g2.setColor(new Color(0xF0F2F5));
                }
                g2.fillOval(0, 0, 34, 34);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        circle.setPreferredSize(new Dimension(34, 34));
        circle.setMinimumSize(new Dimension(34, 34));
        circle.setMaximumSize(new Dimension(34, 34));
        circle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel txt = new JLabel(htmlText, SwingConstants.CENTER);
        txt.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        txt.setForeground(ChatColor.TEXT_SUB);
        txt.setAlignmentX(Component.CENTER_ALIGNMENT);

        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                circle.setIcon(hoverIcon);
                txt.setForeground(ChatColor.TEXT_MAIN);
                circle.repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                circle.setIcon(normalIcon);
                txt.setForeground(ChatColor.TEXT_SUB);
                circle.repaint();
            }
        };
        item.addMouseListener(hoverAdapter);
        circle.addMouseListener(hoverAdapter);

        item.add(circle);
        item.add(Box.createVerticalStrut(4));
        item.add(txt);
        return item;
    }

    private void refreshRightInfoSharedContent() {
        if (infoFileListPanel == null || infoMediaGridPanel == null) return;
        infoFileListPanel.removeAll();
        infoMediaGridPanel.removeAll();

        List<FileEntry> allEntries = sharedFilesMap.getOrDefault(activeConversation, List.of());
        List<FileEntry> imageEntries = new ArrayList<>();
        List<FileEntry> docEntries   = new ArrayList<>();

        for (FileEntry fe : allEntries) {
            if (ChatFile.isImageFile(fe.name())) imageEntries.add(fe);
            else docEntries.add(fe);
        }

        for (int i = 0; i < 8; i++) {
            JPanel thumb = new JPanel(new BorderLayout());
            thumb.setPreferredSize(new Dimension(54, 54));
            thumb.setBackground(new Color(0xF0F2F5));
            thumb.setBorder(new LineBorder(new Color(0xE2E8F0), 1, true));

            if (i < imageEntries.size()) {
                FileEntry imgFe = imageEntries.get(imageEntries.size() - 1 - i);
                if (imgFe.file() != null && imgFe.file().exists()) {
                    try {
                        BufferedImage bi = ImageIO.read(imgFe.file());
                        if (bi != null) {
                            Image scaled = bi.getScaledInstance(54, 54, Image.SCALE_SMOOTH);
                            JLabel pic = new JLabel(new ImageIcon(scaled));
                            pic.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            pic.addMouseListener(new MouseAdapter() {
                                @Override public void mouseClicked(MouseEvent e) {
                                    ChatFile.openImageViewer(imgFe.name(), imgFe.file(), bi);
                                }
                            });
                            thumb.add(pic, BorderLayout.CENTER);
                        }
                    } catch (Exception ignored) {}
                }
            }
            infoMediaGridPanel.add(thumb);
        }

        if (docEntries.isEmpty()) {
            JLabel emptyLbl = new JLabel("Chưa có tệp đính kèm");
            emptyLbl.setFont(ChatColor.FONT_SMALL);
            emptyLbl.setForeground(ChatColor.TEXT_SUB);
            infoFileListPanel.add(emptyLbl);
        } else {
            for (int i = docEntries.size() - 1; i >= Math.max(0, docEntries.size() - 5); i--) {
                FileEntry fe = docEntries.get(i);
                JPanel row = new JPanel(new BorderLayout(10, 0));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(6, 0, 6, 0));
                row.setMaximumSize(new Dimension(268, 52));

                JComponent badge = ChatFile.buildFileTypeBadge(fe.name());
                badge.setPreferredSize(new Dimension(34, 38));

                JPanel meta = new JPanel(new BorderLayout(0, 2));
                meta.setOpaque(false);
                JLabel fn = new JLabel(fe.name());
                fn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                fn.setForeground(ChatColor.TEXT_MAIN);

                JPanel sub = new JPanel(new BorderLayout());
                sub.setOpaque(false);
                JLabel sz = new JLabel(ChatFile.formatSize(fe.size()));
                sz.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                sz.setForeground(ChatColor.TEXT_SUB);
                JLabel dt = new JLabel("Hôm nay");
                dt.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                dt.setForeground(ChatColor.TEXT_SUB);
                sub.add(sz, BorderLayout.WEST);
                sub.add(dt, BorderLayout.EAST);

                meta.add(fn,  BorderLayout.NORTH);
                meta.add(sub, BorderLayout.SOUTH);

                row.add(badge, BorderLayout.WEST);
                row.add(meta,  BorderLayout.CENTER);
                infoFileListPanel.add(row);
            }
        }
        infoFileListPanel.revalidate();
        infoFileListPanel.repaint();
        infoMediaGridPanel.revalidate();
        infoMediaGridPanel.repaint();
    }

    private void switchConversation(String convId, String label) {
        activeConversation = convId;
        activeLabel        = label;
        boolean isGroup    = GROUP_ID.equals(convId);

        chatTitleLbl.setText(label);
        chatStatusLbl.setText(isGroup ? "Nhóm chat chung" : "Vừa truy cập");
        headerAvatar.updateIdentity(label, isGroup);

        if (infoAvatar != null && infoNameLbl != null) {
            infoAvatar.updateIdentity(label, isGroup);
            infoNameLbl.setText(label);
            refreshRightInfoSharedContent();
        }

        MessageFeedPanel feed = conversationFeeds.computeIfAbsent(convId, k -> new MessageFeedPanel());
        chatScroll.setViewportView(feed);
        scrollToBottom(convId);
        inputField.repaint();
        inputField.requestFocusInWindow();

        if (!isGroup && feed.getComponentCount() == 0) {
            client.requestPrivateHistory(convId);
        }
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        String now = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        appendMessage(activeConversation, selfDisplayName, text, true, now, -1L, "");

        if (GROUP_ID.equals(activeConversation)) {
            client.sendMessage(text);
        } else {
            client.sendPrivateMessage(activeConversation, text);
        }
        inputField.setText("");
    }

    private void sendImageOnly() {
        JFileChooser fc = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
        fc.setDialogTitle("Chọn hình ảnh để gửi");
        fc.setFileFilter(new FileNameExtensionFilter("Hình ảnh (PNG, JPG, JPEG, GIF, WEBP)", "png", "jpg", "jpeg", "gif", "webp", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String to = GROUP_ID.equals(activeConversation) ? "ALL" : activeConversation;
        client.sendFile(fc.getSelectedFile(), to);
    }

    private void sendFile() {
        JFileChooser fc = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
        fc.setDialogTitle("Chọn tệp đính kèm để gửi");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String to = GROUP_ID.equals(activeConversation) ? "ALL" : activeConversation;
        client.sendFile(fc.getSelectedFile(), to);
    }

    public void updateUserList(List<String[]> users) {
        this.cachedUsers = new ArrayList<>(users);
        refreshConversationListModel();
    }

    private void refreshConversationListModel() {
        ConvItem selected = convList != null ? convList.getSelectedValue() : null;
        convListModel.clear();

        if (matchesSearchAndFilter(GROUP_ID, GROUP_LABEL)) {
            convListModel.addElement(new ConvItem(GROUP_ID, GROUP_LABEL, true));
        }
        for (String[] u : cachedUsers) {
            if (u[1].equals(selfUsername)) continue;
            if (matchesSearchAndFilter(u[1], u[0])) {
                convListModel.addElement(new ConvItem(u[1], u[0], false));
            }
        }

        if (selected != null && convListModel.size() > 0) {
            for (int i = 0; i < convListModel.size(); i++) {
                if (convListModel.get(i).id.equals(selected.id)) {
                    convList.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private boolean matchesSearchAndFilter(String id, String label) {
        if (filterUnreadOnly && unreadCounts.getOrDefault(id, 0) <= 0) return false;
        if (!searchQuery.isEmpty() && !label.toLowerCase().contains(searchQuery)) return false;
        return true;
    }

    public void updateConversationPreview(String convId, String senderLabel, String rawContent,
                                          boolean isSelf, String timeLabel) {
        String cleanBody = ChatBubble.formatPreviewContent(rawContent);
        String prefix = isSelf ? "Bạn: " : (senderLabel + ": ");
        lastMsgPreview.put(convId, prefix + cleanBody);
        lastMsgTime.put(convId, (timeLabel == null || timeLabel.isEmpty()) ? "Vừa xong" : timeLabel);
        if (convList != null) convList.repaint();
    }

    public void appendMessage(String convId, String from, String text, boolean isSelf) {
        String now = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        appendMessage(convId, from, text, isSelf, now, -1L, "");
    }

    public void appendMessage(String convId, String from, String text, boolean isSelf, String sentAt) {
        appendMessage(convId, from, text, isSelf, sentAt, -1L, "");
    }

    public void appendMessage(String convId, String from, String text, boolean isSelf,
                              String sentAt, long messageId, String reactions) {
        String timeLabel = "", dateLabel = "";
        if (sentAt != null && sentAt.contains(" ")) {
            String[] p = sentAt.split(" ", 2);
            timeLabel = p[0].replace(".", ":");
            dateLabel = p[1];
        } else if (sentAt != null) {
            timeLabel = sentAt;
        }

        if (!dateLabel.isEmpty()) {
            String last = lastDatePerConv.get(convId);
            if (!dateLabel.equals(last)) {
                lastDatePerConv.put(convId, dateLabel);
                appendDateDivider(convId, formatDateDivider(dateLabel));
            }
        }

        updateConversationPreview(convId, from, text, isSelf, timeLabel);
        ChatBubble bubble = new ChatBubble(
                text,
                isSelf,
                timeLabel,
                messageId,
                selfUsername,
                client::sendReaction,
                reactions
        );
        appendBlock(convId, from, bubble, isSelf, messageId);
    }

    public void appendSystem(String convId, String msg) {
        lastSenderPerConv.remove(convId);
        appendPillDivider(convId, msg);
    }

    public void setDisconnected() {
        appendSystem(GROUP_ID, "Mất kết nối với máy chủ");
        chatStatusLbl.setForeground(ChatColor.DANGER);
        chatStatusLbl.setText("Mất kết nối với máy chủ");
    }

    public void appendFileCard(String convId, String sender, String name, long size, File file, boolean isSelf) {
        String now = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        appendFileCard(convId, sender, name, size, file, isSelf, now, -1L, "");
    }

    public void appendFileCard(String convId, String sender, String name, long size, File file, boolean isSelf, String timeLabel) {
        appendFileCard(convId, sender, name, size, file, isSelf, timeLabel, -1L, "");
    }

    public void appendFileCard(String convId, String sender, String name, long size, File file,
                               boolean isSelf, String timeLabel, long messageId, String reactions) {
        String filePreviewBody = ChatBubble.formatFilePreviewLabel(name);
        String prefix = isSelf ? "Bạn: " : (sender + ": ");
        lastMsgPreview.put(convId, prefix + filePreviewBody);
        lastMsgTime.put(convId, (timeLabel == null || timeLabel.isEmpty()) ? "Vừa xong" : timeLabel);
        if (convList != null) convList.repaint();

        sharedFilesMap.computeIfAbsent(convId, k -> new ArrayList<>())
                .add(new FileEntry(name, size, file, timeLabel));
        if (convId.equals(activeConversation)) {
            refreshRightInfoSharedContent();
        }

        ChatFile fileCard = new ChatFile(
                name,
                size,
                file,
                isSelf,
                timeLabel,
                messageId,
                selfUsername,
                client::sendReaction,
                reactions
        );
        appendBlock(convId, sender, fileCard, isSelf, messageId);
    }

    /**
     * Gắn messageId chính thức từ Server trả về (MSG_ACK) vào bong bóng tin nhắn vừa gửi
     * để người gửi hoặc người nhận có thể thả cảm xúc ngay lập tức.
     */
    public void bindPendingSelfMessageId(String convId, long messageId) {
        if (messageId <= 0) return;
        Deque<JComponent> queue = pendingSelfMessages.get(convId);
        if (queue != null && !queue.isEmpty()) {
            JComponent comp = queue.pollFirst();
            if (comp instanceof ChatBubble cb) {
                cb.setMessageId(messageId);
            } else if (comp instanceof ChatFile cf) {
                cf.setMessageId(messageId);
            }
            messageComponentMap.put(messageId, comp);
            JPanel row = (JPanel) comp.getClientProperty("messageRow");
            if (row != null) {
                messageRowMap.put(messageId, row);
            }
        }
    }

    /**
     * Cập nhật hiển thị Reaction realtime dưới bong bóng tin nhắn mà không làm nhảy khung chat.
     */
    public void updateMessageReactions(String convId, long messageId, String reactionSummary) {
        JComponent comp = messageComponentMap.get(messageId);
        if (comp == null) return;

        if (comp instanceof ChatBubble cb) {
            cb.updateReactions(reactionSummary);
        } else if (comp instanceof ChatFile cf) {
            cf.updateReactions(reactionSummary);
        }

        JPanel messageRow = messageRowMap.get(messageId);
        if (messageRow != null) {
            messageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            messageRow.invalidate();
            Dimension pref = messageRow.getPreferredSize();
            messageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
            messageRow.revalidate();
            messageRow.repaint();

            Container parent = messageRow.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }
    }

    private void appendDateDivider(String convId, String label) {
        lastSenderPerConv.remove(convId);
        appendPillDivider(convId, label);
    }

    private void appendPillDivider(String convId, String text) {
        MessageFeedPanel feed = conversationFeeds.computeIfAbsent(convId, k -> new MessageFeedPanel());

        JLabel pill = new JLabel(text, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xB6BEC9));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        pill.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        pill.setForeground(Color.WHITE);
        pill.setBorder(new EmptyBorder(3, 14, 3, 14));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        row.setOpaque(false);
        row.add(pill);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 8));

        feed.add(row);
        feed.revalidate();
        feed.repaint();
        scrollToBottom(convId);
    }

    private String formatDateDivider(String ddMMyyyy) {
        try {
            var fmt   = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
            var date  = java.time.LocalDate.parse(ddMMyyyy, fmt);
            var today = java.time.LocalDate.now();
            if (date.equals(today))              return "Hôm nay";
            if (date.equals(today.minusDays(1))) return "Hôm qua";
            return ddMMyyyy;
        } catch (Exception e) { return ddMMyyyy; }
    }

    private void appendBlock(String convId, String sender, JComponent content, boolean isSelf) {
        appendBlock(convId, sender, content, isSelf, -1L);
    }

    private void appendBlock(String convId, String sender, JComponent content, boolean isSelf, long messageId) {
        MessageFeedPanel feed = conversationFeeds.computeIfAbsent(convId, k -> new MessageFeedPanel());

        String senderKey = isSelf ? "__SELF__" : sender;
        boolean sameAsPrevious = senderKey.equals(lastSenderPerConv.get(convId));
        lastSenderPerConv.put(convId, senderKey);

        JPanel messageRow = new JPanel(new BorderLayout());
        messageRow.setOpaque(false);
        messageRow.setBorder(new EmptyBorder(sameAsPrevious ? 1 : 6, 0, 1, 0));

        if (isSelf) {
            JPanel rightWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            rightWrapper.setOpaque(false);
            rightWrapper.add(content);
            messageRow.add(rightWrapper, BorderLayout.EAST);
        } else {
            JPanel leftWrapper = new JPanel(new BorderLayout(8, 0));
            leftWrapper.setOpaque(false);

            JPanel avatarSlot = new JPanel(new BorderLayout());
            avatarSlot.setOpaque(false);
            avatarSlot.setPreferredSize(new Dimension(36, 36));
            if (!sameAsPrevious) {
                avatarSlot.add(new AvatarLabel(sender, false, 34), BorderLayout.NORTH);
            }

            JPanel bubbleStack = new JPanel(new BorderLayout(0, 2));
            bubbleStack.setOpaque(false);
            if (GROUP_ID.equals(convId) && !sameAsPrevious) {
                JLabel senderLbl = new JLabel(sender);
                senderLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                senderLbl.setForeground(ChatColor.TEXT_SUB);
                senderLbl.setBorder(new EmptyBorder(0, 4, 0, 0));
                bubbleStack.add(senderLbl, BorderLayout.NORTH);
            }
            bubbleStack.add(content, BorderLayout.CENTER);

            leftWrapper.add(avatarSlot,  BorderLayout.WEST);
            leftWrapper.add(bubbleStack, BorderLayout.CENTER);
            messageRow.add(leftWrapper,  BorderLayout.WEST);
        }

        content.putClientProperty("messageRow", messageRow);
        if (messageId > 0) {
            messageComponentMap.put(messageId, content);
            messageRowMap.put(messageId, messageRow);
        } else if (isSelf) {
            pendingSelfMessages.computeIfAbsent(convId, k -> new ArrayDeque<>()).addLast(content);
        }

        Dimension pref = messageRow.getPreferredSize();
        messageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));

        feed.add(messageRow);
        feed.revalidate();
        feed.repaint();

        if (convId.equals(activeConversation)) {
            scrollToBottom(convId);
        } else if (!isSelf) {
            unreadCounts.merge(convId, 1, Integer::sum);
            if (convList != null) convList.repaint();
        }
    }

    private void scrollToBottom(String convId) {
        if (!convId.equals(activeConversation) || chatScroll == null) return;
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = chatScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }
}