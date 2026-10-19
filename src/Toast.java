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

/** 아래쪽에 잠깐 떴다 사라지는 알림 + 동작 버튼 (예: "휴지통으로 옮겼어요  되돌리기"). 한 번에 하나만 */
final class Toast {
    static JWindow current;
    static Runnable currentClose;

    static void show(Window anchor, String text, String action, Runnable onAction, Runnable onClose) {
        if (current != null) close();
        JWindow w = new JWindow(anchor);
        if (MemoPlus.NoteWindow.TRANSLUCENT) {
            w.setBackground(new Color(0, 0, 0, 0));
            w.getRootPane().setOpaque(false);
            w.getRootPane().setBackground(new Color(0, 0, 0, 0));
            w.getLayeredPane().setOpaque(false);
        }
        JPanel p = new JPanel(new BorderLayout(14, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x2E2A27));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(10, 20, 10, 12));
        JLabel l = new JLabel(text);
        l.setFont(MemoPlus.BASE_FONT.deriveFont(13f));
        l.setForeground(new Color(0xF4EFEA));
        p.add(l, BorderLayout.CENTER);
        if (action != null) {
            JLabel a = new JLabel(action);
            a.setFont(MemoPlus.BASE_FONT.deriveFont(Font.BOLD, 13f));
            a.setForeground(new Color(0xFFC46B));
            a.setBorder(new EmptyBorder(2, 8, 2, 8));
            a.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            a.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    close();
                    onAction.run();
                }
            });
            p.add(a, BorderLayout.EAST);
        }
        w.setContentPane(p);
        w.pack();
        Rectangle area = anchor != null ? anchor.getBounds()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setLocation(area.x + (area.width - w.getWidth()) / 2, area.y + area.height - w.getHeight() - 24);
        w.setAlwaysOnTop(true);
        current = w;
        currentClose = onClose;
        w.setVisible(true);
        javax.swing.Timer t = new javax.swing.Timer(7000, e -> { if (current == w) close(); });
        t.setRepeats(false);
        t.start();
    }

    static void close() {
        JWindow w = current;
        Runnable r = currentClose;
        current = null;
        currentClose = null;
        if (w != null) w.dispose();
        if (r != null) r.run();
    }
}
