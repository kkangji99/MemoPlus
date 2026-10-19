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

/** 글꼴에 의존하지 않는 벡터 아이콘 (버튼 글자색을 따라감) */
final class Glyph implements Icon {
    final String kind;
    final int size;

    Glyph(String kind, int size) {
        this.kind = kind;
        this.size = size;
    }

    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }

    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        Color fg = c != null ? c.getForeground() : Color.DARK_GRAY;
        g2.setColor(fg);
        g2.translate(x, y);
        float s = size;
        // 통통하고 끝이 둥근 선 = 아기자기한 느낌
        g2.setStroke(MemoPlus.cute() ? new BasicStroke(Math.max(1.6f, s / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(Math.max(1.4f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "plus" -> { line(g2, .24, .5, .76, .5); line(g2, .5, .24, .5, .76); }
            case "close" -> { line(g2, .3, .3, .7, .7); line(g2, .7, .3, .3, .7); }
            case "more" -> { for (double cx : new double[]{.22, .5, .78}) dot(g2, cx, .52, .095); }
            case "bell" -> {                                     // 종: 몸통 + 아래 테두리 + 추
                java.awt.geom.Path2D.Double b = new java.awt.geom.Path2D.Double();
                b.moveTo(s * .2, s * .72);
                b.curveTo(s * .28, s * .62, s * .26, s * .5, s * .27, s * .4);
                b.curveTo(s * .29, s * .2, s * .71, s * .2, s * .73, s * .4);
                b.curveTo(s * .74, s * .5, s * .72, s * .62, s * .8, s * .72);
                b.closePath();
                g2.draw(b);
                line(g2, .5, .12, .5, .2);
                g2.draw(new java.awt.geom.Arc2D.Double(s * .41, s * .7, s * .18, s * .16, 180, 180, java.awt.geom.Arc2D.OPEN));
            }
            case "list" -> {
                for (double cy : new double[]{.3, .52, .74}) { dot(g2, .2, cy, .075); line(g2, .38, cy, .82, cy); }
            }
            case "bullets" -> {
                for (double cy : new double[]{.28, .5, .72}) { dot(g2, .2, cy, .085); line(g2, .4, cy, .84, cy); }
            }
            case "search" -> {
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * .14, s * .14, s * .5, s * .5));
                line(g2, .58, .58, .84, .84);
            }
            case "heart" -> {
                Shape h = MemoPlus.NoteShape.Heart.outline(s * .1, s * .14, s * .8, s * .74);
                g2.setColor(MemoPlus.PINK);
                g2.fill(h);
            }
            case "lock" -> {                                     // 자물쇠
                g2.draw(new java.awt.geom.Arc2D.Double(s * .3, s * .1, s * .4, s * .42, 0, 180, java.awt.geom.Arc2D.OPEN));
                line(g2, .3, .31, .3, .44);
                line(g2, .7, .31, .7, .44);
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .2, s * .44, s * .6, s * .44, s * .12, s * .12));
                dot(g2, .5, .63, .06);
                line(g2, .5, .66, .5, .74);
            }
            case "tree" -> {                                     // 크리스마스 트리: 3단 초록 + 금별 + 빨간 장식
                g2.setColor(new Color(0x8B5A2B));
                g2.fill(new java.awt.geom.Rectangle2D.Double(s * .44, s * .8, s * .12, s * .14));
                g2.setColor(MemoPlus.XMAS_GREEN);
                for (double[] tier : new double[][]{{.16, .24, .5}, {.32, .16, .66}, {.5, .08, .84}}) {
                    java.awt.geom.Path2D.Double tri = new java.awt.geom.Path2D.Double();
                    tri.moveTo(s * .5, s * tier[0]);
                    tri.lineTo(s * (.5 + tier[2] / 2 + .04), s * (tier[0] + .34 - tier[1] * .2));
                    tri.lineTo(s * (.5 - tier[2] / 2 - .04), s * (tier[0] + .34 - tier[1] * .2));
                    tri.closePath();
                    g2.fill(tri);
                }
                g2.setColor(MemoPlus.XMAS_RED);
                dot(g2, .4, .52, .045);
                dot(g2, .62, .68, .045);
                g2.setColor(MemoPlus.XMAS_GOLD);
                g2.fill(MemoPlus.NoteShape.Star.outline(s * .38, s * .02, s * .24, s * .22));
            }
            case "blossom" -> Deco.blossom(g2, s * .5, s * .5, s * .46, new Color(0xF48FB1));
            case "sun" -> {
                g2.setColor(new Color(0xF5B82E));
                g2.setStroke(new BasicStroke(Math.max(1.6f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI / 4 * k;
                    line(g2, .5 + Math.cos(a) * .32, .5 + Math.sin(a) * .32, .5 + Math.cos(a) * .44, .5 + Math.sin(a) * .44);
                }
                g2.setColor(new Color(0xFFCB45));
                dot(g2, .5, .5, .22);
            }
            case "leaf" -> {
                g2.setColor(new Color(0xE0582B));
                g2.fill(Deco.maple(s * .5, s * .48, s * .42, 0));
            }
            case "snowflake" -> {
                g2.setColor(new Color(0x4C8FC9));
                for (int k = 0; k < 3; k++) {
                    double a = Math.PI / 3 * k + Math.PI / 2;
                    line(g2, .5 - Math.cos(a) * .4, .5 - Math.sin(a) * .4, .5 + Math.cos(a) * .4, .5 + Math.sin(a) * .4);
                    for (int sgn : new int[]{-1, 1}) {               // 가지 끝 V자
                        double ex = .5 + sgn * Math.cos(a) * .28, ey = .5 + sgn * Math.sin(a) * .28;
                        line(g2, ex, ey, ex + sgn * Math.cos(a + .7) * .12, ey + sgn * Math.sin(a + .7) * .12);
                        line(g2, ex, ey, ex + sgn * Math.cos(a - .7) * .12, ey + sgn * Math.sin(a - .7) * .12);
                    }
                }
            }
            case "star", "starFilled" -> {
                Shape st = MemoPlus.NoteShape.Star.outline(s * .06, s * .04, s * .88, s * .88);
                if (kind.equals("starFilled")) {
                    g2.setColor(new Color(0xFFCB45));
                    g2.fill(st);
                    g2.setColor(new Color(0xF0A800));
                    g2.setStroke(new BasicStroke(Math.max(1.2f, s / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                g2.draw(st);
            }
            case "checklist" -> {
                double r = s * .12;
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .08, s * .12, s * .3, s * .3, r, r));
                poly(g2, new double[]{.15, .22, .33}, new double[]{.27, .34, .2});
                line(g2, .52, .27, .88, .27);
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .08, s * .58, s * .3, s * .3, r, r));
                line(g2, .52, .73, .88, .73);
            }
            case "trash" -> {                                    // 뚜껑 손잡이가 동그란 휴지통
                line(g2, .2, .28, .8, .28);
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * .42, s * .1, s * .16, s * .14));
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .27, s * .28, s * .46, s * .6, s * .18, s * .18));
                line(g2, .43, .45, .43, .7);
                line(g2, .57, .45, .57, .7);
            }
            case "pin" -> {                                      // 심플: 선 압정 / 아기자기: 분홍 머리 압정
                if (!MemoPlus.cute()) {
                    poly(g2, new double[]{.38, .62, .62, .72, .28, .38}, new double[]{.14, .14, .48, .62, .62, .48});
                    line(g2, .5, .62, .5, .88);
                    break;
                }
                line(g2, .5, .55, .5, .9);
                g2.setColor(MemoPlus.styleAccent());
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * .22, s * .08, s * .56, s * .5));
                g2.setColor(new Color(255, 255, 255, 170));
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * .33, s * .17, s * .14, s * .12));
            }
            case "info" -> {                                     // 사용설명서: 동그라미 안의 i
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * .1, s * .1, s * .8, s * .8));
                dot(g2, .5, .32, .065);
                line(g2, .5, .46, .5, .7);
            }
            case "gear" -> {                                     // 흔히 쓰는 설정 톱니바퀴 (톱니 8개 + 가운데 구멍)
                java.awt.geom.Area gear = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(s * .2, s * .2, s * .6, s * .6));
                for (int k = 0; k < 8; k++) {
                    Shape tooth = new java.awt.geom.RoundRectangle2D.Double(s * .41, s * .07, s * .18, s * .2, s * .06, s * .06);
                    gear.add(new java.awt.geom.Area(java.awt.geom.AffineTransform.getRotateInstance(Math.PI / 4 * k, s * .5, s * .5)
                            .createTransformedShape(tooth)));
                }
                g2.draw(gear);
                g2.draw(new java.awt.geom.Ellipse2D.Double(s * .37, s * .37, s * .26, s * .26));
            }
            case "image" -> {                                    // 노란 해와 언덕
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .1, s * .16, s * .8, s * .68, s * .24, s * .24));
                poly(g2, new double[]{.18, .4, .55, .64, .82}, new double[]{.74, .5, .64, .56, .72});
                g2.setColor(MemoPlus.cute() ? new Color(0xFFC93C) : fg);
                g2.fill(new java.awt.geom.Ellipse2D.Double(s * .56, s * .27, s * .17, s * .17));
            }
            default -> { }
        }
        g2.dispose();
    }

    private void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1 * size, y1 * size, x2 * size, y2 * size));
    }

    private void dot(Graphics2D g, double cx, double cy, double r) {
        g.fill(new java.awt.geom.Ellipse2D.Double((cx - r) * size, (cy - r) * size, 2 * r * size, 2 * r * size));
    }

    private void poly(Graphics2D g, double[] xs, double[] ys) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(xs[0] * size, ys[0] * size);
        for (int i = 1; i < xs.length; i++) p.lineTo(xs[i] * size, ys[i] * size);
        g.draw(p);
    }
}

