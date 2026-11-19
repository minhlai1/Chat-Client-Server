package com.mycompany.bai4.ui;

import com.mycompany.bai4.util.ChatColor;
import com.mycompany.bai4.util.EmojiManager;
import com.mycompany.bai4.util.EmojiManager.Category;
import com.mycompany.bai4.util.EmojiManager.EmojiItem;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bảng chọn Emoji hiện đại theo phong cách Zalo Desktop & Messenger:
 * - Thanh tìm kiếm song ngữ Việt/Anh tức thời kèm nút xóa nhanh.
 * - Thanh 10 danh mục Emoji với hiệu ứng gạch chân màu xanh Zalo.
 * - Lưới hiển thị Emoji đa sắc (8 cột), hiệu ứng hover bo tròn & Tooltip tên tiếng Việt.
 * - Ghi nhớ và đồng bộ danh mục Emoji gần đây theo từng tài khoản.
 */
public class EmojiPickerPopup extends JPopupMenu {

    private static final int POPUP_WIDTH  = 372;
    private static final int POPUP_HEIGHT = 420;
    private static final int GRID_COLS    = 8;
    private static final int EMOJI_ICON_SIZE = 26;

    private final String username;
    private final Consumer<String> onEmojiSelected;

    private Category activeCategory = Category.SMILEYS;
    private final Map<Category, JButton> categoryButtons = new EnumMap<>(Category.class);

    private JTextField searchField;
    private JButton clearSearchBtn;
    private JLabel categoryTitleLbl;
    private JLabel countBadgeLbl;
    private JPanel gridContainer;
    private JScrollPane gridScroll;

