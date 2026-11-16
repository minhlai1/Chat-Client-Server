package com.mycompany.bai4.ui;

import com.mycompany.bai4.client.ChatClient;
import com.mycompany.bai4.util.ChatColor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Cửa sổ giao diện Cuộc gọi thoại & Cuộc gọi video theo phong cách Zalo Desktop.
 * Hỗ trợ:
 * - Gọi đi (CALLING): Avatar, Tên, Trạng thái "Đang gọi...", Nút Hủy.
 * - Cuộc gọi đến (RINGING): Avatar, Tên, Loại cuộc gọi, Nút Chấp nhận & Từ chối.
 * - Đang kết nối / Đã kết nối (CONNECTING / CONNECTED): Đồng hồ đếm giờ, Nút Bật/Tắt Mic,
 *   Bật/Tắt Camera, Khung Video chính (Remote) giữ tỷ lệ & Khung Video PiP (Local).
 */
public class CallWindow extends JDialog {

    private final ChatClient client;
    private final long callId;
    private final String peerUsername;
    private final String peerDisplayName;
    private final boolean videoCall;
    private final boolean incoming;

    private JLabel statusLabel;
    private JLabel timerLabel;
    private VideoCanvas remoteVideoCanvas;
    private VideoCanvas localVideoCanvas;
    private JPanel centerStack;
    private CardLayout centerCardLayout;

    private JButton btnAccept;
    private JButton btnReject;
    private JButton btnCancelOrEnd;
    private JButton btnToggleMic;
    private JButton btnToggleCam;

    private javax.swing.Timer durationTimer;
    private int elapsedSeconds = 0;
    private boolean micMuted = false;
    private boolean camEnabled = true;

