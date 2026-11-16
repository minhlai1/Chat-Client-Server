package com.mycompany.bai4.util;

import java.awt.Color;
import java.awt.Font;

public class ChatColor {

    // ── Zalo-style color palette ──────────────────────────────────────────────
    // Nav bar (cột ngoài cùng bên trái)
    public static final Color NAV_BG        = new Color(0x0068FF);   // xanh Zalo
    public static final Color NAV_ICON      = new Color(0xB3D4FF);   // icon inactive
    public static final Color NAV_ICON_ACT  = Color.WHITE;           // icon active

    // Sidebar (danh sách hội thoại)
    public static final Color BG_SIDEBAR    = new Color(0xFFFFFF);
    public static final Color SIDEBAR_HOVER = new Color(0xF0F2F5);
    public static final Color SIDEBAR_SEL   = new Color(0xE8F0FE);
    public static final Color DIVIDER       = new Color(0xEBEBEB);

    // Chat area
    public static final Color BG_PAGE       = new Color(0xEBEDF0);   // nền xám nhạt giữa
    public static final Color BG_PANEL      = Color.WHITE;
    public static final Color BG_INPUT      = new Color(0xF0F2F5);

    // Accent
    public static final Color ACCENT        = new Color(0x0068FF);
    public static final Color ACCENT2       = new Color(0x06C755);   // xanh lá online
    public static final Color DANGER        = new Color(0xFF3B30);
    public static final Color NAME_OTHER    = new Color(0x0068FF);

    // Text
    public static final Color TEXT_MAIN     = new Color(0x081C36);
    public static final Color TEXT_SUB      = new Color(0x7589A3);
    public static final Color TEXT_DIM      = new Color(0xADB5BD);

    // Bubble
    public static final Color BUBBLE_SELF        = new Color(0xDCF0FF);  // xanh nhạt như Zalo
    public static final Color BUBBLE_OTHER       = Color.WHITE;
    public static final Color BUBBLE_OTHER_BORDER= new Color(0xE0E0E0);
    public static final Color BUBBLE_SELF_TEXT   = new Color(0x081C36);  // chữ đen trên nền xanh nhạt
    public static final Color BUBBLE_OTHER_TEXT  = new Color(0x081C36);

    // Badge
    public static final Color BADGE_BG   = new Color(0xFF3B30);
    public static final Color BADGE_TEXT = Color.WHITE;

    // Legacy aliases (giữ tương thích với code cũ)
    public static final Color SIDEBAR_SELECTED   = SIDEBAR_SEL;
    public static final Color SIDEBAR_GROUP_TINT = new Color(0xF5F8FF);

    // Font
    public static final Font FONT_MAIN  = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_BOLD  = new Font("Segoe UI", Font.BOLD,  14);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 12);

    private ChatColor() {}
}
