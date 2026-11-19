package com.mycompany.bai4.ui;

import com.mycompany.bai4.util.ChatColor;
import com.mycompany.bai4.util.EmojiManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Hệ thống giao diện Message Reactions chuẩn Zalo Desktop & Messenger.
 */
public class MessageReactionBar {

    public record QuickEmoji(String unicode, String label) {}

    public static final QuickEmoji[] DEFAULT_REACTIONS = {
            new QuickEmoji("❤️", "Yêu thích"),
            new QuickEmoji("😂", "Cười"),
            new QuickEmoji("😮", "Ngạc nhiên"),
            new QuickEmoji("😢", "Buồn"),
            new QuickEmoji("😡", "Tức giận"),
            new QuickEmoji("👍", "Thích"),
            new QuickEmoji("👎", "Không thích")
    };

    public record ReactorUser(String username, String displayName) {}
    public record ReactionEntry(String emoji, int count, List<ReactorUser> users) {}

    private long messageId = -1L;
    private final boolean isSelf;
    private final String selfUsername;
    private final BiConsumer<Long, String> onReactionToggle;

    private final JLabel triggerButton;
    private final JPanel pillsWrapper;
    private final JPanel pillsFlowPanel;

    private JWindow hoverBarWindow;
    private javax.swing.Timer showTimer;
    private javax.swing.Timer hideTimer;
    private boolean pointerInsideTarget = false;
    private boolean pointerInsidePopup  = false;
    private boolean extendedPickerOpen  = false;

    private final Map<String, ReactionEntry> currentReactions = new LinkedHashMap<>();