/** 할 일 항목 앞의 체크박스 (글자색을 따라 그림) */
final class CheckIcon implements Icon {
    static final int BOX = 15, GAP = 7, INDENT_PX = 22;
    final boolean done;
    final int depth;

    CheckIcon(boolean done, int depth) {
        this.done = done;
        this.depth = depth;
    }

    /** 들여쓰기만큼 아이콘 왼쪽을 비운다 */
    int boxX() { return depth * INDENT_PX; }

    public int getIconWidth() { return boxX() + BOX + GAP; }
    public int getIconHeight() { return BOX + 2; }

    public void paintIcon(Component c, Graphics g, int x, int y) {
        x += boxX();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        Color fg = c != null ? c.getForeground() : Color.DARK_GRAY;
        // 메모 테마의 포인트 색 (JTextPane 의 client property "accent")
        Color accent = c instanceof JComponent jc && jc.getClientProperty("accent") instanceof Color a ? a : fg;
        double arc = MemoPlus.cute() ? 7 : 4;
        java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Double(x + 0.75, y + 1.75, BOX - 1.5, BOX - 1.5, arc, arc);
        if (!MemoPlus.cute()) {                                         // 심플: 회색 테두리 / 채운 회색 + 흰 체크
            if (done) {
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
                g2.fill(box);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.geom.Path2D.Double chk = new java.awt.geom.Path2D.Double();
                chk.moveTo(x + 3.8, y + 9.2);
                chk.lineTo(x + 6.6, y + 12);
                chk.lineTo(x + 11.4, y + 6);
                g2.draw(chk);
            } else {
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 170));
                g2.setStroke(new BasicStroke(1.4f));
                g2.draw(box);
            }
            g2.dispose();
            return;
        }
        if (done) {
            g2.setColor(accent);
            g2.fill(box);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D.Double chk = new java.awt.geom.Path2D.Double();
            chk.moveTo(x + 3.8, y + 9.2);
            chk.lineTo(x + 6.6, y + 12);
            chk.lineTo(x + 11.4, y + 6);
            g2.draw(chk);
        } else {
            g2.setColor(new Color(255, 255, 255, 150));
            g2.fill(box);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(box);
        }
        g2.dispose();
    }
}
