package com.mycompany.bai4.util;

import java.awt.Color;
import java.awt.Font;

public class ChatColor {

    // ── Zalo-style color palette ──────────────────────────────────────────────
    public static final Color NAV_BG        = new Color(0x0068FF);
    public static final Color NAV_ICON      = new Color(0xB3D4FF);
    public static final Color NAV_ICON_ACT  = Color.WHITE;

    public static final Color BG_SIDEBAR    = new Color(0xFFFFFF);
    public static final Color SIDEBAR_HOVER = new Color(0xF0F2F5);
    public static final Color SIDEBAR_SEL   = new Color(0xE8F0FE);
    public static final Color DIVIDER       = new Color(0xEBEBEB);

    public static final Color BG_PAGE       = new Color(0xEBEDF0);
    public static final Color BG_PANEL      = Color.WHITE;
    public static final Color BG_INPUT      = new Color(0xF0F2F5);

    public static final Color ACCENT        = new Color(0x0068FF);
    public static final Color ACCENT2       = new Color(0x06C755);
    public static final Color DANGER        = new Color(0xFF3B30);
    public static final Color NAME_OTHER    = new Color(0x0068FF);

    public static final Color TEXT_MAIN     = new Color(0x081C36);
    public static final Color TEXT_SUB      = new Color(0x7589A3);
    public static final Color TEXT_DIM      = new Color(0xADB5BD);

    public static final Color BUBBLE_SELF        = new Color(0xDCF0FF);
    public static final Color BUBBLE_OTHER       = Color.WHITE;
    public static final Color BUBBLE_OTHER_BORDER= new Color(0xE0E0E0);
    public static final Color BUBBLE_SELF_TEXT   = new Color(0x081C36);
    public static final Color BUBBLE_OTHER_TEXT  = new Color(0x081C36);

    public static final Color BADGE_BG   = new Color(0xFF3B30);
    public static final Color BADGE_TEXT = Color.WHITE;

    // ── Nâng cấp: Hệ thống màu sắc cho Icon chuẩn Fluent UI ──────────────────
    public static final Color ICON_MSG      = new Color(0x0866FF); // Tin nhắn
    public static final Color ICON_CONTACT  = new Color(0x13B8A6); // Danh bạ
    public static final Color ICON_GROUP    = new Color(0x8B5CF6); // Nhóm chat
    public static final Color ICON_CALL     = new Color(0x22C55E); // Gọi thoại
    public static final Color ICON_VIDEO    = new Color(0x3B82F6); // Gọi video
    public static final Color ICON_EMOJI    = new Color(0xF59E0B); // Emoji / Thông báo
    public static final Color ICON_IMAGE    = new Color(0x0EA5E9); // Hình ảnh
    public static final Color ICON_ATTACH   = new Color(0x14B8A6); // Đính kèm
    public static final Color ICON_CARD     = new Color(0x8B5CF6); // Danh thiếp
    public static final Color ICON_CUT      = new Color(0xF97316); // Cắt ảnh (Cam)
    public static final Color ICON_DANGER_UI= new Color(0xEF4444); // Xóa / Kết thúc
    public static final Color ICON_DEFAULT  = new Color(0x64748B); // Mặc định
    public static final Color ICON_HOVER_BG = new Color(0xF1F5F9); // Nền khi hover icon

    public static final Font FONT_MAIN  = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_BOLD  = new Font("Segoe UI", Font.BOLD,  14);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 12);

    private ChatColor() {}
}