import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 카드형 확인·알림창 (JOptionPane 대신). 둥근 카드 + 동그란 아이콘 + 제목 + 설명 + 알약 버튼.
 * 메모 목록·설정처럼 네모난 창 위에서는 그 창을 살짝 어둡게 덮고 가운데에, 그 밖에는 화면 가운데에 뜬다.
 */
final class Ask {
    enum Kind { INFO, QUESTION, WARN, DANGER, BELL }

    static final int SH = 16;                                    // 그림자 여백

    static void notice(Component parent, Kind kind, String title, String desc) {
        show(parent, kind, title, desc, "확인", null, null);
    }

    static boolean confirm(Component parent, Kind kind, String title, String desc, String ok, String cancel) {
        return show(parent, kind, title, desc, ok, cancel, null);
    }

    /** extra: 카드 안에 넣을 체크 상자 등 (예: 다시 묻지 않음) */
    static boolean show(Component parent, Kind kind, String title, String desc, String ok, String cancel, JComponent extra) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        if (owner != null && !owner.isShowing()) owner = null;
        // 테두리 있는 네모 창(목록·설정)만 어둡게 덮는다. 모양 메모 창은 투명한 바깥까지 어두워지므로 덮지 않음
        boolean dim = owner != null && MemoPlus.NoteWindow.TRANSLUCENT
                && ((owner instanceof Frame f && !f.isUndecorated()) || (owner instanceof Dialog d && !d.isUndecorated()));
        JDialog dlg = new JDialog(owner, Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setUndecorated(true);
        if (MemoPlus.NoteWindow.TRANSLUCENT) {
            dlg.setBackground(new Color(0, 0, 0, 0));
            dlg.getRootPane().setOpaque(false);
            dlg.getRootPane().setBackground(new Color(0, 0, 0, 0));
            dlg.getLayeredPane().setOpaque(false);
        }
        boolean[] result = {false};
        Color cardBg = MemoPlus.DARK ? new Color(0x2B2B2B) : MemoPlus.STYLE == MemoPlus.Style.Simple ? Color.WHITE : new Color(0xFFFDF9);
        Color fg = MemoPlus.DARK ? new Color(0xF2F2F2) : MemoPlus.STYLE == MemoPlus.Style.Simple ? new Color(0x1F1F1F) : new Color(0x4A3A2C);
        Color sub = MemoPlus.DARK ? new Color(0xB5B5B5) : MemoPlus.STYLE == MemoPlus.Style.Simple ? new Color(0x666666) : new Color(0x8A7766);
        Color danger = new Color(0xD64545);
        Color accent = MemoPlus.STYLE == MemoPlus.Style.Simple ? (MemoPlus.DARK ? new Color(0x4CC2FF) : new Color(0x0067C0)) : MemoPlus.styleAccent();

        JPanel card = new JPanel() {
            java.awt.image.BufferedImage shadow;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                Shape r = new java.awt.geom.RoundRectangle2D.Double(SH, SH, w - 2 * SH, h - 2 * SH, 18, 18);
                if (MemoPlus.NoteWindow.TRANSLUCENT) {
                    if (shadow == null || shadow.getWidth() != w || shadow.getHeight() != h) {
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        Graphics2D sg = img.createGraphics();
                        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        sg.setColor(new Color(0, 0, 0, MemoPlus.DARK ? 140 : 70));
                        sg.fill(java.awt.geom.AffineTransform.getTranslateInstance(0, 4).createTransformedShape(r));
                        sg.dispose();
                        float[] k = new float[13 * 13];
                        java.util.Arrays.fill(k, 1f / k.length);
                        shadow = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(13, 13, k), java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(img, null);
                    }
                    g2.drawImage(shadow, 0, 0, null);
                }
                g2.setColor(cardBg);
                g2.fill(r);
                g2.setColor(MemoPlus.DARK ? new Color(255, 255, 255, 25) : new Color(0, 0, 0, 22));
                g2.draw(r);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(SH + 20, SH + 22, SH + 18, SH + 22));

        Color iconBg = switch (kind) {
            case DANGER -> MemoPlus.DARK ? new Color(0x5A2626) : new Color(0xFDE3E3);
            case WARN -> MemoPlus.DARK ? new Color(0x5A4A1E) : new Color(0xFDF0D2);
            default -> new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), MemoPlus.DARK ? 70 : 38);
        };
        Color iconFg = switch (kind) {
            case DANGER -> MemoPlus.DARK ? new Color(0xFF8A8A) : new Color(0xC43B3B);
            case WARN -> MemoPlus.DARK ? new Color(0xF5C451) : new Color(0xB07A0A);
            default -> accent;
        };
        JComponent icon = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int d = 44, x = (getWidth() - d) / 2;
                g2.setColor(iconBg);
                g2.fillOval(x, 0, d, d);
                g2.setColor(iconFg);
                g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = x + d / 2, cy = d / 2;
                switch (kind) {
                    case DANGER -> {
                        JLabel tmp = new JLabel();
                        tmp.setForeground(iconFg);
                        new Glyph("trash", 22).paintIcon(tmp, g2, cx - 11, cy - 11);
                    }
                    case BELL -> {
                        JLabel tmp = new JLabel();
                        tmp.setForeground(iconFg);
                        new Glyph("bell", 24).paintIcon(tmp, g2, cx - 12, cy - 12);
                    }
                    case WARN -> {
                        g2.drawLine(cx, cy - 9, cx, cy + 2);
                        g2.fillOval(cx - 2, cy + 6, 4, 4);
                    }
                    case QUESTION -> {
                        g2.draw(new java.awt.geom.Arc2D.Double(cx - 6, cy - 11, 12, 11, 160, -250, java.awt.geom.Arc2D.OPEN));
                        g2.drawLine(cx, cy, cx, cy + 2);
                        g2.fillOval(cx - 2, cy + 6, 4, 4);
                    }
                    default -> g2.drawPolyline(new int[]{cx - 8, cx - 2, cx + 9}, new int[]{cy, cy + 6, cy - 6}, 3);
                }
                g2.dispose();
            }
        };
        icon.setPreferredSize(new Dimension(44, 44));
        icon.setMinimumSize(new Dimension(44, 44));                // 세로로 눌려 사라지지 않게
        icon.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(icon);
        card.add(Box.createVerticalStrut(12));

        JLabel t = new JLabel("<html><div style='text-align:center;width:240px'>" + MemoPlus.esc(title) + "</div></html>", SwingConstants.CENTER);
        t.setFont(MemoPlus.BASE_FONT.deriveFont(Font.BOLD, 15f));
        t.setForeground(fg);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(t);
        if (desc != null && !desc.isBlank()) {
            card.add(Box.createVerticalStrut(6));
            JLabel dl = new JLabel("<html><div style='text-align:center;width:240px'>" + MemoPlus.esc(desc).replace("\n", "<br>") + "</div></html>", SwingConstants.CENTER);
            dl.setFont(MemoPlus.BASE_FONT.deriveFont(12.5f));
            dl.setForeground(sub);
            dl.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(dl);
        }
        if (extra != null) {
            card.add(Box.createVerticalStrut(10));
            extra.setOpaque(false);
            extra.setForeground(sub);
            extra.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(extra);
        }
        card.add(Box.createVerticalStrut(18));

        JPanel btns = new JPanel(new GridLayout(1, cancel == null ? 1 : 2, 8, 0));
        btns.setOpaque(false);
        btns.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton okB = new JButton(ok);
        okB.putClientProperty("JButton.buttonType", "roundRect");
        Color okBg = kind == Kind.DANGER ? danger : accent;
        okB.setBackground(okBg);
        okB.setForeground(Color.WHITE);
        // 포커스 테두리(파란 링) 없이 깔끔하게. Enter=확인, Esc=취소 는 그대로
        okB.putClientProperty("FlatLaf.style", "borderColor: " + hex(okBg) + "; focusedBorderColor: " + hex(okBg)
                + "; hoverBackground: " + hex(okBg.darker()) + "; pressedBackground: " + hex(okBg.darker().darker())
                + "; focusWidth: 0; innerFocusWidth: 0");
        okB.addActionListener(e -> { result[0] = true; dlg.dispose(); });
        if (cancel != null) {
            JButton cB = new JButton(cancel);
            cB.putClientProperty("JButton.buttonType", "roundRect");
            cB.setFocusable(false);
            cB.putClientProperty("FlatLaf.style", "focusWidth: 0; innerFocusWidth: 0");
            cB.addActionListener(e -> dlg.dispose());
            btns.add(cB);
        }
        btns.add(okB);
        btns.setPreferredSize(new Dimension(280, 36));
        btns.setMaximumSize(new Dimension(280, 36));
        card.add(btns);
        // 기본 버튼으로 지정하면 FlatLaf 가 파란 테두리를 그리므로 Enter 는 직접 연결
        okB.putClientProperty("FlatLaf.style", okB.getClientProperty("FlatLaf.style") + "; focusColor: " + hex(okBg));
        MemoPlus.NoteWindow.bind(dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), dlg.getRootPane().getActionMap(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ok", okB::doClick);
        MemoPlus.NoteWindow.bind(dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), dlg.getRootPane().getActionMap(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel", dlg::dispose);

        Dimension cs = card.getPreferredSize();
        if (dim) {                                               // 창 전체를 살짝 어둡게 덮고 가운데에 카드
            JPanel shade = new JPanel(new GridBagLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(new Color(40, 28, 20, MemoPlus.DARK ? 120 : 70));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            shade.setOpaque(false);
            shade.add(card);
            dlg.setContentPane(shade);
            Rectangle ob = owner.getBounds();
            if (owner instanceof RootPaneContainer rpc) {           // 창 안쪽(내용 영역)만 덮기
                Container cp = rpc.getContentPane();
                Point p = cp.getLocationOnScreen();
                ob = new Rectangle(p.x, p.y, cp.getWidth(), cp.getHeight());
            }
            dlg.setBounds(ob);
        } else {
            dlg.setContentPane(card);
            dlg.setSize(cs);
            if (owner != null) dlg.setLocationRelativeTo(owner); else dlg.setLocationRelativeTo(null);
        }
        dlg.setVisible(true);                                    // 닫힐 때까지 기다림 (모달)
        return result[0];
    }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }
}