    public EmojiPickerPopup(String username, Consumer<String> onEmojiSelected) {
        this.username = username;
        this.onEmojiSelected = onEmojiSelected;

        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(new CompoundBorder(
                new LineBorder(new Color(0xD0D7DE), 1, true),
                new EmptyBorder(0, 0, 0, 0)
        ));
        setPreferredSize(new Dimension(POPUP_WIDTH, POPUP_HEIGHT));
        setLayout(new BorderLayout(0, 0));

        buildComponents();
        selectCategory(Category.SMILEYS);

        addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (activeCategory == Category.RECENT && searchField.getText().trim().isEmpty()) {
                    refreshGrid();
                }
                SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    private void buildComponents() {
        // ── 1. Phần Header: Ô tìm kiếm + Thanh 10 danh mục ──
        JPanel topPanel = new JPanel(new BorderLayout(0, 6));
        topPanel.setBackground(Color.WHITE);
        topPanel.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, ChatColor.DIVIDER),
                new EmptyBorder(10, 10, 4, 10)
        ));

        JPanel searchBox = new JPanel(new BorderLayout(6, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0xF0F2F5));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                if (searchField != null && searchField.isFocusOwner()) {
                    g2.setColor(ChatColor.ACCENT);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                }
                g2.dispose();
            }
        };
        searchBox.setOpaque(false);
        searchBox.setBorder(new EmptyBorder(5, 10, 5, 8));

        JLabel searchLens = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ChatColor.TEXT_SUB);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawOval(2, 2, 9, 9);
                g2.drawLine(10, 10, 14, 14);
                g2.dispose();
            }
        };
        searchLens.setPreferredSize(new Dimension(16, 16));

        searchField = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ChatColor.TEXT_DIM);
                    g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                    g2.drawString("Tìm Emoji (vd: cười, vui, tim, heart, cờ...)", 2, getHeight() - 5);
                    g2.dispose();
                }
            }
        };
        searchField.setBorder(null);
        searchField.setOpaque(false);
        searchField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        searchField.setForeground(ChatColor.TEXT_MAIN);

        clearSearchBtn = new JButton("✕");
        clearSearchBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        clearSearchBtn.setForeground(ChatColor.TEXT_SUB);
        clearSearchBtn.setBorder(null);
        clearSearchBtn.setContentAreaFilled(false);
        clearSearchBtn.setFocusPainted(false);
        clearSearchBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearSearchBtn.setPreferredSize(new Dimension(18, 18));
        clearSearchBtn.setToolTipText("Xóa nội dung tìm kiếm");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.addActionListener(e -> {
            searchField.setText("");
            searchField.requestFocusInWindow();
        });

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { onSearchChanged(); }
            @Override public void removeUpdate(DocumentEvent e)  { onSearchChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onSearchChanged(); }
        });

        searchBox.add(searchLens, BorderLayout.WEST);
        searchBox.add(searchField, BorderLayout.CENTER);
        searchBox.add(clearSearchBtn, BorderLayout.EAST);

        // Thanh 10 tab danh mục
        JPanel categoryBar = new JPanel(new GridLayout(1, Category.values().length, 2, 0));
        categoryBar.setOpaque(false);

        for (Category cat : Category.values()) {
            JButton tabBtn = createCategoryTabButton(cat);
            categoryButtons.put(cat, tabBtn);
            categoryBar.add(tabBtn);
        }

        topPanel.add(searchBox, BorderLayout.NORTH);
        topPanel.add(categoryBar, BorderLayout.SOUTH);

        // ── 2. Phần Tiêu đề danh mục hiện tại ──
        JPanel sectionHeader = new JPanel(new BorderLayout());
        sectionHeader.setBackground(new Color(0xF8FAFC));
        sectionHeader.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(0xEEF2F6)),
                new EmptyBorder(6, 12, 6, 12)
        ));

        categoryTitleLbl = new JLabel(activeCategory.title);
        categoryTitleLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        categoryTitleLbl.setForeground(ChatColor.TEXT_MAIN);

        countBadgeLbl = new JLabel();
        countBadgeLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        countBadgeLbl.setForeground(ChatColor.TEXT_SUB);

        sectionHeader.add(categoryTitleLbl, BorderLayout.WEST);
        sectionHeader.add(countBadgeLbl, BorderLayout.EAST);

        // ── 3. Phần Lưới hiển thị Emoji ──
        gridContainer = new JPanel(new BorderLayout());
        gridContainer.setBackground(Color.WHITE);

        gridScroll = new JScrollPane(gridContainer);
        gridScroll.setBorder(null);
        gridScroll.getViewport().setBackground(Color.WHITE);
        gridScroll.getVerticalScrollBar().setUnitIncrement(18);
        gridScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel centerWrap = new JPanel(new BorderLayout());
        centerWrap.setBackground(Color.WHITE);
        centerWrap.add(sectionHeader, BorderLayout.NORTH);
        centerWrap.add(gridScroll, BorderLayout.CENTER);

        // ── 4. Phần Footer ghi công & hướng dẫn ──
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(0xF8FAFC));
        footer.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, ChatColor.DIVIDER),
                new EmptyBorder(5, 12, 5, 12)
        ));
        JLabel hintLbl = new JLabel("Nhấn để chèn vào vị trí con trỏ • Twemoji/OpenMoji (CC-BY 4.0)");
        hintLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        hintLbl.setForeground(ChatColor.TEXT_SUB);
        footer.add(hintLbl, BorderLayout.WEST);

        add(topPanel, BorderLayout.NORTH);
        add(centerWrap, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JButton createCategoryTabButton(Category cat) {
        ImageIcon icon = EmojiManager.getEmojiIcon(cat.repEmoji, 18);
        JButton btn = new JButton(icon) {
            @Override
            protected void paintComponent(Graphics g) {
                boolean isSel = (cat == activeCategory) && searchField.getText().trim().isEmpty();
                boolean isHov = getModel().isRollover();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSel) {
                    g2.setColor(ChatColor.SIDEBAR_SEL);
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 4, 8, 8);
                    g2.setColor(ChatColor.ACCENT);
                    g2.fillRoundRect(4, getHeight() - 3, getWidth() - 8, 2, 2, 2);
                } else if (isHov) {
                    g2.setColor(ChatColor.ICON_HOVER_BG);
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 4, 8, 8);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setToolTipText(cat.title);
        btn.setPreferredSize(new Dimension(32, 32));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> {
            if (!searchField.getText().isEmpty()) {
                searchField.setText("");
            }
            selectCategory(cat);
        });
        return btn;
    }

    private void selectCategory(Category cat) {
        this.activeCategory = cat;
        for (JButton b : categoryButtons.values()) {
            b.repaint();
        }
        refreshGrid();
    }

    private void onSearchChanged() {
        String q = searchField.getText().trim();
        clearSearchBtn.setVisible(!q.isEmpty());
        for (JButton b : categoryButtons.values()) {
            b.repaint();
        }
        refreshGrid();
    }

    private void refreshGrid() {
        gridContainer.removeAll();
        String query = searchField.getText().trim();

        List<EmojiItem> items;
        if (!query.isEmpty()) {
            items = EmojiManager.search(query);
            categoryTitleLbl.setText("Kết quả tìm kiếm cho \"" + query + "\"");
        } else {
            items = EmojiManager.getByCategory(activeCategory, username);
            categoryTitleLbl.setText(activeCategory.title);
        }
        countBadgeLbl.setText(items.size() + " biểu cảm");

        if (items.isEmpty()) {
            JPanel emptyPanel = new JPanel();
            emptyPanel.setOpaque(false);
            emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
            emptyPanel.setBorder(new EmptyBorder(54, 20, 40, 20));

            JLabel iconLbl = new JLabel(EmojiManager.getEmojiIcon("🤔", 42));
            iconLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel msgLbl = new JLabel("Không tìm thấy Emoji phù hợp");
            msgLbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            msgLbl.setForeground(ChatColor.TEXT_MAIN);
            msgLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel subLbl = new JLabel("Thử tìm với từ khóa khác (vd: cười, tim, like, hoa, cờ...)");
            subLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            subLbl.setForeground(ChatColor.TEXT_SUB);
            subLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            emptyPanel.add(iconLbl);
            emptyPanel.add(Box.createVerticalStrut(10));
            emptyPanel.add(msgLbl);
            emptyPanel.add(Box.createVerticalStrut(4));
            emptyPanel.add(subLbl);

            gridContainer.add(emptyPanel, BorderLayout.CENTER);
        } else {
            JPanel cellsGrid = new JPanel(new GridLayout(0, GRID_COLS, 4, 4));
            cellsGrid.setBackground(Color.WHITE);
            cellsGrid.setBorder(new EmptyBorder(8, 10, 10, 10));

            for (EmojiItem item : items) {
                cellsGrid.add(createEmojiCellButton(item));
            }

            JPanel northWrap = new JPanel(new BorderLayout());
            northWrap.setBackground(Color.WHITE);
            northWrap.add(cellsGrid, BorderLayout.NORTH);
            gridContainer.add(northWrap, BorderLayout.CENTER);
        }

        gridContainer.revalidate();
        gridContainer.repaint();
        SwingUtilities.invokeLater(() -> gridScroll.getVerticalScrollBar().setValue(0));
    }

    private JButton createEmojiCellButton(EmojiItem item) {
        ImageIcon normalIcon = EmojiManager.getEmojiIcon(item.unicode(), EMOJI_ICON_SIZE);
        ImageIcon hoverIcon  = EmojiManager.getEmojiIcon(item.unicode(), EMOJI_ICON_SIZE + 3);

        JButton cell = new JButton(normalIcon) {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isRollover() || getModel().isPressed()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getModel().isPressed() ? ChatColor.SIDEBAR_SEL : new Color(0xF0F4F9));
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        cell.setRolloverIcon(hoverIcon);
        cell.setToolTipText(item.viName());
        cell.setPreferredSize(new Dimension(38, 38));
        cell.setContentAreaFilled(false);
        cell.setBorderPainted(false);
        cell.setFocusPainted(false);
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        cell.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                categoryTitleLbl.setText(item.viName());
            }
            @Override
            public void mouseExited(MouseEvent e) {
                String q = searchField.getText().trim();
                categoryTitleLbl.setText(q.isEmpty() ? activeCategory.title : "Kết quả tìm kiếm cho \"" + q + "\"");
            }
        });

        cell.addActionListener(e -> {
            EmojiManager.recordEmojiUsage(username, item.unicode());
            if (onEmojiSelected != null) {
                onEmojiSelected.accept(item.unicode());
            }
            if (activeCategory == Category.RECENT && searchField.getText().trim().isEmpty()) {
                refreshGrid();
            }
        });

        return cell;
    }
}