    public CallWindow(Frame owner, ChatClient client, long callId,
                      String peerUsername, String peerDisplayName,
                      boolean videoCall, boolean incoming) {
        super(owner, (videoCall ? "Cuộc gọi video - " : "Cuộc gọi thoại - ") + peerDisplayName, false);
        this.client = client;
        this.callId = callId;
        this.peerUsername = peerUsername;
        this.peerDisplayName = peerDisplayName;
        this.videoCall = videoCall;
        this.incoming = incoming;

        buildUI();
        setSize(videoCall ? 820 : 400, videoCall ? 580 : 500);
        setMinimumSize(new Dimension(380, 460));
        setLocationRelativeTo(owner);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                client.onUserHangupFromUI();
            }
        });
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(0x18222D));

        // ── Phần Trung tâm: Chuyển đổi giữa Chế độ Avatar và Chế độ Video ──
        centerCardLayout = new CardLayout();
        centerStack = new JPanel(centerCardLayout);
        centerStack.setOpaque(false);

        JPanel avatarView = buildAvatarView();
        centerStack.add(avatarView, "AVATAR");

        if (videoCall) {
            JPanel videoView = buildVideoStageView();
            centerStack.add(videoView, "VIDEO");
        }

        // ── Thanh Điều khiển phía dưới ──
        JPanel bottomControls = buildBottomControls();

        root.add(centerStack, BorderLayout.CENTER);
        root.add(bottomControls, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel buildAvatarView() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(0x0F2027),
                        0, getHeight(), new Color(0x203A43));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(48, 24, 24, 24));

        // Vòng tròn Avatar lớn
        JLabel avatarCircle = new JLabel(extractInitials(peerDisplayName), SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 35));
                g2.fillOval(0, 0, 116, 116);
                g2.setColor(ChatColor.ACCENT);
                g2.fillOval(6, 6, 104, 104);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        avatarCircle.setFont(new Font("Segoe UI", Font.BOLD, 34));
        avatarCircle.setForeground(Color.WHITE);
        avatarCircle.setPreferredSize(new Dimension(116, 116));
        avatarCircle.setMinimumSize(new Dimension(116, 116));
        avatarCircle.setMaximumSize(new Dimension(116, 116));
        avatarCircle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel nameLbl = new JLabel(peerDisplayName);
        nameLbl.setFont(new Font("Segoe UI", Font.BOLD, 22));
        nameLbl.setForeground(Color.WHITE);
        nameLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        String initStatus = incoming
                ? (videoCall ? "Cuộc gọi video đến..." : "Cuộc gọi thoại đến...")
                : "Đang đổ chuông...";
        statusLabel = new JLabel(initStatus);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        statusLabel.setForeground(new Color(0xB0C4DE));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        timerLabel = new JLabel("00:00");
        timerLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        timerLabel.setForeground(new Color(0x06C755));
        timerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        timerLabel.setVisible(false);

        panel.add(Box.createVerticalGlue());
        panel.add(avatarCircle);
        panel.add(Box.createVerticalStrut(18));
        panel.add(nameLbl);
        panel.add(Box.createVerticalStrut(8));
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(timerLabel);
        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private JPanel buildVideoStageView() {
        JPanel stage = new JPanel(new BorderLayout());
        stage.setBackground(new Color(0x101418));

        remoteVideoCanvas = new VideoCanvas("Đang chờ hình ảnh từ " + peerDisplayName + "...", false);
        localVideoCanvas  = new VideoCanvas("Camera của bạn", true);
        localVideoCanvas.setPreferredSize(new Dimension(180, 135));
        localVideoCanvas.setBorder(new LineBorder(new Color(255, 255, 255, 160), 2, true));

        // Thanh thông tin nổi phía trên màn hình Video
        JPanel topOverlay = new JPanel(new BorderLayout());
        topOverlay.setBackground(new Color(0, 0, 0, 140));
        topOverlay.setBorder(new EmptyBorder(10, 16, 10, 16));

        JLabel peerTitle = new JLabel(peerDisplayName + "  •  Đã kết nối UDP");
        peerTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        peerTitle.setForeground(Color.WHITE);

        JPanel rightOverlay = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        rightOverlay.setOpaque(false);
        rightOverlay.add(localVideoCanvas);

        topOverlay.add(peerTitle, BorderLayout.WEST);

        JPanel centerOverlayWrap = new JPanel(new BorderLayout());
        centerOverlayWrap.setOpaque(false);
        JPanel pipCorner = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 16));
        pipCorner.setOpaque(false);
        pipCorner.add(localVideoCanvas);

        remoteVideoCanvas.setLayout(new BorderLayout());
        remoteVideoCanvas.add(pipCorner, BorderLayout.SOUTH);

        stage.add(topOverlay, BorderLayout.NORTH);
        stage.add(remoteVideoCanvas, BorderLayout.CENTER);
        return stage;
    }

    private JPanel buildBottomControls() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 16));
        bar.setBackground(new Color(0x1E293B));
        bar.setBorder(new EmptyBorder(6, 16, 10, 16));

        btnToggleMic = createControlPillButton("Tắt Mic", new Color(0x334155), Color.WHITE);
        btnToggleCam = createControlPillButton("Tắt Cam", new Color(0x334155), Color.WHITE);
        btnAccept    = createControlPillButton("Chấp nhận", new Color(0x06C755), Color.WHITE);
        btnReject    = createControlPillButton("Từ chối", new Color(0xE53935), Color.WHITE);
        btnCancelOrEnd = createControlPillButton(incoming ? "Kết thúc" : "Hủy cuộc gọi", new Color(0xE53935), Color.WHITE);

        btnToggleMic.setVisible(false);
        btnToggleCam.setVisible(false);

        if (incoming) {
            btnAccept.setVisible(true);
            btnReject.setVisible(true);
            btnCancelOrEnd.setVisible(false);
        } else {
            btnAccept.setVisible(false);
            btnReject.setVisible(false);
            btnCancelOrEnd.setVisible(true);
        }

        btnAccept.addActionListener(e -> client.acceptIncomingCall());
        btnReject.addActionListener(e -> client.rejectIncomingCall());
        btnCancelOrEnd.addActionListener(e -> client.onUserHangupFromUI());

        btnToggleMic.addActionListener(e -> {
            micMuted = !micMuted;
            client.setCallMicMuted(micMuted);
            if (micMuted) {
                btnToggleMic.setText("Bật Mic");
                btnToggleMic.setBackground(new Color(0xD97706));
            } else {
                btnToggleMic.setText("Tắt Mic");
                btnToggleMic.setBackground(new Color(0x334155));
            }
        });

        btnToggleCam.addActionListener(e -> {
            camEnabled = !camEnabled;
            client.setCallCameraEnabled(camEnabled);
            if (!camEnabled) {
                btnToggleCam.setText("Bật Cam");
                btnToggleCam.setBackground(new Color(0xD97706));
                if (localVideoCanvas != null) localVideoCanvas.setFrame(null, "Đã tắt Camera");
            } else {
                btnToggleCam.setText("Tắt Cam");
                btnToggleCam.setBackground(new Color(0x334155));
            }
        });

        bar.add(btnToggleMic);
        if (videoCall) {
            bar.add(btnToggleCam);
        }
        bar.add(btnAccept);
        bar.add(btnReject);
        bar.add(btnCancelOrEnd);

        return bar;
    }

    private JButton createControlPillButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(132, 42));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cập nhật trạng thái cuộc gọi trên UI
    // ══════════════════════════════════════════════════════════════════════════

    public void showConnectingState() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Đang thiết lập kết nối UDP trực tiếp...");
            btnAccept.setVisible(false);
            btnReject.setVisible(false);
            btnCancelOrEnd.setText("Kết thúc");
            btnCancelOrEnd.setVisible(true);
        });
    }

    public void showConnectedState() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Đã kết nối");
            timerLabel.setVisible(true);
            btnAccept.setVisible(false);
            btnReject.setVisible(false);
            btnToggleMic.setVisible(true);
            if (videoCall) {
                btnToggleCam.setVisible(true);
                centerCardLayout.show(centerStack, "VIDEO");
            }
            btnCancelOrEnd.setText("Kết thúc");
            btnCancelOrEnd.setVisible(true);
            startDurationTimer();
        });
    }

    public void updateRemoteVideo(BufferedImage img) {
        if (remoteVideoCanvas != null) {
            remoteVideoCanvas.setFrame(img, null);
        }
    }

    public void updateLocalVideo(BufferedImage img) {
        if (localVideoCanvas != null && camEnabled) {
            localVideoCanvas.setFrame(img, null);
        }
    }

    public void showRemoteCameraOff() {
        if (remoteVideoCanvas != null) {
            remoteVideoCanvas.setFrame(null, peerDisplayName + " đang tắt Camera");
        }
    }

    private void startDurationTimer() {
        if (durationTimer != null) durationTimer.stop();
        elapsedSeconds = 0;
        durationTimer = new javax.swing.Timer(1000, e -> {
            elapsedSeconds++;
            int mins = elapsedSeconds / 60;
            int secs = elapsedSeconds % 60;
            String formatted = String.format("%02d:%02d", mins, secs);
            timerLabel.setText(formatted);
            setTitle((videoCall ? "Cuộc gọi video - " : "Cuộc gọi thoại - ")
                    + peerDisplayName + " (" + formatted + ")");
        });
        durationTimer.start();
    }

    public void closeWindow() {
        SwingUtilities.invokeLater(() -> {
            if (durationTimer != null) {
                durationTimer.stop();
                durationTimer = null;
            }
            dispose();
        });
    }

    private static String extractInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    /**
     * Panel vẽ khung hình Video tự động co giãn giữ nguyên tỷ lệ khung hình (Aspect Ratio).
     */
    private static class VideoCanvas extends JPanel {
        private volatile BufferedImage currentFrame;
        private volatile String placeholderText;
        private final boolean mirror;

        VideoCanvas(String placeholderText, boolean mirror) {
            this.placeholderText = placeholderText;
            this.mirror = mirror;
            setBackground(new Color(0x0D1117));
        }

        void setFrame(BufferedImage frame, String textIfNull) {
            this.currentFrame = frame;
            if (textIfNull != null) {
                this.placeholderText = textIfNull;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            BufferedImage img = this.currentFrame;
            if (img != null) {
                int pw = getWidth();
                int ph = getHeight();
                int iw = img.getWidth();
                int ih = img.getHeight();
                double scale = Math.min((double) pw / iw, (double) ph / ih);
                int dw = (int) Math.round(iw * scale);
                int dh = (int) Math.round(ih * scale);
                int dx = (pw - dw) / 2;
                int dy = (ph - dh) / 2;

                if (mirror) {
                    g2.drawImage(img, dx + dw, dy, -dw, dh, this);
                } else {
                    g2.drawImage(img, dx, dy, dw, dh, this);
                }
            } else if (placeholderText != null) {
                g2.setColor(new Color(0x94A3B8));
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                FontMetrics fm = g2.getFontMetrics();
                int tx = (getWidth() - fm.stringWidth(placeholderText)) / 2;
                int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(placeholderText, Math.max(8, tx), Math.max(16, ty));
            }
            g2.dispose();
        }
    }
}