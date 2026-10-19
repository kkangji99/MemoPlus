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
 * 계절 스타일 장식. band: 위쪽 색 띠 아래 끝 H px 안에 그리는 줄 장식 (글을 가리지 않음),
 * pattern: 본문에 은은하게 흩뿌린 무늬 (메모마다 같은 자리).
 */
final class Deco {
    static final int H = 18;

    static void band(Graphics2D g, QuickMemo.Style s, QuickMemo.Theme t, int w, int bottom) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double y0 = bottom - H;
        switch (s) {
            case Christmas -> {                                  // 늘어진 줄에 매달린 오너먼트
                int step = 34;
                double sag = 6;
                java.awt.geom.Path2D.Double rope = new java.awt.geom.Path2D.Double();
                rope.moveTo(0, y0);
                for (int x = 0; x < w; x += step) rope.quadTo(x + step / 2.0, y0 + sag * 2, x + step, y0);
                g2.setColor(new Color(0x2E6B45));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(rope);
                Color[] cs = {QuickMemo.XMAS_RED, QuickMemo.XMAS_GOLD, QuickMemo.XMAS_GREEN};
                for (int i = 0, x = step / 2; x < w; i++, x += step) {
                    double cy = y0 + sag + 6, r = 5;
                    g2.setColor(new Color(0xB8B8B8));
                    g2.fill(new java.awt.geom.Rectangle2D.Double(x - 2, cy - r - 3, 4, 3));
                    g2.setColor(cs[i % 3]);
                    g2.fill(new java.awt.geom.Ellipse2D.Double(x - r, cy - r, 2 * r, 2 * r));
                    shine(g2, x, cy, r);
                }
            }
            case Spring -> {                                     // 벚꽃 가지
                java.awt.geom.Path2D.Double branch = new java.awt.geom.Path2D.Double();
                branch.moveTo(0, y0 + 7);
                for (int x = 0; x < w; x += 60) branch.curveTo(x + 20, y0 + 3, x + 40, y0 + 11, x + 60, y0 + 7);
                g2.setColor(new Color(0x8A6450));
                g2.draw(branch);
                Color[] cs = {new Color(0xF7A8C0), new Color(0xFBC7D6), new Color(0xF48FB1)};
                for (int i = 0, x = 16; x < w; i++, x += 30) {
                    double cy = y0 + 7 + (i % 2 == 0 ? 3 : -1);
                    blossom(g2, x, cy, i % 3 == 2 ? 5 : 6.5, cs[i % 3]);
                    if (i % 2 == 0) {
                        g2.setColor(new Color(0x7DB85A));
                        g2.fill(leafShape(x + 9, y0 + 7, 5, 2.4, -0.5));
                    }
                }
            }
            case Summer -> {                                     // 출렁이는 물결 + 거품
                Color sea = new Color(0x7CCBEF), deep = new Color(0x3FA9DE);
                for (int layer = 0; layer < 2; layer++) {
                    double base = y0 + (layer == 0 ? 6 : 11), amp = layer == 0 ? 3 : 2.5, len = layer == 0 ? 40 : 30, ph = layer == 0 ? 0 : 15;
                    java.awt.geom.Path2D.Double wave = new java.awt.geom.Path2D.Double();
                    wave.moveTo(0, bottom);
                    wave.lineTo(0, base);
                    for (double x = 0; x <= w + len; x += 2)
                        wave.lineTo(x, base + Math.sin((x + ph) / len * 2 * Math.PI) * amp);
                    wave.lineTo(w + len, bottom);
                    wave.closePath();
                    g2.setColor(layer == 0 ? new Color(sea.getRed(), sea.getGreen(), sea.getBlue(), 170) : deep);
                    g2.fill(wave);
                }
                g2.setColor(new Color(255, 255, 255, 220));
                for (int x = 10; x < w; x += 23) g2.fill(new java.awt.geom.Ellipse2D.Double(x, y0 + 2 + (x / 23 % 3), 3, 3));
            }
            case Autumn -> {                                     // 줄에 매단 단풍잎
                java.awt.geom.Path2D.Double rope = new java.awt.geom.Path2D.Double();
                int step = 36;
                rope.moveTo(0, y0 + 1);
                for (int x = 0; x < w; x += step) rope.quadTo(x + step / 2.0, y0 + 7, x + step, y0 + 1);
                g2.setColor(new Color(0x8A6450));
                g2.setStroke(new BasicStroke(1.3f));
                g2.draw(rope);
                Color[] cs = {new Color(0xE0582B), new Color(0xF2A33A), new Color(0xC7372F), new Color(0xE8C547)};
                for (int i = 0, x = step / 2; x < w; i++, x += step) {
                    g2.setColor(cs[i % 4]);
                    g2.fill(maple(x, y0 + 9, 9, (i % 2 == 0 ? .2 : -.2)));
                }
            }
            case Winter -> {                                     // 눈 쌓인 처마 + 고드름
                Random rnd = new Random(7);
                Color ice = new Color(0xBFE3F7);
                for (int x = 3; x < w; x += 9 + rnd.nextInt(5)) {
                    double len = 6 + rnd.nextInt(10);
                    java.awt.geom.Path2D.Double ic = new java.awt.geom.Path2D.Double();
                    ic.moveTo(x - 3, y0 + 4);
                    ic.quadTo(x - 1, y0 + 4 + len * .6, x, y0 + 4 + len);
                    ic.quadTo(x + 1, y0 + 4 + len * .6, x + 3, y0 + 4);
                    ic.closePath();
                    g2.setColor(ice);
                    g2.fill(ic);
                    g2.setColor(new Color(255, 255, 255, 200));
                    g2.draw(new java.awt.geom.Line2D.Double(x - 1, y0 + 6, x - .5, y0 + 4 + len * .5));
                }
                java.awt.geom.Path2D.Double snow = new java.awt.geom.Path2D.Double();
                snow.moveTo(0, y0 - 2);
                snow.lineTo(w, y0 - 2);
                snow.lineTo(w, y0 + 4);
                for (double x = w; x >= 0; x -= 14) snow.quadTo(x - 7, y0 + 8, x - 14, y0 + 4);
                snow.closePath();
                g2.setColor(Color.WHITE);
                g2.fill(snow);
                g2.setColor(new Color(0, 0, 0, 18));
                g2.draw(snow);
            }
            default -> { }
        }
        g2.dispose();
    }

    static void pattern(Graphics2D g, QuickMemo.Style s, QuickMemo.Theme t, long seed, int w, int top, int h) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Random rnd = new Random(seed);
        int n = Math.max(6, w * Math.max(0, h - top) / 9000);
        Color c = t.dark ? new Color(255, 255, 255, 50) : new Color(t.accent.getRed(), t.accent.getGreen(), t.accent.getBlue(), 40);
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < n; i++) {
            double x = rnd.nextDouble() * w, y = top + 8 + rnd.nextDouble() * Math.max(1, h - top - 16), r = 4 + rnd.nextDouble() * 5, rot = rnd.nextDouble() * Math.PI;
            switch (s) {
                case Christmas, Winter -> {                      // 눈송이
                    for (int k = 0; k < 3; k++) {
                        double a = Math.PI / 3 * k + Math.PI / 6;
                        g2.draw(new java.awt.geom.Line2D.Double(x - Math.cos(a) * r, y - Math.sin(a) * r, x + Math.cos(a) * r, y + Math.sin(a) * r));
                    }
                }
                case Spring -> g2.fill(petal(x, y, r * .9, rot));    // 흩날리는 꽃잎
                case Summer -> g2.draw(new java.awt.geom.Ellipse2D.Double(x - r * .6, y - r * .6, r * 1.2, r * 1.2));   // 물방울
                case Autumn -> g2.fill(leafShape(x, y, r, r * .45, rot));    // 낙엽
                default -> { }
            }
        }
        g2.dispose();
    }

    static void shine(Graphics2D g2, double cx, double cy, double r) {
        g2.setColor(new Color(255, 255, 255, 170));
        g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r * .5, cy - r * .55, r * .6, r * .5));
    }

    /** 다섯 꽃잎 꽃 */
    static void blossom(Graphics2D g2, double cx, double cy, double r, Color c) {
        g2.setColor(c);
        for (int k = 0; k < 5; k++) {
            double a = -Math.PI / 2 + k * 2 * Math.PI / 5;
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx + Math.cos(a) * r * .55 - r * .45, cy + Math.sin(a) * r * .55 - r * .45, r * .9, r * .9));
        }
        g2.setColor(new Color(0xF6D365));
        g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r * .25, cy - r * .25, r * .5, r * .5));
    }

    /** 끝이 살짝 갈라진 벚꽃잎 */
    static Shape petal(double cx, double cy, double r, double rot) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(0, r);
        p.curveTo(-r * .9, r * .3, -r * .6, -r * .8, -r * .15, -r * .8);
        p.lineTo(0, -r * .55);
        p.lineTo(r * .15, -r * .8);
        p.curveTo(r * .6, -r * .8, r * .9, r * .3, 0, r);
        p.closePath();
        java.awt.geom.AffineTransform at = java.awt.geom.AffineTransform.getTranslateInstance(cx, cy);
        at.rotate(rot);
        return at.createTransformedShape(p);
    }

    /** 길쭉한 잎 */
    static Shape leafShape(double cx, double cy, double len, double wid, double rot) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(-len, 0);
        p.quadTo(0, -wid * 2, len, 0);
        p.quadTo(0, wid * 2, -len, 0);
        p.closePath();
        java.awt.geom.AffineTransform at = java.awt.geom.AffineTransform.getTranslateInstance(cx, cy);
        at.rotate(rot);
        return at.createTransformedShape(p);
    }

    /** 단풍잎: 다섯 갈래 잎(갈래마다 옆 톱니) + 아래 꼭지 */
    static Shape maple(double cx, double cy, double r, double rot) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(0, r * .25);
        double[] angles = {150, 210, 270, 330, 30};               // 화면 좌표(아래가 +y): 왼아래, 왼위, 위, 오위, 오아래
        double[] lens = {.55, .85, 1, .85, .55};
        for (int k = 0; k < 5; k++) {
            double a = Math.toRadians(angles[k]), l = r * lens[k];
            double between = Math.toRadians(angles[k] - 30), nb = r * .42;
            p.lineTo(Math.cos(between) * nb, Math.sin(between) * nb);                       // 갈래 사이 오목한 곳
            p.lineTo(Math.cos(a - .42) * l * .72, Math.sin(a - .42) * l * .72);           // 옆 톱니
            p.lineTo(Math.cos(a - .16) * l * .78, Math.sin(a - .16) * l * .78);
            p.lineTo(Math.cos(a) * l, Math.sin(a) * l);                                  // 갈래 끝
            p.lineTo(Math.cos(a + .16) * l * .78, Math.sin(a + .16) * l * .78);
            p.lineTo(Math.cos(a + .42) * l * .72, Math.sin(a + .42) * l * .72);
        }
        double end = Math.toRadians(60);
        p.lineTo(Math.cos(end) * r * .42, Math.sin(end) * r * .42);
        p.closePath();
        java.awt.geom.Area a = new java.awt.geom.Area(p);
        a.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(-.6, r * .1, 1.2, r * .85)));
        java.awt.geom.AffineTransform at = java.awt.geom.AffineTransform.getTranslateInstance(cx, cy);
        at.rotate(rot);
        return at.createTransformedShape(a);
    }
}