    public MessageReactionBar(long messageId,
                              boolean isSelf,
                              String selfUsername,
                              BiConsumer<Long, String> onReactionToggle) {
        this.messageId = messageId;
        this.isSelf = isSelf;
        this.selfUsername = (selfUsername != null) ? selfUsername : "";
        this.onReactionToggle = onReactionToggle;

        this.triggerButton = buildTriggerBadge();

        this.pillsFlowPanel = new JPanel(new FlowLayout(isSelf ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 2));
        this.pillsFlowPanel.setOpaque(false);

        this.pillsWrapper = new JPanel(new BorderLayout());
        this.pillsWrapper.setOpaque(false);
        this.pillsWrapper.setBorder(new EmptyBorder(2, 4, 0, 4));
        this.pillsWrapper.add(pillsFlowPanel, BorderLayout.CENTER);
        this.pillsWrapper.setVisible(false);

        initTimers();
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public long getMessageId() {
        return messageId;
    }

    public JLabel getTriggerButton() {
        return triggerButton;
    }

    public JPanel getPillsWrapper() {
        return pillsWrapper;
    }

    private void initTimers() {
        showTimer = new javax.swing.Timer(220, e -> {
            if (pointerInsideTarget && !extendedPickerOpen) {
                showQuickReactionPopup();
            }
        });
        showTimer.setRepeats(false);

        hideTimer = new javax.swing.Timer(450, e -> {
            if (isPointerOverHoverWindow()) {
                return;
            }
            if (!pointerInsideTarget && !pointerInsidePopup && !extendedPickerOpen) {
                hideQuickReactionPopup();
            }
        });
        hideTimer.setRepeats(false);
    }

    private boolean isPointerOverHoverWindow() {
        if (hoverBarWindow == null || !hoverBarWindow.isVisible()) return false;
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info == null) return false;
            Point mouse = info.getLocation();
            Rectangle bounds = hoverBarWindow.getBounds();
            bounds.grow(8, 10);
            return bounds.contains(mouse);
        } catch (Exception ignored) {
            return false;
        }
    }

    public void attachHoverTargets(Component... components) {
        MouseAdapter hoverTracker = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                pointerInsideTarget = true;
                triggerButton.setVisible(true);
                hideTimer.stop();
                showTimer.restart();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                pointerInsideTarget = false;
                showTimer.stop();
                hideTimer.restart();
            }
        };
        for (Component comp : components) {
            if (comp != null) {
                comp.addMouseListener(hoverTracker);
            }
        }
    }

    private JLabel buildTriggerBadge() {
        final boolean[] hovered = {false};
        JLabel badge = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hovered[0] ? new Color(0xF0F7FF) : Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(hovered[0] ? ChatColor.ACCENT : new Color(0xD0D7DE));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());

                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.setColor(hovered[0] ? ChatColor.ACCENT : new Color(0x64748B));
                g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(cx - 6, cy - 6, 12, 12);
                g2.fillOval(cx - 3, cy - 2, 2, 2);
                g2.fillOval(cx + 2, cy - 2, 2, 2);
                g2.drawArc(cx - 3, cy - 2, 6, 5, 195, 150);
                g2.dispose();
            }
        };
        badge.setPreferredSize(new Dimension(24, 22));
        badge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        badge.setToolTipText("Bày tỏ cảm xúc");

        badge.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                pointerInsideTarget = true;
                hideTimer.stop();
                badge.repaint();
                showQuickReactionPopup();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                pointerInsideTarget = false;
                badge.repaint();
                hideTimer.restart();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showQuickReactionPopup();
                }
            }
        });
        return badge;
    }

    private void showQuickReactionPopup() {
        if (!triggerButton.isShowing()) return;

        Window owner = SwingUtilities.getWindowAncestor(triggerButton);
        if (hoverBarWindow == null || hoverBarWindow.getOwner() != owner) {
            if (hoverBarWindow != null) hoverBarWindow.dispose();
            hoverBarWindow = createHoverWindow(owner);
        } else {
            hoverBarWindow.repaint();
        }

        Point triggerScreen = triggerButton.getLocationOnScreen();
        Dimension barSize = hoverBarWindow.getPreferredSize();
        hoverBarWindow.setSize(barSize);

        int x = isSelf
                ? (triggerScreen.x + triggerButton.getWidth() - barSize.width)
                : (triggerScreen.x - 10);
        int y = triggerScreen.y - barSize.height - 4;

        if (owner != null && owner.isShowing()) {
            Point ownerLoc = owner.getLocationOnScreen();
            int minX = ownerLoc.x + 75;
            int maxX = ownerLoc.x + owner.getWidth() - barSize.width - 16;
            x = Math.max(minX, Math.min(x, maxX));
            int minY = ownerLoc.y + 55;
            if (y < minY) {
                y = triggerScreen.y + triggerButton.getHeight() + 4;
            }
        }

        hoverBarWindow.setLocation(x, y);
        if (!hoverBarWindow.isVisible()) {
            hoverBarWindow.setVisible(true);
        }
    }

    private void hideQuickReactionPopup() {
        if (hoverBarWindow != null && hoverBarWindow.isVisible()) {
            hoverBarWindow.setVisible(false);
        }
    }

    private JWindow createHoverWindow(Window owner) {
        JWindow win = new JWindow(owner);
        win.setBackground(new Color(0, 0, 0, 0));
        win.setFocusableWindowState(false);

        JPanel pillBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                g2.setColor(new Color(0, 0, 0, 18));
                g2.fillRoundRect(2, 3, w - 4, h - 4, h - 4, h - 4);
                g2.setColor(new Color(0, 0, 0, 10));
                g2.fillRoundRect(1, 2, w - 2, h - 2, h - 2, h - 2);

                g2.setColor(Color.WHITE);
                g2.fillRoundRect(1, 1, w - 3, h - 4, h - 4, h - 4);

                g2.setColor(new Color(0xE2E8F0));
                g2.drawRoundRect(1, 1, w - 3, h - 4, h - 4, h - 4);
                g2.dispose();
            }
        };
        pillBar.setOpaque(false);
        pillBar.setBorder(new EmptyBorder(2, 8, 4, 8));

        MouseAdapter popupMouseTracker = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                pointerInsidePopup = true;
                hideTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                pointerInsidePopup = false;
                hideTimer.restart();
            }
        };
        pillBar.addMouseListener(popupMouseTracker);

        for (QuickEmoji qe : DEFAULT_REACTIONS) {
            pillBar.add(createQuickEmojiButton(qe, popupMouseTracker));
        }

        pillBar.add(createMoreEmojiButton(popupMouseTracker));

        win.setContentPane(pillBar);
        win.pack();
        return win;
    }

    private JLabel createQuickEmojiButton(QuickEmoji qe, MouseListener tracker) {
        ImageIcon normalIcon = EmojiManager.getEmojiIcon(qe.unicode(), 22);
        ImageIcon hoverIcon  = EmojiManager.getEmojiIcon(qe.unicode(), 27);
        final boolean[] hovered = {false};

        JLabel item = new JLabel(normalIcon, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                boolean sel = qe.unicode().equals(findSelfActiveEmoji());
                if (hovered[0] || sel) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(sel ? new Color(0xDBEAFE) : new Color(0xF1F5F9));
                    g2.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
                    if (sel) {
                        g2.setColor(ChatColor.ACCENT);
                        g2.fillOval(getWidth() / 2 - 2, getHeight() - 4, 4, 4);
                    }
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        item.setToolTipText(qe.label());
        item.setPreferredSize(new Dimension(32, 32));
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.addMouseListener(tracker);
        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                pointerInsidePopup = true;
                hideTimer.stop();
                item.setIcon(hoverIcon);
                item.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                item.setIcon(normalIcon);
                item.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    hovered[0] = false;
                    item.setIcon(normalIcon);
                    hideQuickReactionPopup();
                    fireToggleReaction(qe.unicode());
                }
            }
        });
        return item;
    }

    private JLabel createMoreEmojiButton(MouseListener tracker) {
        final boolean[] hovered = {false};
        JLabel btn = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hovered[0] ? new Color(0xE2E8F0) : new Color(0xF1F5F9));
                g2.fillOval(2, 2, getWidth() - 4, getHeight() - 4);

                g2.setColor(hovered[0] ? ChatColor.ACCENT : new Color(0x475569));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.drawLine(cx - 4, cy, cx + 4, cy);
                g2.drawLine(cx, cy - 4, cx, cy + 4);
                g2.dispose();
            }
        };
        btn.setToolTipText("Chọn cảm xúc khác");
        btn.setPreferredSize(new Dimension(28, 28));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(tracker);
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                pointerInsidePopup = true;
                hideTimer.stop();
                btn.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                btn.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    hovered[0] = false;
                    hideQuickReactionPopup();
                    openExtendedEmojiPicker();
                }
            }
        });
        return btn;
    }

    private void openExtendedEmojiPicker() {
        if (!triggerButton.isShowing()) return;
        extendedPickerOpen = true;
        final EmojiPickerPopup[] holder = new EmojiPickerPopup[1];
        holder[0] = new EmojiPickerPopup(selfUsername, selectedEmoji -> {
            if (holder[0] != null) {
                holder[0].setVisible(false);
            }
            extendedPickerOpen = false;
            fireToggleReaction(selectedEmoji);
        });
        holder[0].addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                extendedPickerOpen = false;
            }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                extendedPickerOpen = false;
            }
        });

        Dimension pref = holder[0].getPreferredSize();
        int x = isSelf ? (triggerButton.getWidth() - pref.width) : 0;
        int y = -pref.height - 4;
        if (triggerButton.getLocationOnScreen().y - pref.height < 40) {
            y = triggerButton.getHeight() + 4;
        }
        holder[0].show(triggerButton, x, y);
    }

    private void fireToggleReaction(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        if (messageId <= 0) {
            System.err.println("[Reaction] Chưa có messageId hợp lệ cho tin nhắn này (messageId=" + messageId + ").");
            return;
        }
        if (onReactionToggle != null) {
            onReactionToggle.accept(messageId, emoji);
        }
    }

    private String findSelfActiveEmoji() {
        for (ReactionEntry entry : currentReactions.values()) {
            for (ReactorUser u : entry.users()) {
                if (u.username().equals(selfUsername)) {
                    return entry.emoji();
                }
            }
        }
        return null;
    }

    public void updateReactionsFromSummary(String summary) {
        currentReactions.clear();
        if (summary != null && !summary.isBlank()) {
            String[] items = summary.split(",");
            for (String item : items) {
                String[] parts = item.split("=", 3);
                if (parts.length >= 2) {
                    String emoji = parts[0].trim();
                    int count;
                    try {
                        count = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    List<ReactorUser> users = new ArrayList<>();
                    if (parts.length == 3 && !parts[2].isBlank()) {
                        for (String uToken : parts[2].split("~")) {
                            String[] up = uToken.split("\\|", 2);
                            String uname = up[0].trim();
                            String dname = (up.length > 1 && !up[1].isBlank()) ? up[1].trim() : uname;
                            users.add(new ReactorUser(uname, dname));
                        }
                    }
                    if (count > 0 && !emoji.isEmpty()) {
                        currentReactions.put(emoji, new ReactionEntry(emoji, count, users));
                    }
                }
            }
        }
        rebuildReactionPills();
    }

    private void rebuildReactionPills() {
        pillsFlowPanel.removeAll();
        if (currentReactions.isEmpty()) {
            pillsWrapper.setVisible(false);
        } else {
            for (ReactionEntry entry : currentReactions.values()) {
                pillsFlowPanel.add(createReactionPill(entry));
            }
            pillsWrapper.setVisible(true);
        }
        pillsFlowPanel.revalidate();
        pillsFlowPanel.repaint();
        pillsWrapper.revalidate();
        pillsWrapper.repaint();
        if (hoverBarWindow != null && hoverBarWindow.isVisible()) {
            hoverBarWindow.repaint();
        }
    }

    private JComponent createReactionPill(ReactionEntry entry) {
        boolean reactedByMe = entry.users().stream().anyMatch(u -> u.username().equals(selfUsername));
        ImageIcon icon = EmojiManager.getEmojiIcon(entry.emoji(), 16);

        final boolean[] hovered = {false};
        JPanel pill = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                g2.setColor(new Color(0, 0, 0, 10));
                g2.fillRoundRect(0, 1, w - 1, h - 1, h, h);

                if (reactedByMe) {
                    g2.setColor(hovered[0] ? new Color(0xCFE5FF) : new Color(0xE1F0FF));
                } else {
                    g2.setColor(hovered[0] ? new Color(0xF1F5F9) : Color.WHITE);
                }
                g2.fillRoundRect(0, 0, w - 1, h - 2, h, h);

                g2.setColor(reactedByMe ? new Color(0x74B4FF) : new Color(0xCBD5E1));
                g2.drawRoundRect(0, 0, w - 1, h - 2, h, h);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        pill.setOpaque(false);
        pill.setBorder(new EmptyBorder(1, 5, 2, 6));
        pill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel iconLbl = new JLabel(icon);
        pill.add(iconLbl);

        JLabel countLbl = null;
        if (entry.count() >= 1) {
            countLbl = new JLabel(String.valueOf(entry.count()));
            countLbl.setFont(new Font(Font.SANS_SERIF, reactedByMe ? Font.BOLD : Font.PLAIN, 11));
            countLbl.setForeground(reactedByMe ? ChatColor.ACCENT : ChatColor.TEXT_MAIN);
            pill.add(countLbl);
        }

        StringBuilder tip = new StringBuilder("<html><b>");
        EmojiManager.EmojiItem emInfo = EmojiManager.findEmojiItem(entry.emoji());
        tip.append(emInfo != null ? emInfo.viName() : entry.emoji()).append(" (").append(entry.count()).append(")</b><br>");
        for (int i = 0; i < entry.users().size(); i++) {
            ReactorUser ru = entry.users().get(i);
            tip.append("• ").append(ru.username().equals(selfUsername) ? (ru.displayName() + " (Bạn)") : ru.displayName());
            if (i < entry.users().size() - 1) tip.append("<br>");
        }
        tip.append("<br><i>Nhấn để thả/bỏ • Chuột phải xem chi tiết</i></html>");
        String tipHtml = tip.toString();
        pill.setToolTipText(tipHtml);
        iconLbl.setToolTipText(tipHtml);
        if (countLbl != null) countLbl.setToolTipText(tipHtml);

        MouseAdapter pillMouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                pill.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                pill.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showReactorsPopup(pill);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    fireToggleReaction(entry.emoji());
                }
            }
        };
        pill.addMouseListener(pillMouse);
        iconLbl.addMouseListener(pillMouse);
        if (countLbl != null) countLbl.addMouseListener(pillMouse);
        return pill;
    }

    private void showReactorsPopup(Component invoker) {
        if (currentReactions.isEmpty()) return;
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(Color.WHITE);
        popup.setBorder(new EmptyBorder(6, 10, 6, 10));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel header = new JLabel("Biểu cảm trên tin nhắn");
        header.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        header.setForeground(ChatColor.TEXT_MAIN);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        content.add(header);

        for (ReactionEntry entry : currentReactions.values()) {
            for (ReactorUser u : entry.users()) {
                JPanel row = new JPanel(new BorderLayout(10, 0));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(3, 0, 3, 0));

                String nameText = u.username().equals(selfUsername)
                        ? (u.displayName() + " (Bạn)")
                        : u.displayName();
                JLabel nameLbl = new JLabel(nameText);
                nameLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                nameLbl.setForeground(ChatColor.TEXT_MAIN);

                JLabel emLbl = new JLabel(EmojiManager.getEmojiIcon(entry.emoji(), 16));
                row.add(nameLbl, BorderLayout.WEST);
                row.add(emLbl, BorderLayout.EAST);
                content.add(row);
            }
        }

        popup.add(content);
        popup.show(invoker, 0, invoker.getHeight() + 2);
    }
}