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
 * Microsoft Sticky Notes 를 분석(REA)해 기능을 재구성한 메모 프로그램 QuickMemo.
 * 실행: java QuickMemo.java  (JDK 21+)
 */
public class QuickMemo {

    /** 릴리스할 때마다 올린다. GitHub 릴리스 태그는 v + 이 값 */
    static final String VERSION = "1.0.17";
    /** 자동 업데이트를 확인할 GitHub 저장소 (owner/name) */
    static final String UPDATE_REPO = "kkangji99/QuickMemo";

    public static void main(String[] args) throws Exception {
        boolean dark = false;                          // 글꼴 안티앨리어싱은 Windows(ClearType) 설정을 따른다: 회색조로 강제하면 맑은 고딕 획이 빠짐
        try (var r = Files.newBufferedReader(Store.dataDir().resolve("settings.properties"), StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            dark = Boolean.parseBoolean(p.getProperty("darkMode", "false"));
            if (Boolean.parseBoolean(p.getProperty("followSystemTheme", "false"))) dark = App.systemDark();
        } catch (IOException ignored) { }
        boolean d = dark;
        SwingUtilities.invokeAndWait(() -> setupLaf(d));
        if (Arrays.asList(args).contains("--uninstall")) {          // 앱 및 기능 → 제거
            Installer.uninstall();
            System.exit(0);
        }
        if (Updater.updateIfAvailable()) return;      // 새 버전을 받아 재시작하는 경우
        SwingUtilities.invokeLater(() -> new App().start());
    }

    /**
     * 화면 테마: FlatLaf (Windows 11 느낌의 현대적인 Swing 테마). 설정 창·확인 창·버튼·스크롤바·창 제목 표시줄에 적용.
     * FlatLaf 를 못 쓰면 Windows 기본 테마로.
     */
    /** 지금 어둡게 모드인지 (setupLaf 가 기록. 알림창 색에 씀) */
    static volatile boolean DARK;

    static void setupLaf(boolean dark) {
        DARK = dark;
        UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource("Malgun Gothic", Font.PLAIN, 13));
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("PopupMenu.borderCornerRadius", 8);
        UIManager.put("MenuItem.selectionArc", 6);
        try {
            if (dark) com.formdev.flatlaf.FlatDarkLaf.setup(); else com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Throwable e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        }
    }

    /** 창 제목줄을 창 배경과 같은 색으로, 아이콘·제목 글자는 숨긴다 (FlatLaf 창 꾸미기일 때) */
    static void blendTitleBar(JRootPane rp, Color bg, Color fg) {
        rp.putClientProperty("JRootPane.titleBarShowIcon", false);
        rp.putClientProperty("JRootPane.titleBarShowTitle", false);
        rp.putClientProperty("JRootPane.titleBarBackground", bg);
        rp.putClientProperty("JRootPane.titleBarForeground", fg);
        // 대화상자는 제목 숨김 설정이 먹지 않아 제목을 비운다 (창 안에 제목이 따로 있음)
        if (SwingUtilities.getWindowAncestor(rp) instanceof Dialog d) d.setTitle("");
        else if (rp.getParent() instanceof Dialog d) d.setTitle("");
    }

    /** 둥근 알약 버튼, 파란 포커스 테두리 없이 */
    static JButton pill(JButton b) {
        b.putClientProperty("JButton.buttonType", "roundRect");
        b.putClientProperty("FlatLaf.style", "focusWidth: 0; innerFocusWidth: 0; focusedBackground: $Button.background; focusedBorderColor: $Button.borderColor");
        return b;
    }

    /** 실행 중에 밝게/어둡게를 바꾸면 모든 창의 테마를 다시 적용 */
    static void switchLaf(boolean dark) {
        setupLaf(dark);
        try { com.formdev.flatlaf.FlatLaf.updateUI(); } catch (Throwable e) { for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w); }
    }

    // ===================================================================== 설치 · 제거

    // ===================================================================== 자동 업데이트

    // ===================================================================== 테마
    // ===================================================================== 디자인 스타일
    /**
     * 화면 스타일: 심플(기본, 원래 스티커 메모 느낌) / 아기자기(파스텔·둥근 모양·마스킹테이프) /
     * 크리스마스·봄·여름·가을·겨울(아기자기 + 계절 색, 위쪽 띠 장식, 본문 무늬)
     */
    enum Style {
        //         이름,        설명,             포인트,   목록 배경(밝게/어둡게), 아이콘
        Simple("심플", "깔끔한 기본", 0x0067C0, 0xF3F3F3, 0x202020, null),
        Cute("아기자기", "파스텔 · 테이프", 0xFF8FB1, 0xFFF9F0, 0x2A2633, "heart"),
        Christmas("크리스마스", "트리 · 눈송이", 0xD63A3A, 0xFBF6EE, 0x14261D, "tree"),
        Spring("봄", "벚꽃 · 꽃잎", 0xE87A9A, 0xFFF8FA, 0x2B2430, "blossom"),
        Summer("여름", "바다 · 물방울", 0x1E9BD7, 0xF3FAFF, 0x152231, "sun"),
        Autumn("가을", "단풍 · 낙엽", 0xD9572B, 0xFCF6EE, 0x2A211B, "leaf"),
        Winter("겨울", "고드름 · 눈", 0x4C8FC9, 0xF5F8FC, 0x171C27, "snowflake");

        final String label, desc, icon;
        final Color accent, listLight, listDark;

        Style(String label, String desc, int accent, int listLight, int listDark, String icon) {
            this.label = label;
            this.desc = desc;
            this.accent = new Color(accent);
            this.listLight = new Color(listLight);
            this.listDark = new Color(listDark);
            this.icon = icon;
        }

        /** 위쪽 띠 아래에 장식(가랜드·꽃·물결·단풍·고드름)이 붙는 계절 스타일 */
        boolean decorated() { return ordinal() >= Christmas.ordinal(); }

        static Style parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return Simple; }
        }

        /** 계절 추천: 3~5월 봄, 6~8월 여름, 9~11월 가을, 12~2월 겨울 */
        static Style forSeason(LocalDate d) {
            int m = d.getMonthValue();
            return m >= 3 && m <= 5 ? Spring : m >= 6 && m <= 8 ? Summer : m >= 9 && m <= 11 ? Autumn : Winter;
        }
    }

    static volatile Style STYLE = Style.Simple;

    /** 둥근 모양·통통한 선 등 아기자기 계열 (계절 스타일 포함) */
    static boolean cute() { return STYLE != Style.Simple; }

    static boolean xmas() { return STYLE == Style.Christmas; }

    /** 위쪽 띠 장식이 있는 스타일 */
    static boolean decorated() { return STYLE.decorated(); }

    static final Color XMAS_RED = new Color(0xD63A3A), XMAS_GREEN = new Color(0x2E8B57), XMAS_GOLD = new Color(0xE8B83A);

    /** 스타일 포인트 색 (아기자기 분홍, 크리스마스 빨강, 계절마다 다름) */
    static Color styleAccent() { return STYLE == Style.Simple ? PINK : STYLE.accent; }

    /** a 와 b 를 t(0~1) 비율로 섞은 색 */
    static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** 포인트 색을 투명하게 */
    static Color styleAccent(int alpha) {
        Color c = styleAccent();
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** 스타일을 바꾸면 모든 테마의 색이 그 스타일 팔레트로 바뀐다 */
    static void applyStyle(Style s) {
        STYLE = s;
        for (Theme t : Theme.values()) t.useStyle(s);
    }

    /** 테마 색. 이름(Yellow 등)은 저장 파일·원본 스티커 메모와 호환되도록 유지 */
    enum Theme {
        //      심플 이름, 본문,     띠,       | 아기자기 이름, 본문,  띠,       포인트
        Yellow("노랑", 0xFFF7D1, 0xFFF2AB, "레몬", 0xFFF6CC, 0xFFE58F, 0xF2B300, false),
        Green("초록", 0xE4F9E0, 0xCBF1C4, "민트", 0xE3F7EC, 0xBDEBD3, 0x35B985, false),
        Pink("분홍", 0xFFE4F1, 0xFFCCE5, "딸기우유", 0xFFE7EF, 0xFFC7DA, 0xFF7FA8, false),
        Purple("보라", 0xF2E6FF, 0xE7CFFF, "라벤더", 0xF0E8FF, 0xDACBFF, 0x9C7BEA, false),
        Blue("파랑", 0xE2F1FF, 0xCDE9FF, "하늘", 0xE3F2FF, 0xC2E3FF, 0x4EA6EE, false),
        Gray("회색", 0xF3F2F1, 0xE1DFDD, "구름", 0xF5F2EF, 0xE5DED7, 0xA89584, false),
        Charcoal("차콜", 0x5A5A5A, 0x454545, "밤하늘", 0x4A445C, 0x3A344B, 0xFFB0CC, true);

        final String simpleLabel, cuteLabel;
        final int simpleBody, simpleHeader, cuteBody, cuteHeader, cuteAccent;
        final boolean dark;
        /** 현재 스타일의 값 (applyStyle 이 바꿈) */
        String label;
        Color body, header, accent;

        Theme(String simpleLabel, int simpleBody, int simpleHeader,
              String cuteLabel, int cuteBody, int cuteHeader, int cuteAccent, boolean dark) {
            this.simpleLabel = simpleLabel;
            this.simpleBody = simpleBody;
            this.simpleHeader = simpleHeader;
            this.cuteLabel = cuteLabel;
            this.cuteBody = cuteBody;
            this.cuteHeader = cuteHeader;
            this.cuteAccent = cuteAccent;
            this.dark = dark;
            useStyle(Style.Simple);
        }

        /** 크리스마스 팔레트: 이름, 본문, 띠, 포인트 (Theme 순서와 같음) */
        static final Object[][] XMAS = {
            {"황금 종", 0xFFF6DD, 0xF7D98B, 0xC99A1E},
            {"트리", 0xE7F4EA, 0xA9D8B6, 0x2E8B57},
            {"산타", 0xFFEDED, 0xF5AFAF, 0xD63A3A},
            {"겨울밤", 0xEFEAFA, 0xD2C5F0, 0x7C5CC4},
            {"눈송이", 0xEBF6FD, 0xBFE2F6, 0x3C8DC9},
            {"눈사람", 0xFAFAFA, 0xE3E7EA, 0x8A9399},
            {"고요한 밤", 0x1F3A2E, 0x17301F, 0xF0B43C},
        };

        static final Object[][] SPRING = {
            {"개나리", 0xFFF8DC, 0xFBE7A1, 0xE0B400},
            {"새싹", 0xEEF8E4, 0xCDEBB4, 0x6BAF3C},
            {"벚꽃", 0xFFF0F4, 0xFBCFDC, 0xE87A9A},
            {"라일락", 0xF5EEFC, 0xE2D1F5, 0xA27BD6},
            {"봄하늘", 0xEEF6FD, 0xCFE6FA, 0x5B9FE0},
            {"봄비", 0xF6F6F4, 0xE4E6E1, 0x9AA39A},
            {"봄밤", 0x3B3346, 0x2E2838, 0xF5A3BF},
        };
        static final Object[][] SUMMER = {
            {"레모네이드", 0xFFFBD6, 0xFFEE8A, 0xE0B000},
            {"수박", 0xEAFBEF, 0xB5EDC4, 0x2FB45A},
            {"복숭아", 0xFFF0EA, 0xFFD0BE, 0xFF8A65},
            {"포도", 0xF3EDFF, 0xD9CCFF, 0x7E5BEF},
            {"바다", 0xE6F7FF, 0xA9E0FA, 0x1E9BD7},
            {"모래", 0xFBF6EC, 0xEEDFC4, 0xC79A55},
            {"여름밤", 0x1F2E44, 0x172438, 0x7FD3FF},
        };
        static final Object[][] AUTUMN = {
            {"은행", 0xFFF6DA, 0xF9DE8E, 0xD9A21B},
            {"올리브", 0xF2F3E2, 0xDADDB3, 0x8A8F3A},
            {"단풍", 0xFFEDE6, 0xF8C3AE, 0xD9572B},
            {"포도주", 0xF6ECEF, 0xE3C9D2, 0x9C3D5A},
            {"가을하늘", 0xEAF3FA, 0xC8DFF0, 0x4A86B8},
            {"갈대", 0xF6F2EA, 0xE5DCCB, 0xA28B66},
            {"가을밤", 0x3A2E26, 0x2C221C, 0xF0A04B},
        };
        static final Object[][] WINTER = {
            {"군고구마", 0xFFF6E6, 0xF5DDB5, 0xC98A3A},
            {"상록수", 0xECF4F0, 0xC6DED3, 0x3D7D63},
            {"털장갑", 0xFFF0F2, 0xF6CFD6, 0xD46A82},
            {"새벽", 0xF1EFFA, 0xD8D4F0, 0x6E64C2},
            {"눈꽃", 0xEEF6FC, 0xCFE5F6, 0x4C8FC9},
            {"함박눈", 0xF8F9FB, 0xE4E8EE, 0x8C97A6},
            {"겨울밤", 0x1E2433, 0x161B28, 0x9CC8FF},
        };

        static Object[][] palette(Style s) {
            return switch (s) {
                case Christmas -> XMAS;
                case Spring -> SPRING;
                case Summer -> SUMMER;
                case Autumn -> AUTUMN;
                case Winter -> WINTER;
                default -> null;
            };
        }

        void useStyle(Style s) {
            if (palette(s) != null) {
                Object[] x = palette(s)[ordinal()];
                label = (String) x[0];
                body = new Color((Integer) x[1]);
                header = new Color((Integer) x[2]);
                accent = new Color((Integer) x[3]);
                return;
            }
            boolean c = s == Style.Cute;
            label = c ? cuteLabel : simpleLabel;
            body = new Color(c ? cuteBody : simpleBody);
            header = new Color(c ? cuteHeader : simpleHeader);
            accent = c ? new Color(cuteAccent) : dark ? new Color(0xD6D6D6) : new Color(0x6E6E6E);
        }

        /** 글자색: 심플은 검정, 아기자기는 부드러운 갈색 */
        Color fg() { return dark ? (cute() ? new Color(0xFFF4F8) : new Color(0xF5F5F5)) : (cute() ? new Color(0x5A4636) : new Color(0x1F1F1F)); }
        Color hover() { return dark ? new Color(255, 255, 255, 38) : cute() ? new Color(90, 70, 54, 26) : new Color(0, 0, 0, 26); }
        Color menuBg() { return dark ? (cute() ? new Color(0x2F2A3B) : new Color(0x2B2B2B)) : (cute() ? new Color(0xFFFDF8) : Color.WHITE); }
        Color menuFg() { return fg(); }
        Color menuHover() { return dark ? new Color(255, 255, 255, 28) : cute() ? styleAccent(STYLE == Style.Cute ? 50 : 38) : new Color(0, 0, 0, 18); }

        static Theme parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return Yellow; }
        }
    }

    static final Font BASE_FONT = new Font("Malgun Gothic", Font.PLAIN, 14);
    static final Color PINK = new Color(0xFF8FB1), CREAM = new Color(0xFFF9F0);

    // ===================================================================== 스티커 모양
    /** 메모 창 모양. 기본은 사각. 각 모양은 외곽선과, 글이 들어갈 안쪽 여백(비율)을 가진다 */
    enum NoteShape {
        Square("사각", .00, .00, .00, .00, 220, 170),
        Round("동그라미", .13, .13, .13, .05, 340, 340),
        Heart("하트", .10, .10, .14, .12, 360, 340),
        Star("별", .21, .21, .32, .15, 440, 420),
        Cloud("구름", .08, .08, .25, .08, 340, 300),
        Bubble("말풍선", .00, .00, .00, .12, 240, 200);

        final String label;
        final double left, right, top, bottom;          // 글 영역 여백 (모양 크기 대비 비율)
        final int minW, minH;

        NoteShape(String label, double left, double right, double top, double bottom, int minW, int minH) {
            this.label = label;
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.minW = minW;
            this.minH = minH;
        }

        static NoteShape parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return Square; }
        }

        /** (x, y) 에서 w×h 크기의 외곽선 */
        Shape outline(double x, double y, double w, double h) {
            java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
            switch (this) {
                case Square -> {
                    double arc = Math.min(cute() ? 22 : 8, Math.min(w, h) * .25);   // 작은 칩에서도 사각으로 보이게
                    return new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, arc, arc);
                }
                case Round -> {                                      // 살짝 네모난 동그라미 (슈퍼타원): 글 쓸 자리가 넓다
                    for (int k = 0; k < 96; k++) {
                        double a = 2 * Math.PI * k / 96, c = Math.cos(a), s = Math.sin(a);
                        double px = .5 + .5 * Math.signum(c) * Math.pow(Math.abs(c), 2 / 2.3);
                        double py = .5 + .5 * Math.signum(s) * Math.pow(Math.abs(s), 2 / 2.3);
                        if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
                    }
                    p.closePath();
                }
                case Heart -> {                                      // 아래가 통통한 하트
                    p.moveTo(.50, 1.0);
                    p.curveTo(.40, .95, .00, .80, .00, .40);
                    p.curveTo(.00, .14, .14, .00, .30, .00);
                    p.curveTo(.41, .00, .48, .06, .50, .14);
                    p.curveTo(.52, .06, .59, .00, .70, .00);
                    p.curveTo(.86, .00, 1.0, .14, 1.0, .40);
                    p.curveTo(1.0, .80, .60, .95, .50, 1.0);
                    p.closePath();
                }
                case Star -> {                                       // 통통하고 끝이 둥근 별
                    double cx = .5, cy = .54, ro = .5, ri = .5 * .66;
                    double[][] pts = new double[10][];
                    for (int k = 0; k < 10; k++) {
                        double r = k % 2 == 0 ? ro : ri, a = -Math.PI / 2 + k * Math.PI / 5;
                        pts[k] = new double[]{cx + Math.cos(a) * r, cy + Math.sin(a) * r};
                    }
                    for (int k = 0; k < 10; k++) {                   // 꼭짓점을 곡선으로 둥글게
                        double[] prev = pts[(k + 9) % 10], cur = pts[k], next = pts[(k + 1) % 10];
                        double t = k % 2 == 0 ? .18 : .10;
                        double ax = cur[0] + (prev[0] - cur[0]) * t, ay = cur[1] + (prev[1] - cur[1]) * t;
                        double bx = cur[0] + (next[0] - cur[0]) * t, by = cur[1] + (next[1] - cur[1]) * t;
                        if (k == 0) p.moveTo(ax, ay); else p.lineTo(ax, ay);
                        p.quadTo(cur[0], cur[1], bx, by);
                    }
                    p.closePath();
                }
                case Cloud -> {
                    java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(.02, .34, .96, .62, .34, .34));
                    a.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(.06, .18, .38, .40)));
                    a.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(.28, .00, .46, .50)));
                    a.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(.58, .14, .38, .40)));
                    p.append(a, false);
                }
                case Bubble -> {
                    java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(0, 0, 1, .88, .16, .16));
                    java.awt.geom.Path2D.Double tail = new java.awt.geom.Path2D.Double();
                    tail.moveTo(.18, .84);
                    tail.quadTo(.16, .95, .08, 1.0);
                    tail.quadTo(.24, .98, .34, .86);
                    tail.closePath();
                    a.add(new java.awt.geom.Area(tail));
                    p.append(a, false);
                }
            }
            return java.awt.geom.AffineTransform.getTranslateInstance(x, y)
                    .createTransformedShape(java.awt.geom.AffineTransform.getScaleInstance(w, h).createTransformedShape(p));
        }
    }

    // ===================================================================== 시간 (.NET Ticks, 원본과 동일)
    static final long TICKS_EPOCH = 621355968000000000L;

    static long nowTicks() { return System.currentTimeMillis() * 10000L + TICKS_EPOCH; }

    static long ticksToMillis(long t) { return (t - TICKS_EPOCH) / 10000L; }

    // ===================================================================== 모델
    static final class Note {
        String id = UUID.randomUUID().toString();
        Theme theme = Theme.Yellow;
        NoteShape shape = NoteShape.Square;
        String text = "";              // 문단 ID + 서식 토큰 마크업
        boolean open;
        boolean onTop;
        Rectangle bounds;
        long createdAt = nowTicks();
        long updatedAt = createdAt;
        Long deletedAt;
        String importedFrom;           // Windows 스티커 메모에서 가져온 경우 원본 Note.Id (중복 가져오기 방지)
        boolean favorite;              // 즐겨찾기: 메모 목록 위쪽에 고정
        Long order;                    // 메모 목록 순서 (작을수록 위). null 이면 최근 수정 순
        Long remindAt;                 // 알림 시각 (epoch 밀리초). 알린 뒤에는 null

        boolean deleted() { return deletedAt != null; }

        /** 잠금: 본문을 암호화해서 저장하고, 목록·창에서 내용을 가린다 */
        boolean lock;
        /** 잠긴 메모를 풀지 못했을 때(다른 PC·계정) 받은 암호문을 그대로 보관 */
        String cipher;

        /** 잠긴(암호화된) 메모인지 */
        boolean locked() { return lock; }

        /** 잠겼는데 이 PC·계정에서 풀 수 없는 메모 */
        boolean unreadable() { return lock && cipher != null; }
    }

    // ===================================================================== 본문 마크업

    // ===================================================================== Windows 스티커 메모 가져오기

    // ===================================================================== 저장소
    static final class Store {
        static final Pattern POS = Pattern.compile("Position=(-?\\d+),(-?\\d+);Size=(\\d+),(\\d+)");

        final Path dir;
        final Path notesDir;
        final Path mediaDir;
        final Path settingsFile;
        final Map<String, java.awt.image.BufferedImage> imageCache = new HashMap<>();

        /** 원본 Media 테이블(LocalFileRelativePath)처럼 이미지를 media 폴더에 파일로 둔다 */
        String saveImage(java.awt.image.BufferedImage img) throws IOException {
            String id = UUID.randomUUID().toString();
            javax.imageio.ImageIO.write(img, "png", mediaDir.resolve(id + ".png").toFile());
            imageCache.put(id, img);
            return id;
        }

        /**
         * 미디어 ID 는 우리가 만든 UUID 형식만. 메모 글에 "..\" 나 "\\서버\공유" 같은 경로가 들어 있어도
         * media 폴더 밖 파일이나 네트워크 경로를 열지 않게 (네트워크 경로를 열면 Windows 가 로그인 정보를 보낼 수 있음)
         */
        static boolean validMediaId(String id) {
            return id != null && id.matches("[0-9a-fA-F][0-9a-fA-F\\-]{7,63}");
        }

        java.awt.image.BufferedImage loadImage(String id) {
            if (!validMediaId(id)) return null;
            return imageCache.computeIfAbsent(id, k -> {
                Path p = mediaDir.resolve(k + ".png").normalize();
                return p.startsWith(mediaDir) && Files.exists(p) ? readImageSafely(p.toFile()) : null;
            });
        }

        static final long MAX_PIXELS = 40_000_000L;                  // 약 6300×6300

        /** 그림 크기를 먼저 확인하고 너무 크면 읽지 않는다 (작은 파일이 엄청난 크기로 풀리는 그림 방지) */
        static java.awt.image.BufferedImage readImageSafely(java.io.File f) {
            try (var in = javax.imageio.ImageIO.createImageInputStream(f)) {
                if (in == null) return null;
                var readers = javax.imageio.ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return null;
                javax.imageio.ImageReader r = readers.next();
                try {
                    r.setInput(in, true, true);
                    if ((long) r.getWidth(0) * r.getHeight(0) > MAX_PIXELS) return null;
                    return r.read(0);
                } finally {
                    r.dispose();
                }
            } catch (Exception | OutOfMemoryError e) {
                return null;
            }
        }
        final Properties settings = new Properties();

        Store() throws IOException {
            dir = dataDir();
            Path legacy = dir.resolveSibling("StickyNotesJ");   // 이전 이름의 저장 폴더 이전
            if (!Files.exists(dir) && Files.isDirectory(legacy)) Files.move(legacy, dir);
            notesDir = dir.resolve("notes");
            mediaDir = dir.resolve("media");
            settingsFile = dir.resolve("settings.properties");
            Files.createDirectories(notesDir);
            Files.createDirectories(mediaDir);
            if (Files.exists(settingsFile)) {
                try (var r = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) { settings.load(r); }
            }
        }

        static Path dataDir() {
            String base = System.getenv("APPDATA");
            return Paths.get(base != null ? base : System.getProperty("user.home"), "QuickMemo");
        }

        boolean darkMode() { return Boolean.parseBoolean(settings.getProperty("darkMode", "false")); }
        boolean confirmDelete() { return Boolean.parseBoolean(settings.getProperty("confirmDelete", "true")); }
        /** 계절에 맞춰 디자인 자동 변경 (기본 꺼짐) */
        boolean autoSeason() { return Boolean.parseBoolean(settings.getProperty("autoSeason", "false")); }
        /** 메모 글자 크기 (기본 14) */
        int fontSize() {
            try { return Math.max(11, Math.min(24, Integer.parseInt(settings.getProperty("fontSize", "14")))); } catch (NumberFormatException e) { return 14; }
        }
        /** 밝게/어둡게를 Windows 설정에 맞춤 */
        boolean followSystemTheme() { return Boolean.parseBoolean(settings.getProperty("followSystemTheme", "false")); }
        Theme defaultTheme() { return Theme.parse(settings.getProperty("defaultNoteColor", "Yellow")); }

        void set(String k, String v) {
            settings.setProperty(k, v);
            try (var w = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
                settings.store(w, "QuickMemo settings");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        List<Note> loadAll() {
            List<Note> list = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(notesDir, "*.note")) {
                for (Path p : ds) {
                    try { list.add(read(p)); } catch (Exception e) { System.err.println("읽기 실패: " + p + " " + e); }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return list;
        }

        Note read(Path p) throws IOException {
            String all = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int split = all.indexOf("\n\n");
            String head = split < 0 ? all : all.substring(0, split);
            Note n = new Note();
            n.text = split < 0 ? "" : all.substring(split + 2);
            for (String line : head.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                switch (k) {
                    case "Id" -> n.id = v;
                    case "Theme" -> n.theme = Theme.parse(v);
                    case "IsOpen" -> n.open = "1".equals(v);
                    case "IsAlwaysOnTop" -> n.onTop = "1".equals(v);
                    case "CreatedAt" -> n.createdAt = Long.parseLong(v);
                    case "UpdatedAt" -> n.updatedAt = Long.parseLong(v);
                    case "DeletedAt" -> n.deletedAt = v.isEmpty() ? null : Long.parseLong(v);
                    case "ImportedFrom" -> n.importedFrom = v.isEmpty() ? null : v;
                    case "Favorite" -> n.favorite = "1".equals(v);
                    case "Shape" -> n.shape = NoteShape.parse(v);
                    case "Order" -> n.order = v.isEmpty() ? null : Long.parseLong(v);
                    case "WindowPosition" -> {
                        Matcher m = POS.matcher(v);
                        if (m.find()) n.bounds = new Rectangle(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                    }
                    case "Locked" -> n.lock = "1".equals(v);
                    case "RemindAt" -> { try { n.remindAt = v.isEmpty() ? null : Long.parseLong(v); } catch (NumberFormatException e) { n.remindAt = null; } }
                    default -> { }
                }
            }
            // 파일 안의 Id 로 저장 경로를 만들므로 형식을 검사 (조작된 파일이 메모 폴더 밖에 쓰지 못하게)
            String stem = p.getFileName().toString().replaceFirst("\\.note$", "");
            if (!validNoteId(n.id)) n.id = validNoteId(stem) ? stem : UUID.randomUUID().toString();
            if (n.lock) {                                            // 잠긴 메모: 본문 복호화 (실패하면 원문을 그대로 보관)
                n.cipher = n.text;
                String plain = Crypto.decrypt(dir, n.text);
                if (plain != null) { n.text = plain; n.cipher = null; }
                else n.text = "";
            }
            return n;
        }

        static boolean validNoteId(String id) {
            return id != null && id.matches("[0-9A-Za-z][0-9A-Za-z\\-]{0,63}");
        }

        void save(Note n) {
            StringBuilder sb = new StringBuilder();
            sb.append("Id=").append(n.id).append('\n');
            sb.append("Theme=").append(n.theme.name()).append('\n');
            sb.append("IsOpen=").append(n.open ? 1 : 0).append('\n');
            sb.append("IsAlwaysOnTop=").append(n.onTop ? 1 : 0).append('\n');
            sb.append("WindowPosition=ManagedPosition=");
            if (n.bounds != null) {
                sb.append("Position=").append(n.bounds.x).append(',').append(n.bounds.y)
                  .append(";Size=").append(n.bounds.width).append(',').append(n.bounds.height);
            }
            sb.append('\n');
            sb.append("CreatedAt=").append(n.createdAt).append('\n');
            sb.append("UpdatedAt=").append(n.updatedAt).append('\n');
            sb.append("DeletedAt=").append(n.deletedAt == null ? "" : n.deletedAt).append('\n');
            if (n.importedFrom != null) sb.append("ImportedFrom=").append(n.importedFrom).append('\n');
            if (n.favorite) sb.append("Favorite=1\n");
            if (n.shape != NoteShape.Square) sb.append("Shape=").append(n.shape.name()).append('\n');
            if (n.order != null) sb.append("Order=").append(n.order).append('\n');
            if (n.remindAt != null) sb.append("RemindAt=").append(n.remindAt).append('\n');
            String body = n.text;
            if (n.lock) {
                sb.append("Locked=1\n");
                // 풀지 못한 잠긴 메모는 받은 암호문 그대로, 아니면 지금 내용을 암호화. 암호화에 실패하면 저장하지 않음 (평문 유출 방지)
                body = n.cipher != null ? n.cipher : Crypto.encrypt(dir, n.text);
                if (body == null) { System.err.println("암호화 실패, 저장 안 함: " + n.id); return; }
            }
            sb.append('\n').append(body);

            if (!validNoteId(n.id)) return;
            Path target = notesDir.resolve(n.id + ".note");
            Path tmp = notesDir.resolve(n.id + ".tmp");
            try {
                Files.writeString(tmp, sb, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /** 메모 파일을 지운다 (아무것도 쓰지 않은 메모를 남기지 않을 때) */
        void remove(Note n) {
            try { Files.deleteIfExists(notesDir.resolve(n.id + ".note")); } catch (IOException ignored) { }
        }

        /** 그림 파일 지우기 (ID 형식이 맞을 때만, media 폴더 안에서만) */
        void removeMedia(String id) {
            if (!validMediaId(id)) return;
            imageCache.remove(id);
            try { Files.deleteIfExists(mediaDir.resolve(id + ".png")); } catch (IOException ignored) { }
        }
    }

    /** 글도 그림도 없는 메모 */
    static boolean isEmptyNote(Note n) {
        if (n.locked()) return false;                                 // 잠긴 메모는(풀지 못해 비어 보여도) 절대 빈 메모로 치지 않음
        return Markup.plain(n.text).isBlank() && Markup.mediaIds(n.text).isEmpty();
    }

    // ===================================================================== 공용 UI
    static class FlatButton extends JButton {
        Color hoverColor = new Color(0, 0, 0, 28);
        boolean active;
        boolean grabbed;                                       // 끌어서 순서를 바꾸는 중

        FlatButton(String text, String tip) {
            super(text);
            setToolTipText(tip);
            setFocusable(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setMargin(new Insets(2, 8, 2, 8));
            setFont(BASE_FONT.deriveFont(15f));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (grabbed) {                                         // 잡힌 버튼: 진한 바탕 + 테두리 + 그림자로 들어 올린 느낌
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color f = getForeground();
                int arc = Math.min(getHeight() - 2, cute() ? 16 : 8);
                g2.setColor(new Color(0, 0, 0, 30));
                g2.fillRoundRect(2, 3, getWidth() - 3, getHeight() - 3, arc, arc);
                g2.setColor(new Color(f.getRed(), f.getGreen(), f.getBlue(), 45));
                g2.fillRoundRect(1, 0, getWidth() - 3, getHeight() - 3, arc, arc);
                g2.setColor(new Color(f.getRed(), f.getGreen(), f.getBlue(), 160));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(1, 0, getWidth() - 3, getHeight() - 3, arc, arc);
                g2.dispose();
            } else if (active || getModel().isRollover()) {        // 몽글몽글한 알약 모양 강조
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(active ? new Color(hoverColor.getRed(), hoverColor.getGreen(), hoverColor.getBlue(),
                        Math.min(255, hoverColor.getAlpha() * 2)) : hoverColor);
                int arc = Math.min(getHeight() - 2, cute() ? 16 : 6);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 화면 배율(HiDPI)을 고려해 미리 축소해 둔 이미지 아이콘 */
    static final class ScaledImageIcon implements Icon {
        final java.awt.image.BufferedImage pixels;
        final int w, h;

        ScaledImageIcon(java.awt.image.BufferedImage src, int maxW, int maxH) {
            double fit = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
            w = Math.max(1, (int) Math.round(src.getWidth() * fit));
            h = Math.max(1, (int) Math.round(src.getHeight() * fit));
            double dpi = GraphicsEnvironment.isHeadless() ? 1.0
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration().getDefaultTransform().getScaleX();
            int pw = Math.min(src.getWidth(), (int) Math.round(w * dpi)), ph = Math.min(src.getHeight(), (int) Math.round(h * dpi));
            Image scaled = (pw == src.getWidth() && ph == src.getHeight()) ? src : src.getScaledInstance(pw, ph, Image.SCALE_SMOOTH);
            pixels = new java.awt.image.BufferedImage(pw, ph, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = pixels.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
        }

        public int getIconWidth() { return w; }
        public int getIconHeight() { return h; }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(pixels, x, y, w, h, null);
            g2.dispose();
        }
    }

    static final class ImageSelection implements Transferable {
        final Image img;

        ImageSelection(Image img) { this.img = img; }

        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }

        public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
            return img;
        }
    }

    static java.awt.image.BufferedImage toBuffered(Image img) {
        if (img instanceof java.awt.image.BufferedImage bi) return bi;
        ImageIcon loaded = new ImageIcon(img);      // 크기 정보가 준비될 때까지 대기
        if ((long) loaded.getIconWidth() * loaded.getIconHeight() > Store.MAX_PIXELS) return null;   // 너무 큰 그림
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                Math.max(1, loaded.getIconWidth()), Math.max(1, loaded.getIconHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(loaded.getImage(), 0, 0, null);
        g.dispose();
        return bi;
    }

    /** 앱 아이콘: 오른쪽 아래 모서리가 접힌 메모지 + 글줄 (창 아이콘·ico 공용) */
    static java.awt.image.BufferedImage appIcon(int size, Theme theme) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size, x0 = s * .12, y0 = s * .1, x1 = s * .88, y1 = s * .9, f = s * .26, r = s * .1;
        java.awt.geom.Path2D.Double note = new java.awt.geom.Path2D.Double();
        note.moveTo(x0 + r, y0);
        note.lineTo(x1 - r, y0);
        note.quadTo(x1, y0, x1, y0 + r);
        note.lineTo(x1, y1 - f);
        note.lineTo(x1 - f, y1);
        note.lineTo(x0 + r, y1);
        note.quadTo(x0, y1, x0, y1 - r);
        note.lineTo(x0, y0 + r);
        note.quadTo(x0, y0, x0 + r, y0);
        note.closePath();
        float[] hsb = Color.RGBtoHSB(theme.header.getRed(), theme.header.getGreen(), theme.header.getBlue(), null);
        Color paper = Color.getHSBColor(hsb[0], Math.min(1f, hsb[1] * 1.7f), hsb[2]);   // 작은 크기에서도 보이게 조금 진하게
        g.setColor(paper);
        g.fill(note);
        // 접힌 모서리
        java.awt.geom.Path2D.Double fold = new java.awt.geom.Path2D.Double();
        fold.moveTo(x1, y1 - f);
        fold.lineTo(x1 - f + s * .04, y1 - f);
        fold.quadTo(x1 - f, y1 - f, x1 - f, y1 - f + s * .04);
        fold.lineTo(x1 - f, y1);
        fold.closePath();
        g.setColor(paper.darker());
        g.fill(fold);
        g.setStroke(new BasicStroke((float) Math.max(1, s / 36)));
        g.draw(note);
        // 글줄
        g.setColor(new Color(0, 0, 0, 110));
        g.setStroke(new BasicStroke((float) Math.max(1.2, s / 14), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double lx = s * .27;
        g.draw(new java.awt.geom.Line2D.Double(lx, s * .32, s * .73, s * .32));
        g.draw(new java.awt.geom.Line2D.Double(lx, s * .5, s * .73, s * .5));
        g.draw(new java.awt.geom.Line2D.Double(lx, s * .68, s * .5, s * .68));
        g.dispose();
        return img;
    }

    /** 얇고 둥근 스크롤바 */
    static final class CuteScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color a = c.getClientProperty("accent") instanceof Color col ? col : new Color(0xD8C8B8);
            g2.setColor(new Color(a.getRed(), a.getGreen(), a.getBlue(), isThumbRollover() ? 220 : 150));
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, r.width - 4, r.width - 4);
            g2.dispose();
        }
    }

    /**
     * 메모 목록 카드의 미리보기 글. HTML JLabel 대신 직접 줄바꿈해 그린다
     * (HTML 은 색만 바꿔도 다시 파싱하고 배치마다 다시 재서 메모가 많으면 느림).
     */
    static final class PreviewText extends JComponent {
        static final int WRAP = 250;
        final List<String> src;
        final String placeholder;                            // "빈 메모"·"잠긴 메모" 처럼 기울여 쓰는 안내 (없으면 null)
        List<String> wrapped;
        Font wrappedFor;

        PreviewText(List<String> src, String placeholder) {
            this.src = src;
            this.placeholder = placeholder;
            setOpaque(false);
        }

        /** 글꼴이 바뀔 때만 다시 줄바꿈 (공백에서 우선 끊고, 없으면 글자 단위) */
        List<String> lines() {
            Font f = getFont();
            if (wrapped != null && f.equals(wrappedFor)) return wrapped;
            FontMetrics fm = getFontMetrics(f);
            List<String> out = new ArrayList<>();
            if (placeholder != null) out.add(placeholder);
            for (String s : src) {
                if (s.isEmpty()) { out.add(""); continue; }
                int start = 0;
                while (start < s.length()) {
                    int end = start, lastSpace = -1, w = 0;
                    while (end < s.length()) {
                        int cw = fm.charWidth(s.charAt(end));
                        if (w + cw > WRAP && end > start) break;
                        if (s.charAt(end) == ' ') lastSpace = end;
                        w += cw;
                        end++;
                    }
                    if (end < s.length() && lastSpace > start) end = lastSpace + 1;
                    out.add(s.substring(start, end).stripTrailing());
                    start = end;
                }
            }
            wrapped = out;
            wrappedFor = f;
            return out;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(WRAP, Math.max(1, lines().size()) * fm.getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);   // 회색조에서 맑은 고딕 획 빠짐 방지
            Font f = getFont();
            g2.setFont(placeholder != null ? f.deriveFont(Font.ITALIC) : f);
            g2.setColor(getForeground());
            FontMetrics fm = g2.getFontMetrics();
            int y = fm.getAscent();
            String ql = query == null || query.isBlank() ? null : query.toLowerCase();
            for (String l : lines()) {
                if (ql != null && placeholder == null) {             // 찾는 글자 노란 표시
                    String low = l.toLowerCase();
                    for (int i = low.indexOf(ql); i >= 0; i = low.indexOf(ql, i + ql.length())) {
                        int x0 = fm.stringWidth(l.substring(0, i)), w = fm.stringWidth(l.substring(i, i + ql.length()));
                        g2.setColor(new Color(255, 214, 0, 150));
                        g2.fillRoundRect(x0 - 1, y - fm.getAscent(), w + 2, fm.getHeight(), 4, 4);
                    }
                    g2.setColor(getForeground());
                }
                g2.drawString(l, 0, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }

        /** 메모 목록에서 찾는 글자 (없으면 null) */
        String query;

        void setQuery(String q) {
            if (Objects.equals(q, query)) return;
            query = q;
            repaint();
        }
    }

    /** 스크롤 창 너비를 따라가는 패널 (가로 스크롤 없이 세로로만 스크롤) */
    static final class TrackWidthPanel extends JPanel implements Scrollable {
        TrackWidthPanel() { super(new BorderLayout()); }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 32); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** 줄이 넘치면 다음 줄로 감싸는 FlowLayout (선호 높이를 감싼 줄 수만큼 계산) */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

        @Override
        public Dimension preferredLayoutSize(Container target) { return size(target, true); }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension d = size(target, false);
            d.width = 0;
            return d;
        }

        Dimension size(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Container parent = target.getParent();
                int width = parent != null && parent.getWidth() > 0
                        ? parent.getWidth() - parent.getInsets().left - parent.getInsets().right
                        : target.getWidth();
                if (width <= 0) width = Integer.MAX_VALUE;
                Insets in = target.getInsets();
                int maxW = width - in.left - in.right - getHgap() * 2;
                int w = 0, h = 0, rowW = 0, rowH = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (rowW > 0 && rowW + getHgap() + d.width > maxW) {
                        w = Math.max(w, rowW);
                        h += rowH + getVgap();
                        rowW = 0;
                        rowH = 0;
                    }
                    rowW += (rowW > 0 ? getHgap() : 0) + d.width;
                    rowH = Math.max(rowH, d.height);
                }
                w = Math.max(w, rowW);
                h += rowH;
                return new Dimension(w + in.left + in.right + getHgap() * 2, h + in.top + in.bottom + getVgap() * 2);
            }
        }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean onAnyScreen(Rectangle r) {
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle sb = gd.getDefaultConfiguration().getBounds();
            Rectangle inter = sb.intersection(r);
            if (inter.width >= 80 && inter.height >= 40) return true;
        }
        return false;
    }

    // ===================================================================== 애플리케이션
    static final class App {
        Store store;
        final List<Note> notes = new ArrayList<>();
        final Map<String, NoteWindow> windows = new LinkedHashMap<>();
        NotesListWindow list;
        FileLock lock;

        void start() {
            try {
                store = new Store();
                FileChannel ch = FileChannel.open(store.dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = ch.tryLock();
                if (lock == null) {
                    Ask.notice(null, Ask.Kind.INFO, "QuickMemo가 이미 실행 중이에요", "열려 있는 메모나 메모 목록을 사용해 주세요.");
                    System.exit(0);
                }
            } catch (IOException e) {
                Ask.notice(null, Ask.Kind.WARN, "메모 저장소를 열 수 없어요", e.getMessage());
                System.exit(1);
            }
            applyStyle(chosenStyle());                                  // 기본은 심플
            new javax.swing.Timer(60 * 60 * 1000, e -> {                // 켜 둔 채 계절이 바뀌면 따라 바꿈 (한 시간마다 확인)
                if (store.autoSeason() && chosenStyle() != STYLE) setStyle(chosenStyle());
            }).start();
            notes.addAll(store.loadAll());
            for (Note n : new ArrayList<>(notes))                   // 예전에 남은 빈 메모(닫힌 것) 정리
                if (!n.open && !n.deleted() && isEmptyNote(n)) { notes.remove(n); store.remove(n); }
            list = new NotesListWindow(this);
            purgeExpiredTrash();
            Backup.daily(store);                                     // 하루 한 번 자동 백업 (켜 둔 채 날이 바뀌면 다음 날도)
            if (trayEnabled()) setTray(true);
            if (hotkeyEnabled()) setHotkey(true);
            new javax.swing.Timer(60 * 1000, e -> { if (store.followSystemTheme()) syncSystemTheme(); }).start();
            javax.swing.Timer remind = new javax.swing.Timer(15 * 1000, e -> checkReminders());
            remind.setInitialDelay(2000);                            // 꺼져 있는 동안 지난 알림은 켜고 곧바로
            remind.start();
            new javax.swing.Timer(6 * 60 * 60 * 1000, e -> checkUpdateQuietly()).start();   // 켜 둔 채 새 버전이 나오면 알림 (시작할 때는 Updater 가 확인)
            new javax.swing.Timer(6 * 60 * 60 * 1000, e -> Backup.daily(store)).start();

            int opened = 0;
            for (Note n : new ArrayList<>(notes)) {
                if (n.open && !n.deleted()) { openNote(n); opened++; }
            }
            registerStartMenu();
            // 처음 실행이고 Windows 스티커 메모가 있으면 한 번 가져오기를 제안
            if (notes.isEmpty() && StickyImport.available() && !"true".equals(store.settings.getProperty("importOffered"))) {
                store.set("importOffered", "true");
                boolean yes = Ask.confirm(null, Ask.Kind.QUESTION, "Windows 스티커 메모를 가져올까요?",
                        "나중에 메모 목록 > 설정에서도 가져올 수 있어요.", "가져오기", "나중에");
                if (yes && importStickyNotes(null, false) > 0) { showList(); return; }
            }
            if (opened == 0) {
                if (activeNotes().isEmpty()) newNote(null);
                else showList();
            }
        }

        /** 시작 메뉴 바로가기 이름. 윈도우 검색에서 "memo", "메모" 로 찾을 수 있게 두 단어를 모두 넣음 */
        static final String START_MENU_NAME = "Quick Memo (메모).lnk";

        /**
         * exe 로 실행 중이면 시작 메뉴에 바로가기를 만들거나 경로를 고친다 (백그라운드, 실패해도 무시).
         * 같은 경로로 이미 만들었으면 아무것도 하지 않음.
         */
        void registerStartMenu() {
            Path dir = Updater.installDir();
            String appData = System.getenv("APPDATA");
            if (dir == null || appData == null) return;
            Path exe = dir.resolve("QuickMemo.exe");
            Path lnk = Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", START_MENU_NAME);
            String mark = exe + "|" + VERSION;                         // 위치나 버전이 바뀌면 다시 등록
            if (mark.equals(store.settings.getProperty("startMenuTarget")) && Files.exists(lnk)) return;
            store.set("startMenuTarget", mark);
            boolean auto = autoStart();
            Thread t = new Thread(() -> {
                boolean ok = makeShortcut(lnk, exe, dir);
                if (auto) makeShortcut(autoStartLink(), exe, dir);      // 자동 실행 바로가기도 새 위치로
                boolean reg = Installer.register(dir);
                Updater.log("start menu shortcut: " + lnk + " -> " + exe + " (" + ok + "), uninstall entry (" + reg + ")");
            }, "start-menu");
            t.setDaemon(true);
            t.start();
        }

        /** 바로가기(.lnk) 만들기. 경로는 명령줄 인자로만 넘기고 스크립트는 고정 문자열 */
        static boolean makeShortcut(Path lnk, Path exe, Path workDir) {
            try {
                Files.createDirectories(lnk.getParent());
                Path script = Files.createTempFile("QuickMemo-shortcut-", ".ps1");
                Files.writeString(script, String.join("\r\n",
                        "param([string]$Lnk, [string]$Exe, [string]$Dir)",
                        "$ErrorActionPreference = 'Stop'",
                        "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($Lnk)",
                        "$s.TargetPath = $Exe",
                        "$s.WorkingDirectory = $Dir",
                        "$s.IconLocation = \"$Exe,0\"",
                        "$s.Description = 'QuickMemo'",
                        "$s.Save()",
                        ""), StandardCharsets.UTF_8);
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", script.toString(), "-Lnk", lnk.toString(), "-Exe", exe.toString(), "-Dir", workDir.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                boolean ok = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
                Files.deleteIfExists(script);
                return ok && Files.exists(lnk);
            } catch (Exception e) {
                return false;
            }
        }

        // ---------- 트레이 · Windows 시작 시 실행 · 전역 단축키
        TrayIcon tray;
        boolean hotkeyOn;

        /** 시작 프로그램 폴더의 바로가기 (Windows 로그인하면 실행) */
        static Path autoStartLink() {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "QuickMemo.lnk");
        }

        boolean autoStart() { Path l = autoStartLink(); return l != null && Files.exists(l); }

        /** 켜기: exe 로 실행 중일 때만 (jar 개발 실행은 바로가기 대상이 없음). 결과를 돌려줌 */
        boolean setAutoStart(boolean on) {
            Path lnk = autoStartLink(), dir = Updater.installDir();
            if (lnk == null) return false;
            if (!on) { try { Files.deleteIfExists(lnk); } catch (IOException ignored) { } return !Files.exists(lnk); }
            return dir != null && makeShortcut(lnk, dir.resolve("QuickMemo.exe"), dir);
        }

        boolean trayEnabled() { return !"false".equals(store.settings.getProperty("tray")); }

        /** 알림 영역 아이콘: 두 번 누르면 메모 목록, 메뉴에서 새 메모·목록·종료 */
        void setTray(boolean on) {
            store.set("tray", String.valueOf(on));
            if (!SystemTray.isSupported()) return;
            if (!on) {
                if (tray != null) SystemTray.getSystemTray().remove(tray);
                tray = null;
                checkExit();
                return;
            }
            if (tray != null) return;
            PopupMenu m = new PopupMenu();
            Font f = new Font("Malgun Gothic", Font.PLAIN, 12);
            MenuItem nw = new MenuItem("새 메모");
            nw.addActionListener(e -> SwingUtilities.invokeLater(() -> newNote(null)));
            MenuItem ls = new MenuItem("메모 목록");
            ls.addActionListener(e -> SwingUtilities.invokeLater(this::showList));
            MenuItem quit = new MenuItem("종료");
            quit.addActionListener(e -> SwingUtilities.invokeLater(this::exit));
            for (MenuItem i : List.of(nw, ls, quit)) i.setFont(f);
            m.add(nw);
            m.add(ls);
            m.addSeparator();
            m.add(quit);
            TrayIcon t = new TrayIcon(appIcon(32, Theme.Yellow), "QuickMemo", m);
            t.setImageAutoSize(true);
            t.addActionListener(e -> SwingUtilities.invokeLater(this::showList));
            try {
                SystemTray.getSystemTray().add(t);
                tray = t;
            } catch (AWTException e) {
                tray = null;
            }
        }

        boolean hotkeyEnabled() { return !"false".equals(store.settings.getProperty("hotkey")); }

        /** Ctrl+Alt+M 새 메모. QuickMemoHotkey(미리보기 기능 필요)를 못 쓰면 조용히 꺼짐 */
        void setHotkey(boolean on) {
            store.set("hotkey", String.valueOf(on));
            try {
                Class<?> hk = Class.forName("QuickMemoHotkey");
                if (!on) {
                    hk.getMethod("stop").invoke(null);
                    hotkeyOn = false;
                } else if (!hotkeyOn) {
                    Runnable r = () -> { NoteWindow w = newNoteWindow(); if (w != null) { w.toFront(); w.requestFocus(); } };
                    hotkeyOn = (boolean) hk.getMethod("start", int.class, int.class, Runnable.class).invoke(null, 0x2 | 0x1, (int) 'M', r);
                }
            } catch (Throwable e) {
                hotkeyOn = false;
            }
        }

        /** Windows 스티커 메모를 가져와 닫힌 메모로 추가. 이미 가져온 메모는 건너뜀. 가져온 개수 반환 */
        int importStickyNotes(Component parent, boolean confirm) {
            List<Map<String, Object>> rows;
            try {
                rows = StickyImport.readNotes();
            } catch (Exception e) {
                Ask.notice(parent, Ask.Kind.WARN, "스티커 메모를 읽지 못했어요", e.getMessage());
                return 0;
            }
            Set<String> done = new HashSet<>();
            for (Note n : notes) if (n.importedFrom != null) done.add(n.importedFrom);
            List<Map<String, Object>> todo = new ArrayList<>();
            for (Map<String, Object> r : rows) if (!done.contains((String) r.get("Id"))) todo.add(r);
            int skipped = rows.size() - todo.size();
            if (todo.isEmpty()) {
                Ask.notice(parent, Ask.Kind.INFO, rows.isEmpty() ? "가져올 스티커 메모가 없어요" : "새로 가져올 메모가 없어요",
                        rows.isEmpty() ? null : "이미 가져온 메모 " + skipped + "개");
                return 0;
            }
            if (confirm && !Ask.confirm(parent, Ask.Kind.QUESTION, "스티커 메모 " + todo.size() + "개를 가져올까요?",
                    skipped > 0 ? "이미 가져온 " + skipped + "개는 건너뛰어요." : null, "가져오기", "취소")) return 0;
            for (Map<String, Object> r : todo) {
                Note n = new Note();
                n.importedFrom = (String) r.get("Id");
                n.theme = Theme.parse(String.valueOf(r.get("Theme")));
                n.text = r.get("Text") instanceof String s ? s.replace("\r\n", "\n") : "";
                if (r.get("CreatedAt") instanceof Long c) n.createdAt = c;
                n.updatedAt = r.get("UpdatedAt") instanceof Long u ? u : n.createdAt;
                notes.add(n);
                store.save(n);
            }
            list.refresh();
            Ask.notice(parent, Ask.Kind.INFO, todo.size() + "개의 메모를 가져왔어요", "메모 목록에서 열어 보세요.");
            return todo.size();
        }

        /**
         * 목록 순서: 즐겨찾기 먼저. 각 묶음 안에서는 아직 순서를 정하지 않은 메모(새 메모 등)가 최근 수정 순으로 위,
         * 그다음 사용자가 끌어서 정한 순서.
         */
        static final Comparator<Note> LIST_ORDER = (a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            if ((a.order == null) != (b.order == null)) return a.order == null ? -1 : 1;
            if (a.order == null) return Long.compare(b.updatedAt, a.updatedAt);
            return Long.compare(a.order, b.order);
        };

        List<Note> activeNotes() {
            List<Note> l = new ArrayList<>();
            for (Note n : notes) if (!n.deleted()) l.add(n);
            l.sort(LIST_ORDER);
            return l;
        }

        /** 즐겨찾기 켜기/끄기. 옮겨 간 묶음의 맨 위에 놓인다 */
        void toggleFavorite(Note n) {
            n.favorite = !n.favorite;
            n.order = null;
            store.save(n);
            list.refresh();
        }

        /** 한 묶음(즐겨찾기 또는 일반)의 새 순서를 저장 */
        void reorder(List<Note> group) {
            for (int i = 0; i < group.size(); i++) {
                Note n = group.get(i);
                if (n.order == null || n.order != i) {
                    n.order = (long) i;
                    store.save(n);
                }
            }
            list.refresh();
        }

        void newNote(Window near) { newNoteWindow(near); }

        NoteWindow newNoteWindow() { return newNoteWindow(null); }

        NoteWindow newNoteWindow(Window near) {
            Note n = new Note();
            n.theme = store.defaultTheme();
            n.open = true;
            Rectangle base = near != null ? near.getBounds() : null;
            if (base != null) {
                n.bounds = new Rectangle(base.x + base.width + 12, base.y, 320, 320);
                if (!onAnyScreen(n.bounds)) n.bounds = new Rectangle(base.x + 30, base.y + 30, 320, 320);
            }
            notes.add(n);
            store.save(n);
            NoteWindow w = openNote(n);
            w.pane.requestFocusInWindow();
            list.refresh();
            return w;
        }

        NoteWindow openNote(Note n) {
            NoteWindow w = windows.get(n.id);
            if (w == null) {
                w = new NoteWindow(this, n);
                windows.put(n.id, w);
            }
            if (!n.open) { n.open = true; list.refresh(); }
            w.setVisible(true);
            w.toFront();
            n.bounds = w.getBounds();
            store.save(n);
            return w;
        }

        void closeNote(Note n) {
            NoteWindow w = windows.remove(n.id);
            if (w != null) { w.flush(); w.dispose(); }
            n.open = false;
            if (isEmptyNote(n)) {                                   // 아무것도 쓰지 않고 닫으면 남기지 않음
                notes.remove(n);
                store.remove(n);
            } else {
                store.save(n);
            }
            list.refresh();
            checkExit();
        }

        void deleteNote(Note n, Component parent) {
            if (store.confirmDelete()) {
                JCheckBox dontAsk = new JCheckBox("다시 묻지 않음");
                dontAsk.setFont(BASE_FONT.deriveFont(12.5f));
                if (!Ask.show(parent, Ask.Kind.DANGER, "이 메모를 삭제할까요?", "휴지통에서 " + TRASH_DAYS + "일 동안 되살릴 수 있어요.", "삭제", "취소", dontAsk)) return;
                if (dontAsk.isSelected()) store.set("confirmDelete", "false");
            }
            deleteNotes(List.of(n));
        }

        /** 휴지통 보관 기간. 지나면 시작할 때 완전히 지움 */
        static final int TRASH_DAYS = 30;
        static final long DAY_TICKS = 24L * 3600 * 10_000_000L;

        /** 여러 메모를 휴지통으로 (확인은 부른 쪽에서). 목록은 마지막에 한 번만 다시 그리고 되돌리기 알림을 띄움 */
        void deleteNotes(List<Note> del) {
            long now = nowTicks();
            List<Note> done = new ArrayList<>();
            Map<String, Rectangle> wasOpen = new HashMap<>();
            for (Note n : del) {
                NoteWindow w = windows.remove(n.id);
                if (w != null) { w.flush(); wasOpen.put(n.id, w.getBounds()); w.dispose(); }
                n.open = false;
                n.deletedAt = now;
                store.save(n);
                done.add(n);
            }
            list.refresh();
            if (!done.isEmpty()) {
                // 지운 직후 몇 초 동안 되돌릴 수 있게. 알림이 떠 있는 동안은 앱을 끝내지 않음
                Toast.show(list.isShowing() ? list : null, (done.size() == 1 ? "메모를" : "메모 " + done.size() + "개를") + " 휴지통으로 옮겼어요",
                        "되돌리기", () -> restoreNotes(done, wasOpen), this::checkExit);
            } else {
                checkExit();
            }
        }

        /** 휴지통에서 되살리기. 지울 때 열려 있던 메모는 다시 연다 */
        void restoreNotes(List<Note> notesToRestore, Map<String, Rectangle> reopen) {
            for (Note n : notesToRestore) {
                n.deletedAt = null;
                store.save(n);
                if (reopen != null && reopen.containsKey(n.id)) { n.bounds = reopen.get(n.id); openNote(n); }
            }
            list.refresh();
        }

        List<Note> trashedNotes() {
            List<Note> l = new ArrayList<>();
            for (Note n : notes) if (n.deleted()) l.add(n);
            l.sort((a, b) -> Long.compare(b.deletedAt, a.deletedAt));
            return l;
        }

        /** 완전히 지우기: 메모 파일과, 다른 메모가 쓰지 않는 그림 파일까지 */
        void purgeNotes(List<Note> purge) {
            Set<String> media = new HashSet<>();
            for (Note n : purge) {
                media.addAll(Markup.mediaIds(n.text));
                notes.remove(n);
                store.remove(n);
            }
            if (!media.isEmpty() && notes.stream().noneMatch(Note::locked)) {   // 잠긴 메모는 그림 참조를 볼 수 없으므로 그때는 그림을 남김
                for (Note n : notes) media.removeAll(Markup.mediaIds(n.text));
                for (String id : media) store.removeMedia(id);
            }
            list.refresh();
        }

        /** 보관 기간이 지난 휴지통 메모를 완전히 지움 (시작할 때) */
        void purgeExpiredTrash() {
            long limit = nowTicks() - TRASH_DAYS * DAY_TICKS;
            List<Note> old = new ArrayList<>();
            for (Note n : notes) if (n.deleted() && n.deletedAt < limit) old.add(n);
            if (!old.isEmpty()) purgeNotes(old);
        }

        /** 밝게/어둡게: 설정 저장 + 창 테마(FlatLaf) 전환 + 열린 창 다시 칠하기 */
        void setDark(boolean dark) {
            store.set("darkMode", String.valueOf(dark));
            // 목록 카드(직접 그리는 컴포넌트 수백 개)는 테마 갱신에서 빼고, 끝난 뒤 색만 바꿔 다시 붙인다
            if (list != null && list.cards != null) list.cards.removeAll();
            switchLaf(dark);
            for (NoteWindow w : windows.values()) w.applyTheme();
            list.applyMode();
        }

        // ---------- 실행 중 업데이트 확인 · 설치
        String offeredVersion;                                       // 이번 실행에서 이미 알린 새 버전

        /** 켜 둔 채 새 버전이 나오면 알림 (exe 실행일 때만, 확인은 백그라운드) */
        void checkUpdateQuietly() {
            if (Updater.installDir() == null || UPDATE_REPO.isBlank()) return;
            Thread t = new Thread(() -> {
                Updater.Release r;
                try { r = Updater.latest(); } catch (Exception e) { return; }
                if (r == null || Updater.compare(r.version(), VERSION) <= 0) return;
                SwingUtilities.invokeLater(() -> {
                    if (r.version().equals(offeredVersion)) return;
                    offeredVersion = r.version();
                    Toast.show(list.isShowing() ? list : null, "QuickMemo " + r.version() + " 버전이 나왔어요", "업데이트", () -> updateNow(r), null);
                });
            }, "update-check");
            t.setDaemon(true);
            t.start();
        }

        /** 설정의 "업데이트 확인" */
        void checkUpdateNow(Component parent) {
            if (Updater.installDir() == null) { Ask.notice(parent, Ask.Kind.INFO, "개발용 실행이에요", "설치된 QuickMemo(exe)에서만 업데이트할 수 있어요."); return; }
            Thread t = new Thread(() -> {
                Updater.Release r;
                try { r = Updater.latest(); } catch (Exception e) { r = null; }
                Updater.Release found = r;
                SwingUtilities.invokeLater(() -> {
                    if (found == null) Ask.notice(parent, Ask.Kind.WARN, "업데이트를 확인하지 못했어요", "인터넷 연결을 확인한 뒤 다시 시도해 주세요.");
                    else if (Updater.compare(found.version(), VERSION) <= 0) Ask.notice(parent, Ask.Kind.INFO, "최신 버전이에요", "지금 버전: " + VERSION);
                    else updateNow(found);
                });
            }, "update-check");
            t.setDaemon(true);
            t.start();
        }

        /** 메모를 저장하고 새 버전으로 바꾼 뒤 다시 실행. 실패하면 그대로 계속 */
        void updateNow(Updater.Release r) {
            Path dir = Updater.installDir();
            if (dir == null) return;
            if (!Ask.confirm(list.isShowing() ? list : null, Ask.Kind.QUESTION, "QuickMemo " + r.version() + "로 업데이트할까요?",
                    "메모를 저장하고 다시 실행해요.", "업데이트", "나중에")) return;
            for (NoteWindow w : windows.values()) w.flush();
            releaseLock();                                           // 업데이트 스크립트가 새 버전을 띄울 수 있게
            Thread t = new Thread(() -> {
                if (!Updater.apply(r, dir)) SwingUtilities.invokeLater(this::takeLock);   // 성공하면 apply 가 종료
            }, "update");
            t.start();
        }

        void releaseLock() {
            try { if (lock != null) { lock.release(); lock.channel().close(); } } catch (IOException ignored) { }
            lock = null;
        }

        void takeLock() {
            try { lock = FileChannel.open(store.dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock(); } catch (IOException ignored) { }
        }

        /** 설정의 "이 PC에 설치": 표준 위치로 복사 → 이 창이 끝나면 설치본 실행 */
        void installNow(Component parent) {
            Path from = Updater.installDir(), to = Installer.home();
            if (!Ask.confirm(parent, Ask.Kind.QUESTION, "이 PC에 설치할까요?",
                    to + " 에 설치하고 시작 메뉴에 등록해요.\n설치한 뒤에는 지금 폴더를 지워도 돼요.", "설치", "취소")) return;
            Thread t = new Thread(() -> {
                Updater.Progress pw = Updater.Progress.show("QuickMemo 설치 중...");
                try {
                    Path dst = Installer.copyToHome();
                    pw.close();
                    SwingUtilities.invokeAndWait(() -> {
                        for (NoteWindow w : windows.values()) w.flush();
                        releaseLock();
                    });
                    Path script = Files.createTempFile("QuickMemo-relaunch-", ".ps1");
                    Files.writeString(script, String.join("\r\n",
                            "param([int]$OldPid, [string]$Exe)",
                            "try { Wait-Process -Id $OldPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}",
                            "Start-Process -FilePath $Exe -WorkingDirectory (Split-Path -Parent $Exe)",
                            "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue",
                            ""), StandardCharsets.UTF_8);
                    new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                            "-File", script.toString(), "-OldPid", String.valueOf(ProcessHandle.current().pid()),
                            "-Exe", dst.resolve("QuickMemo.exe").toString()).directory(script.getParent().toFile()).start();
                    Updater.log("installed " + from + " -> " + dst);
                    System.exit(0);
                } catch (Exception e) {
                    pw.close();
                    Updater.log("install failed: " + e);
                    SwingUtilities.invokeLater(() -> Ask.notice(parent, Ask.Kind.WARN, "설치하지 못했어요", e.getMessage()));
                }
            }, "install");
            t.start();
        }

        // ---------- 알림
        /** 알림 시각 저장 (null 이면 끄기). 수정 시각은 바꾸지 않음 */
        void setReminder(Note n, Long at) {
            n.remindAt = at;
            store.save(n);
            if (list != null) list.refresh();
        }

        /** 시각이 지난 알림을 띄운다 (꺼져 있는 동안 지난 알림은 켜질 때 바로) */
        void checkReminders() {
            long now = System.currentTimeMillis();
            for (Note n : new ArrayList<>(notes)) {
                if (n.remindAt == null || n.remindAt > now) continue;
                n.remindAt = null;
                store.save(n);
                if (!n.deleted()) fireReminder(n);
            }
        }

        void fireReminder(Note n) {
            NoteWindow w = openNote(n);
            w.setExtendedState(Frame.NORMAL);
            w.toFront();
            w.requestFocus();
            Toolkit.getDefaultToolkit().beep();
            String title = n.locked() ? "잠긴 메모" : Markup.plain(n.text).strip().split("\n")[0];   // 잠긴 메모는 내용을 알림에 보이지 않음
            if (title.isBlank()) title = "메모";
            if (title.length() > 24) title = title.substring(0, 24) + "…";
            if (tray != null) tray.displayMessage("QuickMemo 알림", title, TrayIcon.MessageType.INFO);
            Toast.show(null, "알림 · " + title, "10분 뒤 다시", () -> setReminder(n, System.currentTimeMillis() + 10 * 60_000L), null);
        }

        /** "오늘 오후 6:00" / "내일 오전 9:00" / "10월 25일 오전 9:00" */
        static String remindText(long millis) {
            ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
            LocalDate d = t.toLocalDate(), today = LocalDate.now();
            String day = d.equals(today) ? "오늘" : d.equals(today.plusDays(1)) ? "내일" : d.format(NotesListWindow.DATE);
            return day + " " + t.format(NotesListWindow.TIME);
        }

        /** 메모 글자 크기를 바꾸고 열린 메모에 바로 적용 */
        void setFontSize(int size) {
            store.set("fontSize", String.valueOf(size));
            for (NoteWindow w : windows.values()) {
                w.pane.setFont(BASE_FONT.deriveFont((float) size));
                w.pane.revalidate();
                w.pane.repaint();
            }
        }

        /** Windows 가 어둡게(앱 모드)인지: 레지스트리 AppsUseLightTheme 가 0 이면 어둡게 */
        static boolean systemDark() {
            try {
                Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
            } catch (Exception e) {
                return false;
            }
        }

        /** "시스템" 모드: Windows 설정을 따라 밝게/어둡게 (1분마다 확인, 확인은 백그라운드) */
        void followSystemTheme(boolean on) {
            store.set("followSystemTheme", String.valueOf(on));
            if (on) syncSystemTheme();
        }

        void syncSystemTheme() {
            Thread t = new Thread(() -> {
                boolean d = systemDark();
                SwingUtilities.invokeLater(() -> {
                    if (store.followSystemTheme() && d != store.darkMode()) {
                        setDark(d);
                        if (list.settings != null && list.settings.isShowing()) list.settings.rebuild();
                    }
                });
            }, "theme-check");
            t.setDaemon(true);
            t.start();
        }

        /** 지금 써야 할 디자인: 자동 변경이 켜져 있으면 계절 추천, 아니면 직접 고른 것 */
        Style chosenStyle() {
            return store.autoSeason() ? Style.forSeason(LocalDate.now())
                    : Style.parse(store.settings.getProperty("uiStyle", Style.Simple.name()));
        }

        /** 계절 자동 변경 켜기/끄기. 끄면 직접 골랐던 디자인으로 돌아감 */
        void setAutoSeason(boolean on) {
            store.set("autoSeason", String.valueOf(on));
            setStyle(chosenStyle());
        }

        /** 디자인 스타일을 바꾸고 열린 모든 창에 바로 적용 */
        void setStyle(Style s) {
            applyStyle(s);
            for (NoteWindow w : windows.values()) {
                w.applyTheme();
                w.content.revalidate();
                w.repaint();
            }
            list.applyMode();
        }

        /** 서식 버튼 순서를 저장하고 열린 모든 메모에 적용 */
        void saveFormatOrder(List<String> ids) {
            store.set("formatOrder", String.join(",", ids));
            for (NoteWindow w : windows.values()) w.applyFormatOrder();
        }

        void showList() {
            list.refresh();
            list.setVisible(true);
            list.setState(Frame.NORMAL);
            list.toFront();
        }

        void noteChanged() { list.refresh(); }

        void checkExit() {
            SwingUtilities.invokeLater(() -> {
                if (windows.isEmpty() && !list.isVisible() && tray == null) exit();   // 트레이가 있으면 계속 실행
            });
        }

        void exit() {
            for (NoteWindow w : windows.values()) w.flush();
            System.exit(0);
        }
    }

    // ===================================================================== 노트 창
    static final class NoteWindow extends JFrame {
        /** 그림자를 그릴 바깥 여백 (이 여백 가장자리를 잡으면 크기 조절) */
        static final int M = 12, EDGE = 10, RING = 14, HEADER_H = 34, GARLAND_H = Deco.H;
        static final boolean TRANSLUCENT = !GraphicsEnvironment.isHeadless()
                && GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);

        final App app;
        final Note note;
        final JTextPane pane;
        final StyledDocument doc;
        final UndoManager undo = new UndoManager();
        final JPanel header = new JPanel(new BorderLayout());
        final JPanel formatBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 2));
        /** 서식 버튼 (id → 버튼), 기본 순서 */
        final Map<String, FlatButton> formatButtons = new LinkedHashMap<>();
        boolean dragSuppress;
        final JScrollPane scroll;
        final List<FlatButton> flatButtons = new ArrayList<>();
        final FlatButton bBold, bItalic, bUnder, bStrike, bBullet;
        final ShapePanel content = new ShapePanel();
        MenuPopup popup;
        FlatButton moreBtn;

        Component moreButton() { return moreBtn; }
        final javax.swing.Timer saveTimer;
        boolean loading;
        boolean dirtyText;

        NoteWindow(App app, Note note) {
            super("QuickMemo");
            this.app = app;
            this.note = note;
            setUndecorated(true);
            if (TRANSLUCENT) setBackground(new Color(0, 0, 0, 0));      // 모양 밖은 투명, 부드러운 그림자
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setMinimumSize(minSizeFor(note.shape));
            setIconImage(makeIcon());

            // ---- 헤더
            FlatButton add = iconButton("plus", "새 메모 (Ctrl+N)", () -> app.newNote(this));
            FlatButton[] moreRef = new FlatButton[1];
            FlatButton more = iconButton("more", "색깔·모양·메뉴", () -> showMenu(moreRef[0]));
            moreRef[0] = more;
            moreBtn = more;
            FlatButton close = iconButton("close", "메모 닫기 (Ctrl+W)", () -> app.closeNote(note));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(more);
            right.add(close);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            left.add(add);
            header.add(left, BorderLayout.WEST);
            header.add(right, BorderLayout.EAST);
            header.setOpaque(false);
            header.setPreferredSize(new Dimension(10, HEADER_H));
            MouseAdapter drag = new MouseAdapter() {
                Point start;
                public void mousePressed(MouseEvent e) { start = e.getPoint(); }
                public void mouseDragged(MouseEvent e) {
                    Point p = e.getLocationOnScreen();
                    setLocation(p.x - start.x, p.y - start.y);
                }
            };
            header.addMouseListener(drag);
            header.addMouseMotionListener(drag);

            // ---- 본문
            pane = new JTextPane() {
                @Override
                protected void paintComponent(Graphics g) {
                    // 반투명 창은 ClearType 을 못 쓰고 회색조로 그린다: 이때 맑은 고딕 획이 빠지지 않게
                    ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    super.paintComponent(g);
                    if (getDocument().getLength() == 0) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);   // 회색조에서 맑은 고딕 획 빠짐 방지
                        Color fg = getForeground();
                        g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 110));
                        g2.setFont(getFont());
                        Insets in = getInsets();
                        g2.drawString("메모 작성...", in.left + 2, in.top + g2.getFontMetrics().getAscent());
                        g2.dispose();
                    }
                }
            };
            pane.setFont(BASE_FONT.deriveFont((float) app.store.fontSize()));   // 설정의 글자 크기
            pane.setBorder(new EmptyBorder(6, 12, 6, 10));
            pane.setOpaque(false);                         // 스티커 모양(ShapePanel)이 배경을 그림
            doc = pane.getStyledDocument();
            pane.setTransferHandler(new NoteTransfer());   // 이미지 붙여넣기·끌어놓기
            scroll =new JScrollPane(pane, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(new EmptyBorder(0, 0, 0, 0));   // null 이면 테마를 바꿀 때 FlatLaf 가 기본 테두리(회색 선)를 다시 넣음
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
            scroll.getVerticalScrollBar().setUI(new CuteScrollBarUI());
            scroll.getVerticalScrollBar().setOpaque(false);
            formatBar.setOpaque(false);

            // ---- 서식 막대
            bBold = fmt("bold", "<html><b>B</b></html>", "굵게 (Ctrl+B)", () -> toggle(StyleConstants.Bold));
            bItalic = fmt("italic", "<html><i>I</i></html>", "기울임꼴 (Ctrl+I)", () -> toggle(StyleConstants.Italic));
            bUnder = fmt("underline", "<html><u>U</u></html>", "밑줄 (Ctrl+U)", () -> toggle(StyleConstants.Underline));
            bStrike = fmt("strike", "<html><s>ab</s></html>", "취소선 (Ctrl+T)", () -> toggle(StyleConstants.StrikeThrough));
            bBullet = fmt("bullets", "", "글머리 기호 (Ctrl+Shift+L, Tab으로 들여쓰기)", this::toggleBullets);
            bBullet.setIcon(new Glyph("bullets", 16));
            FlatButton bTodo = fmt("todo", "", "할 일 목록 (Ctrl+Shift+C)", this::toggleTodoList);
            bTodo.setIcon(new Glyph("checklist", 16));
            FlatButton bImage = fmt("image", "", "이미지 넣기 (붙여넣기·끌어놓기도 가능)", this::chooseImage);
            bImage.setIcon(new Glyph("image", 16));
            applyFormatOrder();

            content.add(header, BorderLayout.NORTH);
            content.add(scroll, BorderLayout.CENTER);
            content.add(formatBar, BorderLayout.SOUTH);
            setContentPane(content);
            if (note.unreadable()) pane.setEditable(false);           // 이 PC 에서 풀 수 없는 잠긴 메모는 고칠 수 없음
            applyLockView();
            Resizer rz = new Resizer();
            content.addMouseListener(rz);
            content.addMouseMotionListener(rz);

            // ---- 내용 로드
            loading = true;
            Markup.load(doc, note.text, this::mediaIcon);
            loading = false;
            pane.setCaretPosition(doc.getLength());
            doc.addUndoableEditListener(undo);

            saveTimer = new javax.swing.Timer(500, e -> flush());
            saveTimer.setRepeats(false);
            linkTimer = new javax.swing.Timer(400, e -> refreshLinks());
            linkTimer.setRepeats(false);
            refreshLinks();
            MouseAdapter links = new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e) || !e.isControlDown()) return;
                    String u = linkAt(e.getPoint());
                    if (u != null) { e.consume(); openLink(u); }
                }

                public void mouseMoved(MouseEvent e) {
                    String u = e.isControlDown() ? linkAt(e.getPoint()) : null;
                    pane.setCursor(Cursor.getPredefinedCursor(u != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                    pane.setToolTipText(u == null && linkAt(e.getPoint()) != null ? "Ctrl+클릭하면 열려요" : null);
                }
            };
            pane.addMouseListener(links);
            pane.addMouseMotionListener(links);
            doc.addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { textChanged(); }
                public void removeUpdate(DocumentEvent e) { textChanged(); }
                public void changedUpdate(DocumentEvent e) { textChanged(); }
            });
            pane.addCaretListener(e -> {
                updateFormatButtons();
                keepCaretAfterCheckbox();
            });
            MouseAdapter check = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    int ps = checkboxAt(e.getPoint());
                    if (ps >= 0 && SwingUtilities.isLeftMouseButton(e)) {
                        e.consume();
                        SwingUtilities.invokeLater(() -> toggleDone(ps));
                    }
                }
                public void mouseMoved(MouseEvent e) {
                    pane.setCursor(Cursor.getPredefinedCursor(checkboxAt(e.getPoint()) >= 0 ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                }
            };
            pane.addMouseListener(check);
            pane.addMouseMotionListener(check);

            bindKeys();
            applyTheme();
            setAlwaysOnTop(note.onTop);

            if (note.bounds != null && onAnyScreen(note.bounds)) setBounds(note.bounds);
            else {
                Dimension min = minSizeFor(note.shape);
                setSize(Math.max(340, min.width), Math.max(340, min.height));
                Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                int k = app.windows.size() % 8;
                setLocation(scr.x + scr.width - 360 - k * 30, scr.y + 40 + k * 30);
                note.bounds = getBounds();
            }

            addComponentListener(new ComponentAdapter() {
                public void componentMoved(ComponentEvent e) { boundsChanged(); }
                public void componentResized(ComponentEvent e) { boundsChanged(); updateWindowShape(); }
            });
            updateWindowShape();
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { app.closeNote(note); }
            });
        }

        // ---------- 이미지
        Icon mediaIcon(String id) {
            java.awt.image.BufferedImage img = app.store.loadImage(id);
            if (img == null) return new Glyph("image", 48);
            int maxW = pane.getWidth() > 120 ? pane.getWidth() - 40 : 260;
            return new ScaledImageIcon(img, maxW, 600);
        }

        void insertImage(java.awt.image.BufferedImage img) {
            try {
                String id = app.store.saveImage(img);
                int pos = pane.getSelectionStart();
                if (pane.getSelectionEnd() > pos) doc.remove(pos, pane.getSelectionEnd() - pos);
                if (pos > doc.getParagraphElement(pos).getStartOffset()) { doc.insertString(pos, "\n", null); pos++; }
                doc.insertString(pos, " ", Markup.imageAttrs(id, mediaIcon(id)));
                pos++;
                doc.insertString(pos, "\n", null);
                pane.setCaretPosition(pos + 1);
            } catch (IOException | BadLocationException ex) {
                Ask.notice(this, Ask.Kind.WARN, "그림을 넣지 못했어요", ex.getMessage());
            }
        }

        boolean insertImageFile(java.io.File f) {
            java.awt.image.BufferedImage img = Store.readImageSafely(f);     // 너무 큰 그림은 넣지 않음
            if (img == null) return false;
            insertImage(img);
            return true;
        }

        void chooseImage() {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("이미지 (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
            fc.setMultiSelectionEnabled(true);
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            for (java.io.File f : fc.getSelectedFiles()) insertImageFile(f);
        }

        /** 텍스트 복사/잘라내기/붙여넣기 + 이미지(클립보드·파일) 붙여넣기와 끌어놓기 */
        final class NoteTransfer extends TransferHandler {
            Position srcStart, srcEnd;

            @Override
            public int getSourceActions(JComponent c) { return COPY_OR_MOVE; }

            @Override
            protected Transferable createTransferable(JComponent c) {
                int s = pane.getSelectionStart(), e = pane.getSelectionEnd();
                if (s == e) return null;
                try {
                    srcStart = doc.createPosition(s);
                    srcEnd = doc.createPosition(e);
                } catch (BadLocationException ex) {
                    return null;
                }
                if (e - s == 1) {
                    AttributeSet a = doc.getCharacterElement(s).getAttributes();
                    Object id = a.getAttribute(Markup.MEDIA);
                    if (id != null && StyleConstants.getIcon(a) != null) {
                        java.awt.image.BufferedImage img = app.store.loadImage(id.toString());
                        if (img != null) return new ImageSelection(img);
                    }
                }
                return new StringSelection(pane.getSelectedText());
            }

            @Override
            protected void exportDone(JComponent c, Transferable data, int action) {
                if (action == MOVE && srcStart != null) {
                    try {
                        doc.remove(srcStart.getOffset(), srcEnd.getOffset() - srcStart.getOffset());
                    } catch (BadLocationException ignored) {
                    }
                }
                srcStart = srcEnd = null;
            }

            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || s.isDataFlavorSupported(DataFlavor.stringFlavor)
                        || s.isDataFlavorSupported(DataFlavor.imageFlavor);
            }

            @Override
            public boolean importData(TransferSupport s) {
                if (!canImport(s)) return false;
                if (s.isDrop()) {
                    int idx = ((JTextComponent.DropLocation) s.getDropLocation()).getIndex();
                    if (srcStart != null && idx >= srcStart.getOffset() && idx <= srcEnd.getOffset()) return false;
                    pane.setCaretPosition(idx);
                }
                Transferable t = s.getTransferable();
                try {
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        boolean any = false;
                        for (Object f : (List<?>) t.getTransferData(DataFlavor.javaFileListFlavor)) any |= insertImageFile((java.io.File) f);
                        return any;
                    }
                    if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        pane.replaceSelection((String) t.getTransferData(DataFlavor.stringFlavor));
                        return true;
                    }
                    if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        java.awt.image.BufferedImage img = toBuffered((Image) t.getTransferData(DataFlavor.imageFlavor));
                        if (img == null) return false;
                        insertImage(img);
                        return true;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                return false;
            }
        }

        // ---------- 버튼 생성
        FlatButton flat(String text, String tip, Runnable r) {
            FlatButton b = new FlatButton(text, tip);
            b.addActionListener(e -> r.run());
            flatButtons.add(b);
            return b;
        }

        FlatButton iconButton(String kind, String tip, Runnable r) {
            FlatButton b = flat("", tip, r);
            b.setIcon(new Glyph(kind, 16));
            b.setPreferredSize(new Dimension(36, 32));
            return b;
        }

        FlatButton fmt(String id, String text, String tip, Runnable r) {
            FlatButton b = flat(text, tip + " · 끌어서 순서 변경", () -> {
                if (dragSuppress) { dragSuppress = false; return; }      // 끌기를 끝낸 놓기는 클릭으로 치지 않음
                r.run();
                pane.requestFocusInWindow();
            });
            b.setPreferredSize(new Dimension(34, 28));
            b.setMargin(new Insets(0, 0, 0, 0));
            b.putClientProperty("fmtId", id);
            formatButtons.put(id, b);
            MouseAdapter dnd = new MouseAdapter() {
                Point start;
                boolean dragging;

                public void mousePressed(MouseEvent e) { start = e.getPoint(); dragging = false; }

                public void mouseDragged(MouseEvent e) {
                    if (!dragging && start != null && start.distance(e.getPoint()) < 6) return;
                    dragging = true;
                    dragSuppress = true;
                    b.grabbed = true;
                    b.repaint();
                    b.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    Point p = SwingUtilities.convertPoint(b, e.getPoint(), formatBar);
                    int target = -1, cur = -1;
                    Component[] cs = formatBar.getComponents();
                    for (int i = 0; i < cs.length; i++) {
                        if (cs[i] == b) cur = i;
                        if (cs[i].getBounds().contains(p)) target = i;
                    }
                    if (target < 0 && cs.length > 0 && p.y >= cs[cs.length - 1].getY() && p.x > cs[cs.length - 1].getX()) target = cs.length - 1;
                    if (target >= 0 && target != cur) {
                        formatBar.remove(b);
                        formatBar.add(b, target);
                        formatBar.revalidate();
                        formatBar.repaint();
                    }
                }

                public void mouseReleased(MouseEvent e) {
                    b.setCursor(Cursor.getDefaultCursor());
                    b.grabbed = false;
                    b.repaint();
                    if (dragging) app.saveFormatOrder(currentFormatOrder());
                    dragging = false;
                    SwingUtilities.invokeLater(() -> dragSuppress = false);
                }
            };
            b.addMouseListener(dnd);
            b.addMouseMotionListener(dnd);
            return b;
        }

        List<String> currentFormatOrder() {
            List<String> ids = new ArrayList<>();
            for (Component c : formatBar.getComponents())
                if (c instanceof JComponent jc && jc.getClientProperty("fmtId") instanceof String id) ids.add(id);
            return ids;
        }

        /** 저장된 순서(설정 formatOrder)대로 서식 버튼 배치. 저장에 없는 버튼은 기본 순서로 뒤에 붙임 */
        // ---------- 링크 (Ctrl+클릭으로 열기) · 찾은 글자 표시
        static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'\\u3000]{1,2000}");
        final List<Object> linkTags = new ArrayList<>(), findTags = new ArrayList<>();
        javax.swing.Timer linkTimer;

        /** 링크에 밑줄 (글이 바뀌면 잠시 뒤 다시 찾음) */
        void refreshLinks() {
            javax.swing.text.Highlighter h = pane.getHighlighter();
            for (Object t : linkTags) h.removeHighlight(t);
            linkTags.clear();
            String s;
            try { s = doc.getText(0, doc.getLength()); } catch (BadLocationException e) { return; }
            Matcher m = URL.matcher(s);
            Color c = note.theme.dark ? new Color(0x8CC8FF) : new Color(0x1F6FD1);
            while (m.find()) {
                int end = trimUrlEnd(s, m.start(), m.end());
                try { linkTags.add(h.addHighlight(m.start(), end, new UnderlinePainter(c))); } catch (BadLocationException ignored) { }
            }
        }

        /** 문장 끝 마침표·괄호는 주소에서 뺀다 */
        static int trimUrlEnd(String s, int start, int end) {
            while (end > start && ".,;:!?)]}".indexOf(s.charAt(end - 1)) >= 0) end--;
            return end;
        }

        /** 이 위치에 링크가 있으면 그 주소 */
        String linkAt(Point p) {
            int pos = pane.viewToModel2D(p);
            if (pos < 0) return null;
            String s;
            try { s = doc.getText(0, doc.getLength()); } catch (BadLocationException e) { return null; }
            Matcher m = URL.matcher(s);
            while (m.find()) {
                int end = trimUrlEnd(s, m.start(), m.end());
                if (pos >= m.start() && pos < end) return s.substring(m.start(), end);
            }
            return null;
        }

        /** http/https 주소만 기본 브라우저로 연다 (file:, javascript: 같은 것은 열지 않음) */
        static void openLink(String url) {
            java.net.URI u = safeLink(url);
            if (u == null) return;
            try { Desktop.getDesktop().browse(u); } catch (Exception ignored) { }
        }

        /** 열어도 되는 주소면 URI, 아니면 null */
        static java.net.URI safeLink(String url) {
            try {
                java.net.URI u = new java.net.URI(url);
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null ? u : null;
            } catch (Exception e) {
                return null;
            }
        }

        /** 메모 목록에서 찾은 글자를 노랗게 표시하고 첫 위치로 이동 (글을 고치면 사라짐) */
        void showFind(String q) {
            clearFind();
            if (q == null || q.isBlank() || note.lock && !revealed) return;
            String s;
            try { s = doc.getText(0, doc.getLength()).toLowerCase(); } catch (BadLocationException e) { return; }
            String ql = q.toLowerCase();
            int first = -1;
            for (int i = s.indexOf(ql); i >= 0; i = s.indexOf(ql, i + ql.length())) {
                if (first < 0) first = i;
                try { findTags.add(pane.getHighlighter().addHighlight(i, i + ql.length(), new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(255, 214, 0, 150)))); }
                catch (BadLocationException ignored) { }
            }
            if (first >= 0) {
                int f = first;
                SwingUtilities.invokeLater(() -> {
                    try {
                        Rectangle r = pane.modelToView2D(f).getBounds();
                        r.height += 40;
                        pane.scrollRectToVisible(r);
                    } catch (Exception ignored) { }
                });
            }
        }

        void clearFind() {
            for (Object t : findTags) pane.getHighlighter().removeHighlight(t);
            findTags.clear();
        }

        /** 링크 밑줄 */
        static final class UnderlinePainter implements javax.swing.text.Highlighter.HighlightPainter {
            final Color c;

            UnderlinePainter(Color c) { this.c = c; }

            public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent t) {
                try {
                    Rectangle a = t.modelToView2D(p0).getBounds(), b = t.modelToView2D(p1).getBounds();
                    g.setColor(c);
                    if (a.y == b.y) { g.fillRect(a.x, a.y + a.height - 2, b.x - a.x, 1); return; }
                    // 여러 줄에 걸친 주소
                    Rectangle area = bounds.getBounds();
                    g.fillRect(a.x, a.y + a.height - 2, area.x + area.width - a.x, 1);
                    for (int y = a.y + a.height; y < b.y; y += a.height) g.fillRect(area.x, y + a.height - 2, area.width, 1);
                    g.fillRect(area.x, b.y + b.height - 2, b.x - area.x, 1);
                } catch (BadLocationException ignored) { }
            }
        }

        // ---------- 잠금
        /** 이 창에서 "내용 보기"를 눌렀는지 (창을 닫으면 다시 가려짐) */
        boolean revealed;
        JPanel lockCover;

        /** 잠긴 메모는 글 대신 가림판을 보여 준다 */
        void applyLockView() {
            boolean cover = note.lock && (!revealed || note.unreadable());
            if (lockCover != null) content.remove(lockCover);
            content.remove(scroll);
            if (cover) {
                lockCover = buildLockCover();
                content.add(lockCover, BorderLayout.CENTER);
            } else {
                content.add(scroll, BorderLayout.CENTER);
            }
            formatBar.setVisible(!cover);
            content.revalidate();
            content.repaint();
        }

        JPanel buildLockCover() {
            Theme t = note.theme;
            JPanel p = new JPanel(new GridBagLayout());
            p.setOpaque(false);
            JPanel col = new JPanel();
            col.setOpaque(false);
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            JLabel ic = new JLabel(new Glyph("lock", 30));
            ic.setForeground(t.fg());
            ic.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(ic);
            col.add(Box.createVerticalStrut(8));
            JLabel l = new JLabel(note.unreadable() ? "이 PC에서 열 수 없는 잠긴 메모" : "잠긴 메모");
            l.setFont(BASE_FONT.deriveFont(Font.BOLD, 14f));
            l.setForeground(t.fg());
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(l);
            if (!note.unreadable()) {
                col.add(Box.createVerticalStrut(10));
                JButton show = pill(new JButton("내용 보기"));
                show.setAlignmentX(Component.CENTER_ALIGNMENT);
                show.addActionListener(e -> { revealed = true; applyLockView(); pane.requestFocusInWindow(); });
                col.add(show);
            } else {
                JLabel d = new JLabel("다른 PC나 다른 Windows 계정에서 잠근 메모예요");
                d.setFont(BASE_FONT.deriveFont(12f));
                d.setForeground(new Color(t.fg().getRed(), t.fg().getGreen(), t.fg().getBlue(), 150));
                d.setAlignmentX(Component.CENTER_ALIGNMENT);
                col.add(Box.createVerticalStrut(4));
                col.add(d);
            }
            p.add(col);
            return p;
        }

        /** 메뉴의 "알림 설정": 빠른 선택 + 날짜·시각 직접 고르기 */
        void askReminder() {
            java.time.LocalDateTime now = java.time.LocalDateTime.now().withSecond(0).withNano(0);
            java.time.LocalDateTime evening = now.toLocalDate().atTime(18, 0);
            if (!evening.isAfter(now.plusMinutes(5))) evening = evening.plusDays(1);
            java.time.LocalDateTime start = note.remindAt != null && note.remindAt > System.currentTimeMillis()
                    ? java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(note.remindAt), ZoneId.systemDefault()) : now.plusHours(1);
            SpinnerDateModel model = new SpinnerDateModel(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()), null, null, java.util.Calendar.MINUTE);
            JSpinner spin = new JSpinner(model);
            spin.setEditor(new JSpinner.DateEditor(spin, "yyyy. M. d.  a h:mm"));
            spin.setFont(BASE_FONT.deriveFont(13f));
            spin.setMaximumSize(new Dimension(240, 32));
            spin.setAlignmentX(CENTER_ALIGNMENT);
            JPanel quick = new JPanel(new GridLayout(2, 2, 6, 6));
            quick.setOpaque(false);
            Object[][] picks = {{"30분 뒤", now.plusMinutes(30)}, {"1시간 뒤", now.plusHours(1)},
                    {evening.toLocalDate().equals(now.toLocalDate()) ? "오늘 저녁 6시" : "내일 저녁 6시", evening},
                    {"내일 아침 9시", now.toLocalDate().plusDays(1).atTime(9, 0)}};
            for (Object[] p : picks) {
                JButton b = pill(new JButton((String) p[0]));
                b.setFont(BASE_FONT.deriveFont(12.5f));
                java.time.LocalDateTime at = (java.time.LocalDateTime) p[1];
                b.addActionListener(e -> model.setValue(Date.from(at.atZone(ZoneId.systemDefault()).toInstant())));
                quick.add(b);
            }
            quick.setMaximumSize(new Dimension(240, 70));
            quick.setAlignmentX(CENTER_ALIGNMENT);
            JPanel extra = new JPanel();
            extra.setLayout(new BoxLayout(extra, BoxLayout.Y_AXIS));
            extra.add(quick);
            extra.add(Box.createVerticalStrut(10));
            extra.add(spin);
            if (!Ask.show(this, Ask.Kind.BELL, "언제 알려 드릴까요?", "그 시각에 이 메모를 앞으로 띄워요.", "알림 설정", "취소", extra)) return;
            long at = ((Date) model.getValue()).getTime();
            if (at <= System.currentTimeMillis()) {
                Ask.notice(this, Ask.Kind.WARN, "지난 시각이에요", "지금보다 뒤의 시각을 골라 주세요.");
                return;
            }
            app.setReminder(note, at);
        }

        /** 메뉴의 "메모 잠그기 / 잠금 풀기" */
        void toggleLock() {
            if (note.unreadable()) return;
            if (!note.lock) {
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                byte[] k = Crypto.key(app.store.dir);                   // 처음이면 키를 만든다 (1초 안쪽)
                setCursor(Cursor.getDefaultCursor());
                if (k == null) {
                    Ask.notice(this, Ask.Kind.WARN, "메모를 잠그지 못했어요", "이 PC에서 Windows 암호화 기능을 쓸 수 없어요.");
                    return;
                }
                flush();
                note.lock = true;
                revealed = false;
            } else {
                if (!Ask.confirm(this, Ask.Kind.QUESTION, "잠금을 풀까요?", "이 메모는 암호화되지 않은 채로 저장돼요.", "잠금 풀기", "취소")) return;
                flush();
                note.lock = false;
            }
            note.updatedAt = nowTicks();
            app.store.save(note);
            applyLockView();
            app.noteChanged();
        }

        void applyFormatOrder() {
            List<String> order = new ArrayList<>();
            for (String id : app.store.settings.getProperty("formatOrder", "").split(","))
                if (formatButtons.containsKey(id) && !order.contains(id)) order.add(id);
            for (String id : formatButtons.keySet()) if (!order.contains(id)) order.add(id);
            formatBar.removeAll();
            for (String id : order) formatBar.add(formatButtons.get(id));
            formatBar.revalidate();
            formatBar.repaint();
        }

        // ---------- 테마
        void applyTheme() {
            Theme t = note.theme;
            int garland = decorated() ? GARLAND_H : 0;              // 계절 스타일: 장식 자리만큼 띠를 키워 글을 가리지 않게
            header.setPreferredSize(new Dimension(10, HEADER_H + garland));
            header.setBorder(new EmptyBorder(0, 0, garland, 0));
            header.revalidate();
            pane.setBackground(t.body);                            // 체크박스·아이콘 배경색 참조용 (불투명 아님)
            pane.setForeground(t.fg());
            pane.setCaretColor(t.accent);
            pane.putClientProperty("accent", t.accent);
            Color sel = !cute() ? (t.dark ? new Color(0x2B5797) : new Color(0xB5D5FF))
                    : t.dark ? new Color(0x7A5C8F) : new Color(t.accent.getRed(), t.accent.getGreen(), t.accent.getBlue(), 70);
            pane.setSelectionColor(sel);
            pane.setSelectedTextColor(t.fg());
            for (FlatButton b : flatButtons) {
                b.setForeground(t.fg());
                b.hoverColor = t.hover();
            }
            scroll.getVerticalScrollBar().setUI(new CuteScrollBarUI());       // 테마 전환(updateUI) 뒤에도 메모 색 스크롤바 유지
            scroll.getVerticalScrollBar().putClientProperty("accent", t.header.darker());
            setIconImage(makeIcon());
            repaint();
        }

        void setTheme(Theme t) {
            note.theme = t;
            app.store.set("defaultNoteColor", t.name());
            note.updatedAt = nowTicks();
            app.store.save(note);
            applyTheme();
            app.noteChanged();
        }

        static Dimension minSizeFor(NoteShape s) { return new Dimension(s.minW + 2 * M, s.minH + 2 * M); }

        /** 스티커 모양 바꾸기. 모양에 비해 창이 작으면 키움 */
        void setNoteShape(NoteShape s) {
            note.shape = s;
            Dimension min = minSizeFor(s);
            setMinimumSize(min);
            if (getWidth() < min.width || getHeight() < min.height)
                setSize(Math.max(getWidth(), min.width), Math.max(getHeight(), min.height));
            if (popup != null && popup.isVisible()) placePopup();     // 메모가 커져도 메뉴가 가리지 않게
            note.updatedAt = nowTicks();
            app.store.save(note);
            content.revalidate();
            updateWindowShape();
            repaint();
            app.noteChanged();
        }

        /** 투명 창을 못 쓰는 환경에서는 창 자체를 모양대로 자른다 */
        void updateWindowShape() {
            if (TRANSLUCENT) return;
            setShape(note.shape.outline(M, M, getWidth() - 2 * M, getHeight() - 2 * M));
        }

        void showMenu(Component anchor) {
            if (popup != null && popup.isVisible()) { popup.dispose(); return; }
            popup = new MenuPopup();
            popupAnchor = anchor;
            placePopup();
            popup.setVisible(true);
            popup.toFront();
            popup.getContentPane().requestFocusInWindow();
        }

        Component popupAnchor;

        /**
         * 메뉴를 ⋯ 버튼 높이에서 모양 바로 옆(오른쪽, 자리가 없으면 왼쪽)에 붙이고 꼬리로 버튼을 가리킨다.
         * 메모를 가리지 않아 색·모양을 바꾸는 모습이 그대로 보인다. 양옆 모두 자리가 없으면 메모 아래/위.
         */
        void placePopup() {
            if (popup == null) return;
            Rectangle scr = getGraphicsConfiguration().getBounds();
            Rectangle me = getBounds();
            int pw = popup.getWidth(), ph = popup.getHeight(), sh = MenuPopup.SH, gap = 2;
            Component a = popupAnchor != null && popupAnchor.isShowing() ? popupAnchor : header;
            Point ap = SwingUtilities.convertPoint(a, 0, a.getHeight() / 2, content);
            // 버튼 높이에서 모양의 오른쪽·왼쪽 끝
            var b = content.box();
            java.awt.geom.Area area = new java.awt.geom.Area(note.shape.outline(b.getX(), b.getY(), b.getWidth(), b.getHeight()));
            int right = (int) b.getMaxX(), left = (int) b.getX();
            while (right > ap.x && !area.contains(right, ap.y)) right--;
            while (left < ap.x && !area.contains(left, ap.y)) left++;
            int screenY = me.y + ap.y;
            int x = me.x + right + gap - sh + MenuPopup.TAIL, side = -1;          // 오른쪽: 꼬리가 왼쪽으로
            if (x + pw - sh > scr.x + scr.width) { x = me.x + left - gap + sh - MenuPopup.TAIL - pw; side = 1; }
            if (x + sh < scr.x) {                                                // 양옆 모두 자리가 없으면 아래/위
                popup.tailSide = 0;
                x = Math.max(scr.x, Math.min(me.x + (me.width - pw) / 2, scr.x + scr.width - pw));
                int y = me.y + me.height - M;
                if (y + ph > scr.y + scr.height) y = Math.max(scr.y, me.y + M - ph);
                popup.setLocation(x, y);
                popup.repaint();
                return;
            }
            int y = Math.max(scr.y, Math.min(screenY - sh - 26, scr.y + scr.height - ph));   // 첫 줄(색깔)이 버튼 높이 근처
            popup.tailSide = side;
            popup.tailY = screenY - y;
            popup.setLocation(x, y);
            popup.repaint();
        }

        // ---------- 스티커 모양을 그리는 바탕
        /** 그림자 + 모양 + 위쪽 색 띠 + 바느질 점선. 안쪽 여백(insets)이 모양마다 달라 글이 모양 안에 들어감 */
        final class ShapePanel extends JPanel {
            ShapePanel() {
                super(new BorderLayout());
                setOpaque(false);
            }

            java.awt.geom.Rectangle2D box() {
                return new java.awt.geom.Rectangle2D.Double(M, M - 3, Math.max(1, getWidth() - 2 * M), Math.max(1, getHeight() - 2 * M));
            }

            Shape ring;
            String ringKey;
            java.awt.geom.Area area;
            String areaKey;

            /** 아래가 좁은 모양(원·하트·별): 서식 줄을 그 높이의 모양 폭에 맞게 좁혀 두 줄로 감싼다 */
            @Override
            public void doLayout() {
                NoteShape s = note.shape;
                int side = 0;
                if ((s == NoteShape.Round || s == NoteShape.Heart || s == NoteShape.Star) && getWidth() > 0) {
                    String key = s + "/" + getWidth() + "x" + getHeight();
                    if (!key.equals(areaKey)) {
                        var b = box();
                        area = new java.awt.geom.Area(s.outline(b.getX(), b.getY(), b.getWidth(), b.getHeight()));
                        areaKey = key;
                    }
                    Insets in = getInsets();
                    int inner = getWidth() - in.left - in.right;
                    for (int pass = 0; pass < 3; pass++) {               // 줄 수가 바뀌면 높이도 바뀌므로 몇 번 맞춘다
                        setBarSide(side);
                        int barH = formatBar.getPreferredSize().height;
                        int y1 = getHeight() - in.bottom, y0 = y1 - barH;
                        int lo = 0, hi = Math.max(0, (inner - 4 * 38) / 2);     // 한 줄에 버튼 4개는 들어가게
                        while (lo < hi) {
                            int m = (lo + hi) / 2;
                            if (area.contains(in.left + m, y0, inner - 2 * m, barH)) hi = m; else lo = m + 1;
                        }
                        if (lo == side) break;
                        side = lo;
                    }
                }
                setBarSide(side);
                super.doLayout();
            }

            /** 같은 값이면 테두리를 다시 넣지 않는다 (setBorder 는 다시 배치를 불러 무한 반복이 됨) */
            void setBarSide(int side) {
                Insets cur = formatBar.getBorder() != null ? formatBar.getBorder().getBorderInsets(formatBar) : null;
                if (cur != null && cur.left == side && cur.right == side) return;
                if (cur == null && side == 0) return;
                formatBar.setBorder(new EmptyBorder(0, side, 0, side));
            }

            /** 모양 테두리 안팎 RING/2 px 띠: 여기에 마우스를 대면 크기 조절 */
            Shape edgeRing() {
                String key = note.shape + "/" + getWidth() + "x" + getHeight();
                if (!key.equals(ringKey)) {
                    var b = box();
                    ring = new BasicStroke(RING).createStrokedShape(note.shape.outline(b.getX(), b.getY(), b.getWidth(), b.getHeight()));
                    ringKey = key;
                }
                return ring;
            }

            @Override
            public Insets getInsets() {
                NoteShape s = note.shape;
                var b = box();
                int pad = s == NoteShape.Square || s == NoteShape.Bubble ? 6 : 2;
                return new Insets((int) (b.getY() + b.getHeight() * s.top) + pad, (int) (b.getX() + b.getWidth() * s.left) + pad,
                        (int) (getHeight() - b.getMaxY() + b.getHeight() * s.bottom) + pad, (int) (getWidth() - b.getMaxX() + b.getWidth() * s.right) + pad);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Theme t = note.theme;
                var b = box();
                Shape sh = note.shape.outline(b.getX(), b.getY(), b.getWidth(), b.getHeight());
                if (TRANSLUCENT) {
                    // 가장자리 띠는 거의 투명하게 칠해 마우스로 잡을 수 있게 (크기 조절)
                    java.awt.geom.Area band = new java.awt.geom.Area(new Rectangle(0, 0, getWidth(), getHeight()));
                    band.subtract(new java.awt.geom.Area(new Rectangle(EDGE, EDGE, getWidth() - 2 * EDGE, getHeight() - 2 * EDGE)));
                    g2.setColor(new Color(0, 0, 0, 1));
                    g2.fill(band);
                    g2.fill(edgeRing());                              // 모양 바로 바깥도 마우스를 받게
                    Shape shadow = java.awt.geom.AffineTransform.getTranslateInstance(0, 4).createTransformedShape(sh);
                    for (int i = 7; i >= 1; i--) {                     // 몽글한 그림자
                        g2.setColor(new Color(120, 80, 60, 6));
                        g2.setStroke(new BasicStroke(i * 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.draw(shadow);
                    }
                }
                g2.setColor(t.body);
                g2.fill(sh);
                Shape oldClip = g2.getClip();
                g2.clip(sh);
                int bandBottom = getInsets().top + header.getPreferredSize().height - 2;
                g2.setColor(t.header);
                g2.fillRect(0, 0, getWidth(), bandBottom);
                if (decorated()) {
                    Deco.pattern(g2, STYLE, t, note.id.hashCode(), getWidth(), bandBottom, getHeight());
                    Deco.band(g2, STYLE, t, getWidth(), bandBottom);
                } else if (cute()) {
                    // 색 띠 아래 바느질 점선
                    g2.setColor(new Color(255, 255, 255, t.dark ? 70 : 200));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{1f, 6f}, 0f));
                    g2.drawLine(0, bandBottom - 5, getWidth(), bandBottom - 5);
                }
                if (cute()) {
                    // 위쪽 반짝임
                    g2.setPaint(new GradientPaint(0, (float) b.getY(), new Color(255, 255, 255, t.dark ? 25 : 90), 0, (float) (b.getY() + b.getHeight() * .5), new Color(255, 255, 255, 0)));
                    g2.fill(sh);
                }
                g2.setClip(oldClip);
                g2.setColor(cute() ? new Color(t.header.darker().getRed(), t.header.darker().getGreen(), t.header.darker().getBlue(), 140)
                        : new Color(0, 0, 0, t.dark ? 90 : 45));
                g2.setStroke(new BasicStroke(1.3f));
                g2.draw(sh);
                g2.dispose();
            }
        }

        // ---------- 색깔·모양·메뉴 팝업
        /** ⋯ 를 누르면 뜨는 둥근 팝업. 바깥을 누르면 닫힘 */
        final class MenuPopup extends JWindow {
            MenuPopup() {
                super(NoteWindow.this);
                Theme t = note.theme;
                if (TRANSLUCENT) {
                    setBackground(new Color(0, 0, 0, 0));
                    // FlatLaf 는 JWindow 의 루트 패널 배경을 회색으로 칠하므로 투명하게 (카드 바깥·그림자 자리)
                    getRootPane().setOpaque(false);
                    getRootPane().setBackground(new Color(0, 0, 0, 0));
                    getLayeredPane().setOpaque(false);
                }
                JPanel root = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        Shape card = cardShape(getWidth(), getHeight());
                        if (TRANSLUCENT) g2.drawImage(shadow(card, getWidth(), getHeight()), 0, 0, null);
                        g2.setColor(t.menuBg());
                        g2.fill(card);
                        g2.setColor(cute() ? new Color(t.accent.getRed(), t.accent.getGreen(), t.accent.getBlue(), 70)
                                : new Color(0, 0, 0, t.dark ? 110 : 30));
                        g2.setStroke(new BasicStroke(1f));
                        g2.draw(card);
                        g2.dispose();
                    }
                };
                root.setOpaque(!TRANSLUCENT);
                root.setBackground(t.menuBg());
                root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
                root.setBorder(new EmptyBorder(SH + 10, SH + 12, SH + 8, SH + 12));
                root.setFocusable(true);

                root.add(sectionLabel("색깔", t));
                JPanel colors = new JPanel(new GridLayout(1, Theme.values().length, 6, 0));
                colors.setOpaque(false);
                for (Theme th : Theme.values()) colors.add(new Swatch(th));
                colors.setAlignmentX(LEFT_ALIGNMENT);
                root.add(colors);
                root.add(Box.createVerticalStrut(10));
                root.add(sectionLabel("스티커 모양", t));
                JPanel shapes = new JPanel(new GridLayout(1, NoteShape.values().length, 6, 0));
                shapes.setOpaque(false);
                for (NoteShape s : NoteShape.values()) shapes.add(new ShapeChip(s));
                shapes.setAlignmentX(LEFT_ALIGNMENT);
                root.add(shapes);
                root.add(Box.createVerticalStrut(10));
                FlatButton top = item("pin", "항상 위에 표시", () -> {
                    note.onTop = !note.onTop;
                    setAlwaysOnTop(note.onTop);
                    app.store.save(note);
                    dispose();
                });
                top.active = note.onTop;
                root.add(top);
                root.add(item("list", "메모 목록", () -> { dispose(); app.showList(); }));
                if (note.remindAt == null) {
                    root.add(item("bell", "알림 설정", () -> { dispose(); askReminder(); }));
                } else {
                    FlatButton bell = item("bell", "알림 · " + App.remindText(note.remindAt), () -> { dispose(); askReminder(); });
                    bell.active = true;
                    root.add(bell);
                    root.add(item("close", "알림 끄기", () -> { dispose(); app.setReminder(note, null); }));
                }
                if (!note.unreadable())
                    root.add(item("lock", note.lock ? "잠금 풀기" : "메모 잠그기", () -> { dispose(); toggleLock(); }));
                root.add(item("trash", "메모 삭제", () -> { dispose(); app.deleteNote(note, NoteWindow.this); }));

                setContentPane(root);
                pack();
                setSize(Math.max(getWidth(), 300), getHeight());
                addWindowFocusListener(new WindowAdapter() {
                    public void windowLostFocus(WindowEvent e) { dispose(); }
                });
                NoteWindow.bind(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), root.getActionMap(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose);
            }

            /** 그림자 여백, 꼬리 크기. 꼬리는 메모 쪽(왼쪽/오른쪽)으로 나와 ⋯ 버튼을 가리킨다 */
            static final int SH = 14, TAIL = 9;
            int tailSide;                                            // -1 왼쪽, 1 오른쪽, 0 없음
            int tailY = 40;                                          // 창 안에서 꼬리 끝의 y
            java.awt.image.BufferedImage shadowImg;
            String shadowKey;

            Shape cardShape(int w, int h) {
                double arc = cute() ? 22 : 12;
                java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(SH, SH, w - 2 * SH - 1, h - 2 * SH - 1, arc, arc));
                if (tailSide != 0) {
                    double ty = Math.max(SH + arc / 2 + TAIL, Math.min(h - SH - arc / 2 - TAIL, tailY));
                    double base = tailSide < 0 ? SH + 1 : w - SH - 2, tip = tailSide < 0 ? SH - TAIL + 2 : w - SH + TAIL - 3;
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    p.moveTo(base, ty - TAIL);
                    p.quadTo(base, ty - 2, tip, ty);                   // 살짝 휜 말풍선 꼬리
                    p.quadTo(base, ty + 2, base, ty + TAIL);
                    p.closePath();
                    a.add(new java.awt.geom.Area(p));
                }
                return a;
            }

            /** 부드럽게 번진 그림자 (크기·꼬리가 같으면 다시 만들지 않음) */
            java.awt.image.BufferedImage shadow(Shape card, int w, int h) {
                String key = w + "x" + h + "/" + tailSide + "/" + tailY;
                if (!key.equals(shadowKey)) {
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = img.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(0, 0, 0, note.theme.dark ? 120 : 70));
                    g.fill(java.awt.geom.AffineTransform.getTranslateInstance(0, 3).createTransformedShape(card));
                    g.dispose();
                    int r = 6;
                    float[] k = new float[(2 * r + 1) * (2 * r + 1)];
                    java.util.Arrays.fill(k, 1f / k.length);
                    java.awt.image.ConvolveOp blur = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(2 * r + 1, 2 * r + 1, k),
                            java.awt.image.ConvolveOp.EDGE_NO_OP, null);
                    shadowImg = blur.filter(img, null);
                    shadowKey = key;
                }
                return shadowImg;
            }

            JLabel sectionLabel(String text, Theme t) {
                JLabel l = new JLabel(text);
                l.setFont(BASE_FONT.deriveFont(Font.BOLD, 12f));
                l.setForeground(new Color(t.menuFg().getRed(), t.menuFg().getGreen(), t.menuFg().getBlue(), 170));
                l.setBorder(new EmptyBorder(0, 2, 6, 0));
                l.setAlignmentX(LEFT_ALIGNMENT);
                return l;
            }

            FlatButton item(String kind, String text, Runnable r) {
                Theme t = note.theme;
                FlatButton b = new FlatButton(text, null);
                b.setIcon(new Glyph(kind, 16));
                b.setIconTextGap(10);
                b.setHorizontalAlignment(SwingConstants.LEFT);
                b.setFont(BASE_FONT.deriveFont(13f));
                b.setMargin(new Insets(7, 10, 7, 10));
                b.setAlignmentX(LEFT_ALIGNMENT);
                b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                b.setForeground(t.menuFg());
                b.hoverColor = t.menuHover();
                b.addActionListener(e -> r.run());
                return b;
            }
        }

        /** 모양 선택 칩: 지금 색으로 채운 작은 모양 */
        final class ShapeChip extends JComponent {
            final NoteShape shape;

            ShapeChip(NoteShape shape) {
                this.shape = shape;
                setToolTipText(shape.label);
                setPreferredSize(new Dimension(34, 34));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        setNoteShape(ShapeChip.this.shape);
                        if (popup != null) popup.repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Theme t = note.theme;
                boolean sel = note.shape == shape;
                if (sel) {
                    g2.setColor(t.menuHover());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                }
                int s = Math.min(getWidth(), getHeight()) - 10;
                Shape o = shape.outline((getWidth() - s) / 2.0, (getHeight() - s) / 2.0, s, s);
                g2.setColor(t.header);
                g2.fill(o);
                g2.setColor(sel ? t.accent : new Color(t.menuFg().getRed(), t.menuFg().getGreen(), t.menuFg().getBlue(), 120));
                g2.setStroke(new BasicStroke(sel ? 2f : 1.3f));
                g2.draw(o);
                g2.dispose();
            }
        }

        // ---------- 저장
        void textChanged() {
            if (loading) return;
            dirtyText = true;
            saveTimer.restart();
            updateFormatButtons();
            clearFind();
            if (linkTimer != null) linkTimer.restart();
        }

        void boundsChanged() {
            if (!isShowing()) return;
            note.bounds = getBounds();
            saveTimer.restart();
        }

        void flush() {
            saveTimer.stop();
            if (dirtyText) {
                note.text = Markup.serialize(doc);
                note.updatedAt = nowTicks();
                dirtyText = false;
                app.store.save(note);
                app.noteChanged();
            } else {
                app.store.save(note);
            }
        }

        // ---------- 서식
        void toggle(Object key) {
            int s = pane.getSelectionStart(), e = pane.getSelectionEnd();
            if (s == e) {
                MutableAttributeSet ia = pane.getInputAttributes();
                ia.addAttribute(key, !Boolean.TRUE.equals(ia.getAttribute(key)));
            } else {
                boolean all = true;
                for (int i = s; i < e; i++) {
                    if (!Boolean.TRUE.equals(doc.getCharacterElement(i).getAttributes().getAttribute(key))) { all = false; break; }
                }
                SimpleAttributeSet a = new SimpleAttributeSet();
                a.addAttribute(key, !all);
                doc.setCharacterAttributes(s, e - s, a, false);
            }
            updateFormatButtons();
        }

        /** 문단 글 (줄바꿈 제외) */
        String paraText(Element para) throws BadLocationException {
            int ps = para.getStartOffset();
            return doc.getText(ps, Math.min(para.getEndOffset() - 1, doc.getLength()) - ps);
        }

        int[] selectedParas() {
            Element root = doc.getDefaultRootElement();
            int s = pane.getSelectionStart(), e = pane.getSelectionEnd();
            return new int[]{root.getElementIndex(s), root.getElementIndex(Math.max(s, e - (e > s ? 1 : 0)))};
        }

        void toggleBullets() {
            Element root = doc.getDefaultRootElement();
            int[] range = selectedParas();
            try {
                boolean all = true;
                for (int p = range[0]; p <= range[1]; p++) {
                    if (Markup.todoState(doc, root.getElement(p).getStartOffset()) != null) continue;
                    if (Markup.bulletOf(paraText(root.getElement(p))) == null) { all = false; break; }
                }
                for (int p = range[1]; p >= range[0]; p--) {
                    int st = root.getElement(p).getStartOffset();
                    if (Markup.todoState(doc, st) != null) continue;   // 할 일 항목에는 글머리를 붙이지 않음
                    int[] bl = Markup.bulletOf(paraText(root.getElement(p)));
                    if (all && bl != null) doc.remove(st, bl[1]);
                    else if (!all && bl == null) doc.insertString(st, Markup.bulletPrefix(0), null);
                }
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }

        /** Tab(+1) / Shift+Tab(-1): 선택한 글머리·할 일 문단의 들여쓰기 단계 변경. 둘 다 아니면 Tab 은 탭 문자 입력 */
        void indentBullets(int delta) {
            Element root = doc.getDefaultRootElement();
            int[] range = selectedParas();
            boolean any = false;
            try {
                for (int p = range[1]; p >= range[0]; p--) {
                    Element para = root.getElement(p);
                    int st0 = para.getStartOffset();
                    Boolean state = Markup.todoState(doc, st0);
                    if (state != null) {                              // 할 일: 체크박스 아이콘의 단계만 바꿈
                        any = true;
                        int d = Markup.todoDepth(doc, st0);
                        int nd = Math.max(0, Math.min(Markup.MAX_DEPTH, d + delta));
                        if (nd == d) continue;
                        int caret = pane.getCaretPosition();
                        doc.remove(st0, 1);
                        doc.insertString(st0, " ", Markup.todoAttrs(state, nd));
                        if (caret > st0 && caret <= doc.getLength()) pane.setCaretPosition(caret);
                        continue;
                    }
                    int[] bl = Markup.bulletOf(paraText(para));
                    if (bl == null) continue;
                    any = true;
                    int nd = Math.max(0, Math.min(Markup.MAX_DEPTH, bl[0] + delta));
                    if (nd == bl[0]) continue;
                    int st = para.getStartOffset();
                    doc.remove(st, bl[1]);
                    doc.insertString(st, Markup.bulletPrefix(nd), null);
                }
                if (!any && delta > 0) pane.replaceSelection("\t");
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }

        // ---------- 할 일 목록
        /** 선택한 문단들을 할 일 항목으로 바꾸거나(모두 할 일이면) 해제 */
        void toggleTodoList() {
            Element root = doc.getDefaultRootElement();
            int s = pane.getSelectionStart(), e = pane.getSelectionEnd();
            int first = root.getElementIndex(s);
            int last = root.getElementIndex(Math.max(s, e - (e > s ? 1 : 0)));
            try {
                boolean all = true;
                for (int p = first; p <= last; p++) {
                    if (Markup.todoState(doc, root.getElement(p).getStartOffset()) == null) { all = false; break; }
                }
                for (int p = last; p >= first; p--) {
                    int st = root.getElement(p).getStartOffset();
                    Boolean state = Markup.todoState(doc, st);
                    if (all) {
                        doc.remove(st, 1);
                        if (Boolean.TRUE.equals(state)) clearStrike(st, root.getElement(p).getEndOffset() - 1);
                    } else if (state == null) {
                        int[] bl = Markup.bulletOf(paraText(root.getElement(p)));
                        if (bl != null) doc.remove(st, bl[1]);
                        doc.insertString(st, " ", Markup.todoAttrs(false));
                    }
                }
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }

        void clearStrike(int from, int to) {
            if (to <= from) return;
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setStrikeThrough(a, false);
            doc.setCharacterAttributes(from, to - from, a, false);
        }

        /** 클릭 위치가 체크박스면 그 문단 시작 오프셋, 아니면 -1 */
        int checkboxAt(Point pt) {
            int pos = pane.viewToModel2D(pt);
            if (pos < 0) return -1;
            int ps = doc.getParagraphElement(pos).getStartOffset();
            if (Markup.todoState(doc, ps) == null) return -1;
            try {
                java.awt.geom.Rectangle2D r = pane.modelToView2D(ps);
                if (r == null) return -1;
                double bx = r.getX() + Markup.todoDepth(doc, ps) * CheckIcon.INDENT_PX;
                boolean inX = pt.x >= bx - 2 && pt.x <= bx + CheckIcon.BOX + 3;
                boolean inY = pt.y >= r.getY() - 2 && pt.y <= r.getY() + Math.max(r.getHeight(), CheckIcon.BOX + 2) + 2;
                return inX && inY ? ps : -1;
            } catch (BadLocationException ex) {
                return -1;
            }
        }

        /**
         * 체크박스 토글: 완료하면 취소선을 긋고 같은 단계 형제들의 맨 아래로,
         * 해제하면 취소선을 지우고 완료된 형제들 바로 위로 옮긴다. 하위 항목(더 깊은 단계)은 함께 이동.
         */
        void toggleDone(int paraStart) {
            Element root = doc.getDefaultRootElement();
            int idx = root.getElementIndex(paraStart);
            List<String> lines = new ArrayList<>(Arrays.asList(Markup.serialize(doc).split("\n", -1)));
            if (idx >= lines.size() || Markup.todoOf(lines.get(idx)) == 0) return;
            boolean done = Markup.todoOf(lines.get(idx)) == 1;
            int d = Markup.todoDepthOf(lines.get(idx));

            int subEnd = idx + 1;                            // 하위 항목까지 [idx, subEnd)
            while (subEnd < lines.size() && Markup.todoOf(lines.get(subEnd)) > 0 && Markup.todoDepthOf(lines.get(subEnd)) > d) subEnd++;
            List<String> moved = new ArrayList<>(lines.subList(idx, subEnd));
            moved.set(0, Markup.withTodo(moved.get(0), done));
            lines.subList(idx, subEnd).clear();

            // 같은 부모 아래 형제 범위 [bs, be): 단계가 d 이상인 할 일 줄이 이어지는 구간
            int bs = idx, be = idx;
            while (bs > 0 && Markup.todoOf(lines.get(bs - 1)) > 0 && Markup.todoDepthOf(lines.get(bs - 1)) >= d) bs--;
            while (be < lines.size() && Markup.todoOf(lines.get(be)) > 0 && Markup.todoDepthOf(lines.get(be)) >= d) be++;
            int target = be;
            if (!done) {
                for (int i = bs; i < be; i++)
                    if (Markup.todoDepthOf(lines.get(i)) == d && Markup.todoOf(lines.get(i)) == 2) { target = i; break; }
            }
            lines.addAll(target, moved);

            loading = true;
            Markup.load(doc, String.join("\n", lines), this::mediaIcon);
            loading = false;
            undo.discardAllEdits();
            dirtyText = true;
            saveTimer.restart();
            app.noteChanged();
            int caret = root.getElement(Math.min(target, root.getElementCount() - 1)).getEndOffset() - 1;
            pane.setCaretPosition(Math.min(caret, doc.getLength()));
        }

        /** 캐럿이 체크박스 앞에 오면 글자가 체크박스 앞에 들어가므로 뒤로 옮김 */
        void keepCaretAfterCheckbox() {
            int dot = pane.getCaret().getDot();
            if (dot != pane.getCaret().getMark()) return;
            int ps = doc.getParagraphElement(dot).getStartOffset();
            if (dot == ps && Markup.todoState(doc, ps) != null) SwingUtilities.invokeLater(() -> {
                if (pane.getCaretPosition() == ps && Markup.todoState(doc, ps) != null) pane.setCaretPosition(ps + 1);
            });
        }

        /** Enter: 할 일/글머리 항목이면 다음 줄도 같은 항목으로, 빈 항목이면 목록에서 빠져나옴 */
        void insertBreak() {
            try {
                if (pane.getSelectionEnd() > pane.getSelectionStart()) pane.replaceSelection("");
                int pos = pane.getCaretPosition();
                Element para = doc.getParagraphElement(pos);
                int ps = para.getStartOffset(), pe = para.getEndOffset() - 1;
                if (Markup.todoState(doc, ps) != null) {
                    int d = Markup.todoDepth(doc, ps);
                    if (pe - ps <= 1) {                                // 빈 항목: 한 단계 내어쓰기, 0단계면 목록 끝
                        doc.remove(ps, 1);
                        if (d > 0) doc.insertString(ps, " ", Markup.todoAttrs(false, d - 1));
                        return;
                    }
                    doc.insertString(pos, "\n", null);
                    doc.insertString(pos + 1, " ", Markup.todoAttrs(false, d));
                    clearStrike(pos + 2, doc.getParagraphElement(pos + 1).getEndOffset() - 1);
                    pane.setCaretPosition(pos + 2);
                    return;
                }
                String text = doc.getText(ps, pe - ps);
                int[] bl = Markup.bulletOf(text);
                if (bl != null && pos >= ps + bl[1]) {
                    if (text.length() == bl[1]) {                       // 빈 항목: 한 단계 내어쓰기, 0단계면 목록 끝
                        doc.remove(ps, bl[1]);
                        if (bl[0] > 0) doc.insertString(ps, Markup.bulletPrefix(bl[0] - 1), null);
                        return;
                    }
                    doc.insertString(pos, "\n" + Markup.bulletPrefix(bl[0]), null);
                    return;
                }
                doc.insertString(pos, "\n", pane.getInputAttributes());
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }

        void updateFormatButtons() {
            AttributeSet a;
            int s = pane.getSelectionStart();
            if (pane.getSelectionStart() != pane.getSelectionEnd()) a = doc.getCharacterElement(s).getAttributes();
            else a = pane.getInputAttributes();
            bBold.active = StyleConstants.isBold(a);
            bItalic.active = StyleConstants.isItalic(a);
            bUnder.active = StyleConstants.isUnderline(a);
            bStrike.active = StyleConstants.isStrikeThrough(a);
            for (FlatButton b : List.of(bBold, bItalic, bUnder, bStrike)) b.repaint();
        }

        void bindKeys() {
            InputMap im = pane.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap am = pane.getActionMap();
            int m = InputEvent.CTRL_DOWN_MASK;
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_B, m), "bold", () -> toggle(StyleConstants.Bold));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_I, m), "italic", () -> toggle(StyleConstants.Italic));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_U, m), "underline", () -> toggle(StyleConstants.Underline));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_T, m), "strike", () -> toggle(StyleConstants.StrikeThrough));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_L, m | InputEvent.SHIFT_DOWN_MASK), "bullets", this::toggleBullets);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_C, m | InputEvent.SHIFT_DOWN_MASK), "todoList", this::toggleTodoList);
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "smartBreak", this::insertBreak);
            pane.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Set.of());
            pane.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Set.of());
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "indent", () -> indentBullets(1));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "outdent", () -> indentBullets(-1));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_N, m), "newNote", () -> app.newNote(this));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_D, m), "deleteNote", () -> app.deleteNote(note, this));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_W, m), "closeNote", () -> app.closeNote(note));
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Z, m), "undo", () -> { if (undo.canUndo()) undo.undo(); });
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_Y, m), "redo", () -> { if (undo.canRedo()) undo.redo(); });
            bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_F, m), "list", app::showList);
        }

        static void bind(InputMap im, ActionMap am, KeyStroke ks, String name, Runnable r) {
            im.put(ks, name);
            am.put(name, new AbstractAction() {
                public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }

        Image makeIcon() { return appIcon(32, note.theme); }

        // ---------- 색 견본
        final class Swatch extends JComponent {
            final Theme theme;

            Swatch(Theme theme) {
                this.theme = theme;
                setToolTipText(theme.label);
                setPreferredSize(new Dimension(32, 32));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        setTheme(Swatch.this.theme);
                        if (popup != null) popup.dispose();        // 색이 바뀌면 팝업 색도 바뀌므로 닫음
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {                  // 동글동글한 색 구슬
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = note.theme == theme;
                int d = Math.min(getWidth(), getHeight()) - 6, x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
                if (sel) {
                    g2.setColor(theme.accent);
                    g2.setStroke(new BasicStroke(2.2f));
                    g2.drawOval(x - 2, y - 2, d + 3, d + 3);
                }
                g2.setColor(theme.header);
                g2.fillOval(x, y, d, d);
                if (cute()) {
                    g2.setColor(new Color(255, 255, 255, 140));
                    g2.fillOval(x + d / 4, y + d / 6, d / 4, d / 5);           // 반짝 하이라이트
                }
                g2.setColor(new Color(0, 0, 0, 25));
                g2.setStroke(new BasicStroke(1f));
                g2.drawOval(x, y, d - 1, d - 1);
                if (sel) {
                    g2.setColor(theme.dark ? Color.WHITE : theme.accent.darker());
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g2.drawPolyline(new int[]{cx - 5, cx - 1, cx + 5}, new int[]{cy, cy + 4, cy - 4}, 3);
                }
                g2.dispose();
            }
        }

        // ---------- 테두리 크기 조절
        final class Resizer extends MouseAdapter {
            int edge;
            Point start;
            Rectangle startBounds;

            int edgeAt(Point p) {
                Component c = getContentPane();
                int e = 0;
                if (p.x < EDGE) e |= 1;
                if (p.x >= c.getWidth() - EDGE) e |= 2;
                if (p.y < EDGE) e |= 4;
                if (p.y >= c.getHeight() - EDGE) e |= 8;
                if (e != 0 || !content.edgeRing().contains(p)) return e;
                // 모양 테두리 근처: 가운데에서 본 방향(8방향)으로 크기 조절
                var b = content.box();
                double a = Math.toDegrees(Math.atan2(p.y - b.getCenterY(), p.x - b.getCenterX()));
                int oct = (int) Math.floorMod(Math.round(a / 45), 8);  // 0=오른쪽, 2=아래, 4=왼쪽, 6=위
                return new int[]{2, 10, 8, 9, 1, 5, 4, 6}[oct];
            }

            public void mouseMoved(MouseEvent e) {
                int ed = edgeAt(e.getPoint());
                int cur = switch (ed) {
                    case 1 -> Cursor.W_RESIZE_CURSOR;
                    case 2 -> Cursor.E_RESIZE_CURSOR;
                    case 4 -> Cursor.N_RESIZE_CURSOR;
                    case 8 -> Cursor.S_RESIZE_CURSOR;
                    case 5 -> Cursor.NW_RESIZE_CURSOR;
                    case 6 -> Cursor.NE_RESIZE_CURSOR;
                    case 9 -> Cursor.SW_RESIZE_CURSOR;
                    case 10 -> Cursor.SE_RESIZE_CURSOR;
                    default -> Cursor.DEFAULT_CURSOR;
                };
                getContentPane().setCursor(Cursor.getPredefinedCursor(cur));
            }

            public void mousePressed(MouseEvent e) {
                edge = edgeAt(e.getPoint());
                start = e.getLocationOnScreen();
                startBounds = getBounds();
            }

            public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                int dx = p.x - start.x, dy = p.y - start.y;
                if (edge == 0) {                                     // 모양의 빈 부분(하트 귀퉁이 등)을 잡으면 창 이동
                    setLocation(startBounds.x + dx, startBounds.y + dy);
                    return;
                }
                Rectangle r = new Rectangle(startBounds);
                Dimension min = getMinimumSize();
                if ((edge & 1) != 0) { r.width = Math.max(min.width, startBounds.width - dx); r.x = startBounds.x + startBounds.width - r.width; }
                if ((edge & 2) != 0) r.width = Math.max(min.width, startBounds.width + dx);
                if ((edge & 4) != 0) { r.height = Math.max(min.height, startBounds.height - dy); r.y = startBounds.y + startBounds.height - r.height; }
                if ((edge & 8) != 0) r.height = Math.max(min.height, startBounds.height + dy);
                setBounds(r);
                revalidate();
            }

            public void mouseExited(MouseEvent e) {
                getContentPane().setCursor(Cursor.getDefaultCursor());
            }
        }
    }

    // ===================================================================== 메모 목록 창
    static final class NotesListWindow extends JFrame {
        static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
        static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

        final App app;
        final JTextField search = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {                 // 알약 모양 검색창
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                int arc = cute() ? getHeight() : 8;                         // 아기자기: 알약 / 심플: 둥근 사각
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                Color line = cute() ? styleAccent() : new Color(0x8A8A8A);
                g2.setColor(isFocusOwner() ? line : new Color(line.getRed(), line.getGreen(), line.getBlue(), cute() ? 90 : 70));
                g2.setStroke(new BasicStroke(cute() ? 1.5f : 1f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc - 2, arc - 2);
                new Glyph("search", 16).paintIcon(this, g2, 12, (getHeight() - 16) / 2);
                g2.dispose();
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    Graphics2D g3 = (Graphics2D) g.create();
                    g3.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g3.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);   // 회색조에서 맑은 고딕 획 빠짐 방지
                    Color f = getForeground();
                    g3.setColor(new Color(f.getRed(), f.getGreen(), f.getBlue(), 110));
                    Insets in = getInsets();
                    g3.drawString("메모 찾기...", in.left + 2, in.top + g3.getFontMetrics().getAscent() + 1);
                    g3.dispose();
                }
            }
        };
        final JPanel cards = new JPanel();
        final JPanel top = new JPanel(new BorderLayout());
        final JLabel title = new JLabel("QuickMemo");
        final JScrollPane scroll;
        final List<FlatButton> buttons = new ArrayList<>();

        NotesListWindow(App app) {
            super("QuickMemo");
            this.app = app;
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setSize(380, 560);
            setLocationRelativeTo(null);

            setIconImage(appIcon(32, Theme.Yellow));
            title.setFont(BASE_FONT.deriveFont(Font.BOLD, 21f));
            title.setIconTextGap(6);
            FlatButton add = new FlatButton("", "새 메모 (Ctrl+N)");
            add.setIcon(new Glyph("plus", 20));
            add.setPreferredSize(new Dimension(40, 36));
            add.addActionListener(e -> app.newNote(this));
            FlatButton gear = new FlatButton("", "설정");
            gear.setIcon(new Glyph("gear", 20));
            gear.setPreferredSize(new Dimension(40, 36));
            gear.addActionListener(e -> showSettings());
            selectBtn.setIcon(new Glyph("trash", 20));                // 여러 개 골라 지우기
            selectBtn.setPreferredSize(new Dimension(40, 36));
            selectBtn.addActionListener(e -> setSelecting(!selecting));
            buttons.add(add);
            buttons.add(selectBtn);
            buttons.add(gear);
            JPanel tb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            tb.setOpaque(false);
            tb.add(add);
            tb.add(selectBtn);
            tb.add(gear);
            top.add(title, BorderLayout.WEST);
            top.add(tb, BorderLayout.EAST);
            top.setBorder(new EmptyBorder(12, 16, 8, 10));
            top.setOpaque(false);   // 기본 밝은 배경이 칠해지면 어둡게 모드에서 제목·버튼이 안 보임

            search.setFont(BASE_FONT);
            search.setOpaque(false);
            search.setBorder(new EmptyBorder(8, 34, 8, 14));
            search.addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { search.repaint(); }
                public void focusLost(FocusEvent e) { search.repaint(); }
            });
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { refresh(); }
                public void removeUpdate(DocumentEvent e) { refresh(); }
                public void changedUpdate(DocumentEvent e) { refresh(); }
            });
            JPanel sp = new JPanel(new BorderLayout());
            sp.setOpaque(false);
            sp.setBorder(new EmptyBorder(0, 16, 10, 16));
            sp.add(search);

            // 여러 개 선택 막대: [전체 선택]  N개 선택됨   [삭제] [취소]
            selectAll.setOpaque(false);
            selectAll.setFont(BASE_FONT.deriveFont(13f));
            selectAll.addActionListener(e -> {
                selected.clear();
                if (selectAll.isSelected()) for (Card c : visibleCards()) selected.add(c.note.id);
                selectionChanged();
            });
            selCount.setFont(BASE_FONT.deriveFont(12.5f));
            selCount.setBorder(new EmptyBorder(0, 10, 0, 0));
            JButton del = deleteSelected;
            del.putClientProperty("JButton.buttonType", "roundRect");
            del.setBackground(new Color(0xD64545));                  // 지우는 버튼은 빨갛게
            del.setForeground(Color.WHITE);
            del.putClientProperty("FlatLaf.style", "borderColor: #D64545; hoverBackground: #C23838; pressedBackground: #A92F2F; focusWidth: 0; innerFocusWidth: 0");
            del.addActionListener(e -> deleteSelection());
            JButton cancel = new JButton("취소");
            pill(cancel);
            for (JButton b : new JButton[]{del, cancel}) b.setMargin(new Insets(3, 12, 3, 12));   // 좁은 창에서도 선택 수가 잘리지 않게
            cancel.addActionListener(e -> setSelecting(false));
            JPanel selBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            selBtns.setOpaque(false);
            selBtns.add(del);
            selBtns.add(cancel);
            selectBar.setOpaque(false);
            selectBar.setBorder(new EmptyBorder(0, 16, 10, 16));
            selectBar.add(selectAll, BorderLayout.WEST);
            selectBar.add(selCount, BorderLayout.CENTER);
            selectBar.add(selBtns, BorderLayout.EAST);
            selectBar.setVisible(false);

            JPanel lower = new JPanel(new BorderLayout());
            lower.setOpaque(false);
            lower.add(sp, BorderLayout.NORTH);
            lower.add(selectBar, BorderLayout.SOUTH);
            JPanel north = new JPanel(new BorderLayout());
            north.setOpaque(false);
            north.add(top, BorderLayout.NORTH);
            north.add(lower, BorderLayout.SOUTH);

            cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
            cards.setBorder(new EmptyBorder(0, 16, 16, 16));
            JPanel holder = new TrackWidthPanel();   // 스크롤바가 생겨도 카드가 보이는 너비에 맞춰짐
            holder.add(cards, BorderLayout.NORTH);
            scroll = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(new EmptyBorder(0, 0, 0, 0));   // null 이면 테마를 바꿀 때 FlatLaf 가 기본 테두리(회색 선)를 다시 넣음
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getVerticalScrollBar().setUI(new CuteScrollBarUI());
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
            scroll.getVerticalScrollBar().putClientProperty("accent", PINK);

            JPanel root = new JPanel(new BorderLayout());
            root.add(north, BorderLayout.NORTH);
            root.add(scroll, BorderLayout.CENTER);
            setContentPane(root);

            JRootPane rp = getRootPane();
            NoteWindow.bind(rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), rp.getActionMap(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "newNote", () -> app.newNote(this));
            NoteWindow.bind(rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), rp.getActionMap(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "search", search::requestFocusInWindow);
            NoteWindow.bind(rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), rp.getActionMap(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "endSelect", () -> { if (selecting) setSelecting(false); });

            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    setVisible(false);
                    app.checkExit();
                }
            });
            applyMode();
        }

        Color bg() {
            boolean d = app.store.darkMode();
            return d ? STYLE.listDark : STYLE.listLight;
        }

        // ---------- 여러 개 선택해 삭제
        final FlatButton selectBtn = new FlatButton("", "여러 개 선택해 삭제");
        final JPanel selectBar = new JPanel(new BorderLayout());
        final JCheckBox selectAll = new JCheckBox("전체 선택");
        final JLabel selCount = new JLabel();
        final JButton deleteSelected = new JButton("삭제");
        boolean selecting;
        final Set<String> selected = new HashSet<>();

        List<Card> visibleCards() {
            List<Card> l = new ArrayList<>(favCards);
            l.addAll(normalCards);
            return l;
        }

        void setSelecting(boolean on) {
            selecting = on;
            selected.clear();
            selectAll.setSelected(false);
            selectBar.setVisible(on);
            selectBtn.active = on;
            selectBtn.repaint();
            for (Card c : cardCache.values()) c.selectModeChanged();
            selectionChanged();
            getContentPane().revalidate();
        }

        /** 선택 수·전체 선택 체크·삭제 버튼 상태를 맞춘다 */
        void selectionChanged() {
            List<Card> vis = visibleCards();
            selected.retainAll(cardCache.keySet());
            long n = vis.stream().filter(c -> selected.contains(c.note.id)).count();
            selCount.setText(n + "개 선택");
            selectAll.setSelected(n > 0 && n == vis.size());
            deleteSelected.setEnabled(n > 0);
            for (Card c : vis) c.repaint();
        }

        void deleteSelection() {
            List<Note> del = new ArrayList<>();
            for (Card c : visibleCards()) if (selected.contains(c.note.id)) del.add(c.note);
            if (del.isEmpty()) return;
            if (!Ask.confirm(this, Ask.Kind.DANGER, "메모 " + del.size() + "개를 삭제할까요?", "휴지통에서 " + App.TRASH_DAYS + "일 동안 되살릴 수 있어요.",
                    del.size() + "개 삭제", "취소")) return;
            app.deleteNotes(del);
            setSelecting(false);
        }

        Color fg() {
            boolean d = app.store.darkMode();
            return cute() ? (d ? new Color(0xFFF4F8) : new Color(0x5A4636)) : (d ? new Color(0xF0F0F0) : new Color(0x1F1F1F));
        }

        void applyMode() {
            styleGen++;
            Color bg = bg(), fg = fg();
            blendTitleBar(getRootPane(), bg, fg);
            getContentPane().setBackground(bg);
            for (Component c : List.of(scroll.getViewport(), (Component) scroll.getViewport().getView(), cards)) c.setBackground(bg);
            title.setForeground(fg);
            title.setIcon(STYLE.icon != null ? new Glyph(STYLE.icon, STYLE == Style.Cute ? 20 : 22) : null);
            for (FlatButton b : buttons) {
                b.setForeground(fg);
                b.setBackground(bg);
                b.hoverColor = app.store.darkMode() ? new Color(255, 255, 255, 40) : cute() ? styleAccent(STYLE == Style.Cute ? 60 : 45) : new Color(0, 0, 0, 28);
            }
            scroll.getVerticalScrollBar().setUI(new CuteScrollBarUI());
            scroll.getVerticalScrollBar().putClientProperty("accent", cute() ? styleAccent() : new Color(0xB0B0B0));
            search.setBackground(app.store.darkMode() ? (cute() ? new Color(0x37323F) : new Color(0x2D2D2D)) : Color.WHITE);
            search.setForeground(fg);
            search.setCaretColor(fg);
            selectAll.setForeground(fg);
            selCount.setForeground(fg);
            refresh();
            repaint();
        }

        /** 화면에 보이는 카드 순서 (즐겨찾기 / 일반). 끌기 중에는 이 목록을 바꿔 가며 다시 배치 */
        final List<Card> favCards = new ArrayList<>(), normalCards = new ArrayList<>();
        boolean searching;

        /** 디자인·밝기가 바뀔 때마다 1씩 늘어남. 카드는 이 값이 다르면 색만 다시 칠한다 */
        static int styleGen;
        /** 메모별 카드 재사용 (HTML 글·그림 썸네일을 매번 새로 만들지 않게) */
        final Map<String, Card> cardCache = new HashMap<>();

        void refresh() {
            if (cards == null) return;
            String q = search.getText().trim().toLowerCase();
            searching = !q.isEmpty();
            favCards.clear();
            normalCards.clear();
            Set<String> alive = new HashSet<>();
            for (Note n : app.activeNotes()) {
                alive.add(n.id);
                String stamp = n.updatedAt + "/" + n.favorite + "/" + n.shape + "/" + n.theme + "/" + n.open + "/" + n.lock + "/" + when(n.updatedAt)
                        + "/" + (n.remindAt == null ? "" : App.remindText(n.remindAt));
                Card c = cardCache.get(n.id);
                if (c == null || !stamp.equals(c.stamp)) {
                    c = new Card(n, n.locked() ? "" : Markup.plain(n.text));   // 잠긴 메모는 미리보기·검색에서 내용을 숨김
                    c.stamp = stamp;
                    cardCache.put(n.id, c);
                } else {
                    c.restyle();
                }
                c.body.setQuery(searching ? q : null);
                if (searching && !c.plain.toLowerCase().contains(q)) continue;
                (n.favorite ? favCards : normalCards).add(c);
            }
            cardCache.keySet().retainAll(alive);                      // 지운 메모의 카드는 버림
            layoutCards();
            if (selecting) selectionChanged();
        }

        void layoutCards() {
            cards.removeAll();
            boolean headers = !favCards.isEmpty();
            if (headers) cards.add(groupLabel("즐겨찾기", true));
            for (Card c : favCards) { cards.add(c); cards.add(Box.createVerticalStrut(10)); }
            if (headers && !normalCards.isEmpty()) cards.add(groupLabel("메모", false));
            for (Card c : normalCards) { cards.add(c); cards.add(Box.createVerticalStrut(10)); }
            if (favCards.isEmpty() && normalCards.isEmpty()) {
                JLabel empty = new JLabel(searching ? "검색 결과가 없습니다." : "메모가 없습니다. + 를 눌러 새 메모를 만드세요.");
                empty.setForeground(Color.GRAY);
                empty.setFont(BASE_FONT);
                empty.setBorder(new EmptyBorder(20, 4, 0, 0));
                cards.add(empty);
            }
            cards.revalidate();
            cards.repaint();
        }

        JLabel groupLabel(String text, boolean star) {
            JLabel l = new JLabel(text);
            if (star) l.setIcon(new Glyph("starFilled", 14));
            l.setIconTextGap(6);
            l.setFont(BASE_FONT.deriveFont(Font.BOLD, 12f));
            Color ac = styleAccent();
            l.setForeground(cute() ? (app.store.darkMode() ? ac.brighter() : ac.darker()) : (app.store.darkMode() ? new Color(0xBBBBBB) : new Color(0x666666)));
            l.setBorder(new EmptyBorder(4, 2, 6, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            return l;
        }

        static String when(long ticks) {
            ZonedDateTime t = Instant.ofEpochMilli(ticksToMillis(ticks)).atZone(ZoneId.systemDefault());
            return t.toLocalDate().equals(LocalDate.now()) ? t.format(TIME) : t.format(DATE);
        }

        SettingsDialog settings;

        /** 설정 창은 하나만. 열려 있어도 메모를 누르고 쓸 수 있게 모달이 아님 */
        void showSettings() {
            if (settings == null || !settings.isDisplayable()) settings = new SettingsDialog();
            settings.setVisible(true);
            settings.toFront();
        }

        /** 설정 창: 섹션 카드 + 미리보기 선택 + 토글. 바꾸면 바로 적용·저장 (확인 버튼 없음) */
        final class SettingsDialog extends JDialog {
            SettingsDialog() {
                super(NotesListWindow.this, "설정", false);
                setDefaultCloseOperation(DISPOSE_ON_CLOSE);
                build();
                setSize(480, 700);
                setLocationRelativeTo(NotesListWindow.this);
                NoteWindow.bind(getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), getRootPane().getActionMap(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose);
            }

            boolean dark() { return app.store.darkMode(); }
            Color accent() { return cute() ? styleAccent() : dark() ? new Color(0x4CC2FF) : new Color(0x0067C0); }
            // 디자인(스타일)마다 메모 목록과 같은 바탕색, 글자는 포인트 색이 살짝 섞인 색
            Color pageBg() { return dark() ? STYLE.listDark : STYLE.listLight; }
            Color cardBg() {
                if (!cute()) return dark() ? new Color(0x2B2B2B) : Color.WHITE;
                return dark() ? mix(STYLE.listDark, Color.WHITE, .06) : mix(STYLE.listLight, Color.WHITE, .7);
            }
            Color text() {
                Color base = dark() ? new Color(0xF0F0F0) : new Color(0x1B1B1B);
                return cute() ? mix(base, styleAccent(), dark() ? .08 : .18) : base;
            }
            Color sub() {
                Color base = dark() ? new Color(0xA8A8A8) : new Color(0x6B6B6B);
                return cute() ? mix(base, styleAccent(), dark() ? .12 : .22) : base;
            }
            /** 바탕보다 조금 진한 색 (아래 닫기 바, 선택 상자 바탕) */
            Color shade() { return dark() ? mix(pageBg(), Color.WHITE, .09) : mix(pageBg(), Color.BLACK, .05); }

            /** 색·스타일이 바뀌면 내용을 다시 만든다 */
            void build() {
                JPanel page = new TrackWidthPanel();               // 창 너비에 맞춰 배치
                page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
                page.setBackground(pageBg());
                blendTitleBar(getRootPane(), pageBg(), text());
                page.setBorder(new EmptyBorder(16, 18, 16, 18));

                // 디자인
                JPanel styles = new JPanel(new GridLayout(0, 4, 8, 8));
                styles.setOpaque(false);
                for (Style s : Style.values()) styles.add(new StyleCard(s, s.label, s.desc));
                Style season = Style.forSeason(LocalDate.now());
                page.add(section("디자인", styles, row("계절에 맞춰 자동 변경", "계절마다 추천 디자인으로 (지금: " + season.label + ")",
                        new ToggleSwitch(app.store.autoSeason(), on -> { app.setAutoSeason(on); rebuild(); }))));

                // 화면
                boolean sys = app.store.followSystemTheme();
                int[] sizes = {13, 14, 16, 18};
                int fs = app.store.fontSize(), fsIdx = 1;
                for (int k = 0; k < sizes.length; k++) if (sizes[k] == fs) fsIdx = k;
                page.add(section("화면 모드",
                        row("밝기", sys ? "Windows 설정을 따라가요" : "메모 목록과 창의 밝기",
                                new Segmented(new String[]{"밝게", "어둡게", "시스템"}, sys ? 2 : dark() ? 1 : 0, i -> {
                                    if (i == 2) { app.followSystemTheme(true); rebuild(); return; }
                                    app.followSystemTheme(false);
                                    if ((i == 1) != dark()) app.setDark(i == 1);
                                    rebuild();
                                })),
                        row("글자 크기", "메모 글자의 크기", new Segmented(new String[]{"작게", "보통", "크게", "더 크게"}, fsIdx,
                                i -> app.setFontSize(sizes[i])))));

                // 동작
                page.add(section("동작", row("삭제 전에 확인", "메모를 지울 때 한 번 더 묻기", new ToggleSwitch(app.store.confirmDelete(), on ->
                        app.store.set("confirmDelete", String.valueOf(on))))));

                // 실행
                boolean exe = Updater.installDir() != null;
                ToggleSwitch auto = new ToggleSwitch(app.autoStart(), on -> {
                    if (!app.setAutoStart(on)) Ask.notice(this, Ask.Kind.WARN, "자동 실행을 바꾸지 못했어요", exe ? null : "설치된 QuickMemo(exe)에서만 쓸 수 있어요.");
                    rebuild();
                });
                page.add(section("실행",
                        row("Windows 시작 시 실행", exe ? "로그인하면 QuickMemo를 켜요" : "설치된 QuickMemo(exe)에서만 쓸 수 있어요", auto),
                        row("알림 영역에 남기", "창을 모두 닫아도 작업 표시줄 옆 아이콘으로 계속 실행", new ToggleSwitch(app.trayEnabled(), on -> app.setTray(on))),
                        row("새 메모 단축키", "어디서든 Ctrl+Alt+M" + (app.hotkeyEnabled() && !app.hotkeyOn ? " (다른 프로그램이 쓰고 있어요)" : ""),
                                new ToggleSwitch(app.hotkeyEnabled(), on -> { app.setHotkey(on); rebuild(); }))));

                // 휴지통
                JButton trash = pill(new JButton("열기"));
                trash.addActionListener(e -> new TrashDialog().setVisible(true));
                page.add(section("휴지통", row("최근 삭제한 메모 " + app.trashedNotes().size() + "개",
                        App.TRASH_DAYS + "일이 지나면 완전히 지워져요", trash)));

                // 백업
                JButton exp = pill(new JButton("내보내기"));
                exp.addActionListener(e -> exportBackup());
                JButton res = pill(new JButton("가져오기"));
                res.addActionListener(e -> restoreBackup());
                JButton bdir = pill(new JButton("폴더 열기"));
                bdir.addActionListener(e -> {
                    try { Files.createDirectories(Backup.dir(app.store)); Desktop.getDesktop().open(Backup.dir(app.store).toFile()); } catch (Exception ignored) { }
                });
                page.add(section("백업",
                        row("자동 백업", "매일 한 번, 최근 " + Backup.KEEP + "개 보관", new ToggleSwitch(!"false".equals(app.store.settings.getProperty("autoBackup")), on -> {
                            app.store.set("autoBackup", String.valueOf(on));
                            if (on) Backup.daily(app.store);
                        })),
                        row("백업 폴더", "자동 백업 파일이 있는 곳", bdir),
                        row("내보내기", "모든 메모를 zip 파일 하나로 저장", exp),
                        row("백업에서 가져오기", "zip 파일의 메모를 합치기 (더 최근 것 유지)", res)));

                // 가져오기
                JButton imp = pill(new JButton("가져오기"));
                imp.setEnabled(StickyImport.available());
                imp.addActionListener(e -> app.importStickyNotes(this, true));
                page.add(section("가져오기", row("Windows 스티커 메모", StickyImport.available()
                        ? "이 PC의 스티커 메모를 QuickMemo로 복사" : "이 PC에서 스티커 메모를 찾지 못했습니다", imp)));

                // 정보
                JButton open = pill(new JButton("폴더 열기"));
                open.addActionListener(e -> { try { Desktop.getDesktop().open(app.store.dir.toFile()); } catch (Exception ignored) { } });
                boolean exeRun = Updater.installDir() != null;
                JButton upd = pill(new JButton("업데이트 확인"));
                upd.setEnabled(exeRun);
                upd.addActionListener(e -> app.checkUpdateNow(this));
                List<JComponent> info = new ArrayList<>();
                info.add(row("버전 " + VERSION, exeRun ? "새 버전은 자동으로 받아요" : "개발용 실행 (자동 업데이트 꺼짐)", upd));
                if (Installer.canInstall()) {                               // 압축을 푼 폴더에서 바로 실행 중
                    JButton inst = pill(new JButton("설치하기"));
                    inst.addActionListener(e -> app.installNow(this));
                    info.add(row("이 PC에 설치", "시작 메뉴에 등록하고 한곳에 보관해요", inst));
                }
                String where = app.store.dir.toString().replace(System.getProperty("user.home"), "~");
                JComponent whereRow = row("메모 저장 위치", where.length() > 30 ? "…" + where.substring(where.length() - 29) : where, open);   // 긴 경로는 앞을 줄임
                whereRow.setToolTipText(app.store.dir.toString());
                info.add(whereRow);
                page.add(section("정보", info.toArray(new JComponent[0])));

                JScrollPane sp = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                sp.setBorder(new EmptyBorder(0, 0, 0, 0));   // null 이면 테마를 바꿀 때 FlatLaf 가 기본 테두리(회색 선)를 다시 넣음
                sp.getViewport().setBackground(pageBg());
                sp.getVerticalScrollBar().setUnitIncrement(16);

                // 스크롤과 상관없이 항상 보이는 아래쪽 닫기 바
                JButton close = new JButton("닫기");
                close.addActionListener(e -> dispose());
                close.setPreferredSize(new Dimension(96, 32));
                JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                bottom.setBackground(dark() ? mix(pageBg(), Color.BLACK, .2) : mix(pageBg(), Color.BLACK, .035));
                bottom.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, dark() ? new Color(255, 255, 255, 20) : new Color(0, 0, 0, 20)),
                        new EmptyBorder(12, 18, 12, 18)));
                bottom.add(close);
                getRootPane().setDefaultButton(close);

                JPanel root = new JPanel(new BorderLayout());
                root.add(sp, BorderLayout.CENTER);
                root.add(bottom, BorderLayout.SOUTH);
                setContentPane(root);
            }

            void rebuild() {
                build();
                revalidate();
                repaint();
            }

            /** 제목 + 둥근 카드 */
            JComponent section(String title, JComponent... rows) {
                JPanel wrap = new JPanel();
                wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
                wrap.setOpaque(false);
                wrap.setAlignmentX(LEFT_ALIGNMENT);
                JLabel t = new JLabel(title);
                t.setFont(BASE_FONT.deriveFont(Font.BOLD, 13f));
                t.setForeground(text());
                t.setBorder(new EmptyBorder(6, 2, 6, 0));
                t.setAlignmentX(LEFT_ALIGNMENT);
                if (title != null) wrap.add(t);                          // 제목 없는 카드 (휴지통 줄)
                JPanel card = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        int arc = cute() ? 18 : 10;
                        g2.setColor(cardBg());
                        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                        g2.setColor(dark() ? new Color(255, 255, 255, 18) : new Color(0, 0, 0, 22));
                        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                        g2.dispose();
                    }
                };
                card.setOpaque(false);
                card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
                card.setBorder(new EmptyBorder(12, 14, 12, 14));
                card.setAlignmentX(LEFT_ALIGNMENT);
                for (int i = 0; i < rows.length; i++) {
                    if (i > 0) {
                        JSeparator s = new JSeparator();
                        s.setForeground(dark() ? new Color(255, 255, 255, 20) : new Color(0, 0, 0, 18));
                        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                        card.add(Box.createVerticalStrut(10));
                        card.add(s);
                        card.add(Box.createVerticalStrut(10));
                    }
                    rows[i].setAlignmentX(LEFT_ALIGNMENT);
                    card.add(rows[i]);
                }
                wrap.add(card);
                wrap.add(Box.createVerticalStrut(10));
                wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
                return wrap;
            }

            /** 왼쪽 제목·설명 + 오른쪽 조작 */
            JComponent row(String title, String desc, JComponent right) {
                JPanel r = new JPanel(new BorderLayout(12, 0));
                r.setOpaque(false);
                JPanel labels = new JPanel();
                labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
                labels.setOpaque(false);
                JLabel t = new JLabel(title);
                t.setForeground(text());
                JLabel d = new JLabel("<html><div style='width:190px'>" + esc(desc) + "</div></html>");
                d.setFont(BASE_FONT.deriveFont(11.5f));
                d.setForeground(sub());
                labels.add(t);
                labels.add(Box.createVerticalStrut(2));
                labels.add(d);
                r.add(labels, BorderLayout.CENTER);
                if (right != null) {
                    JPanel holder = new JPanel(new GridBagLayout());
                    holder.setOpaque(false);
                    holder.add(right);
                    r.add(holder, BorderLayout.EAST);
                }
                return r;
            }

            void exportBackup() {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("메모 내보내기");
                fc.setSelectedFile(new java.io.File("QuickMemo-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".zip"));
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("zip 파일", "zip"));
                if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
                Path out = fc.getSelectedFile().toPath();
                if (!out.getFileName().toString().toLowerCase().endsWith(".zip")) out = out.resolveSibling(out.getFileName() + ".zip");
                try {
                    for (NoteWindow w : app.windows.values()) w.flush();
                    Backup.write(app.store, out);
                    Ask.notice(this, Ask.Kind.INFO, "메모를 내보냈어요", out.getFileName().toString());
                } catch (Exception ex) {
                    Ask.notice(this, Ask.Kind.WARN, "내보내지 못했어요", ex.getMessage());
                }
            }

            void restoreBackup() {
                JFileChooser fc = new JFileChooser(Backup.dir(app.store).toFile());
                fc.setDialogTitle("백업에서 가져오기");
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("zip 파일", "zip"));
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                if (!Ask.confirm(this, Ask.Kind.QUESTION, "백업에서 메모를 가져올까요?",
                        "없는 메모는 추가하고, 같은 메모는 더 최근 것으로 바꿔요. 열려 있는 메모는 건너뛰어요.", "가져오기", "취소")) return;
                try {
                    for (NoteWindow w : app.windows.values()) w.flush();
                    Backup.Result r = Backup.restore(app, fc.getSelectedFile().toPath());
                    Ask.notice(this, Ask.Kind.INFO, "백업을 가져왔어요", "추가 " + r.added() + "개 · 바뀜 " + r.updated() + "개 · 그대로 " + r.skipped() + "개");
                    rebuild();
                } catch (Exception ex) {
                    Ask.notice(this, Ask.Kind.WARN, "가져오지 못했어요", ex.getMessage());
                }
            }

            /** 휴지통: 지운 메모를 되살리거나 완전히 지운다 */
            final class TrashDialog extends JDialog {
                TrashDialog() {
                    super(SettingsDialog.this, "휴지통", false);
                    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
                    build();
                    setSize(440, 560);
                    setLocationRelativeTo(SettingsDialog.this);
                    NoteWindow.bind(getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), getRootPane().getActionMap(),
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose);
                }

                void build() {
                    List<Note> items = app.trashedNotes();
                    blendTitleBar(getRootPane(), pageBg(), text());
                    JPanel page = new TrackWidthPanel();
                    page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
                    page.setBackground(pageBg());
                    page.setBorder(new EmptyBorder(14, 18, 16, 18));

                    JPanel head = new JPanel(new BorderLayout(8, 0));
                    head.setOpaque(false);
                    head.setAlignmentX(LEFT_ALIGNMENT);
                    JLabel t = new JLabel("휴지통 · " + items.size() + "개");
                    t.setFont(BASE_FONT.deriveFont(Font.BOLD, 15f));
                    t.setForeground(text());
                    head.add(t, BorderLayout.WEST);
                    JPanel acts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
                    acts.setOpaque(false);
                    JButton all = new JButton("모두 되살리기");
                    pill(all);
                    all.addActionListener(e -> { app.restoreNotes(items, null); rebuildAll(); });
                    JButton empty = new JButton("비우기");
                    pill(empty);
                    empty.setForeground(new Color(0xC43B3B));
                    empty.addActionListener(e -> {
                        if (Ask.confirm(this, Ask.Kind.DANGER, "휴지통을 비울까요?", "메모 " + items.size() + "개가 완전히 지워지고 되살릴 수 없어요.", "비우기", "취소")) {
                            app.purgeNotes(items);
                            rebuildAll();
                        }
                    });
                    all.setEnabled(!items.isEmpty());
                    empty.setEnabled(!items.isEmpty());
                    acts.add(all);
                    acts.add(empty);
                    head.add(acts, BorderLayout.EAST);
                    head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
                    page.add(head);
                    page.add(Box.createVerticalStrut(12));

                    if (items.isEmpty()) {
                        JLabel e = new JLabel("휴지통이 비어 있어요");
                        e.setForeground(sub());
                        e.setBorder(new EmptyBorder(30, 4, 0, 0));
                        page.add(e);
                    }
                    for (Note n : items) {
                        String first = Markup.plain(n.text).strip().lines().findFirst().orElse("");
                        if (n.locked()) first = "잠긴 메모";
                        if (first.isBlank()) first = "(빈 메모)";
                        if (first.length() > 40) first = first.substring(0, 40) + "…";
                        long days = Math.max(0, App.TRASH_DAYS - (nowTicks() - n.deletedAt) / App.DAY_TICKS);
                        JButton restore = new JButton("되살리기");
                        pill(restore);
                        restore.addActionListener(e -> { app.restoreNotes(List.of(n), null); rebuildAll(); });
                        FlatButton purge = new FlatButton("", "완전히 지우기");
                        purge.setIcon(new Glyph("trash", 16));
                        purge.setForeground(new Color(0xC43B3B));
                        purge.setPreferredSize(new Dimension(32, 28));
                        purge.addActionListener(e -> {
                            if (Ask.confirm(this, Ask.Kind.DANGER, "완전히 지울까요?", "이 메모는 되살릴 수 없어요.", "지우기", "취소")) {
                                app.purgeNotes(List.of(n));
                                rebuildAll();
                            }
                        });
                        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                        btns.setOpaque(false);
                        btns.add(restore);
                        btns.add(purge);
                        page.add(section(null, row(first, NotesListWindow.when(n.deletedAt) + " 삭제 · " + days + "일 뒤 완전 삭제", btns)));
                    }
                    JScrollPane sp = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                    sp.setBorder(new EmptyBorder(0, 0, 0, 0));   // null 이면 테마를 바꿀 때 FlatLaf 가 기본 테두리(회색 선)를 다시 넣음
                    sp.getViewport().setBackground(pageBg());
                    sp.getVerticalScrollBar().setUnitIncrement(16);
                    setContentPane(sp);
                }

                /** 휴지통과 설정(삭제한 메모 수)을 같이 새로 그림 */
                void rebuildAll() {
                    build();
                    revalidate();
                    repaint();
                    SettingsDialog.this.rebuild();
                }
            }

            /** 디자인 선택 카드: 작은 메모 미리보기 + 이름 */
            final class StyleCard extends JComponent {
                final Style style;
                final String name, desc;

                StyleCard(Style style, String name, String desc) {
                    this.style = style;
                    this.name = name;
                    this.desc = desc;
                    setPreferredSize(new Dimension(96, 124));
                    setToolTipText(name + " · " + desc);
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        public void mouseClicked(MouseEvent e) {
                            boolean auto = app.store.autoSeason();
                            if (STYLE == style && !auto) return;
                            app.store.set("uiStyle", style.name());
                            if (auto) app.store.set("autoSeason", "false");   // 직접 고르면 자동 변경은 끔
                            app.setStyle(style);
                            rebuild();
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);   // 회색조에서 맑은 고딕 획 빠짐 방지
                    boolean sel = STYLE == style;
                    int w = getWidth(), h = getHeight();
                    g2.setColor(dark() ? mix(cardBg(), Color.WHITE, .04) : mix(cardBg(), pageBg(), .5));
                    g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
                    g2.setColor(sel ? accent() : dark() ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 30));
                    g2.setStroke(new BasicStroke(sel ? 2f : 1f));
                    g2.drawRoundRect(1, 1, w - 3, h - 3, 12, 12);
                    // 미리보기 메모
                    int pw = 74, ph = 56, px = (w - pw) / 2, py = 12;
                    if (style == Style.Simple) {
                        g2.setColor(new Color(0xFFF7D1));
                        g2.fillRoundRect(px, py, pw, ph, 6, 6);
                        g2.setClip(new java.awt.geom.RoundRectangle2D.Float(px, py, pw, ph, 6, 6));
                        g2.setColor(new Color(0xFFF2AB));
                        g2.fillRect(px, py, pw, 12);
                        g2.setClip(null);
                        g2.setColor(new Color(0, 0, 0, 40));
                        g2.drawRoundRect(px, py, pw, ph, 6, 6);
                        g2.setColor(new Color(0x9A9A9A));
                        g2.fillRoundRect(px + 9, py + 22, 46, 4, 4, 4);
                        g2.fillRoundRect(px + 9, py + 32, 34, 4, 4, 4);
                    } else if (style.decorated()) {                      // 계절: 대표 색 + 띠 장식 + 아이콘
                        Object[] pal = Theme.palette(style)[style == Style.Summer || style == Style.Winter ? 4 : 2];
                        g2.setColor(new Color((Integer) pal[1]));
                        g2.fillRoundRect(px, py, pw, ph, 16, 16);
                        Shape clip = new java.awt.geom.RoundRectangle2D.Float(px, py, pw, ph, 16, 16);
                        g2.setClip(clip);
                        double sc = .6;
                        int bandH = 22;
                        g2.setColor(new Color((Integer) pal[2]));
                        g2.fillRect(px, py, pw, bandH);
                        Graphics2D gd = (Graphics2D) g2.create();
                        gd.translate(px, py);
                        gd.scale(sc, sc);
                        Deco.band(gd, style, Theme.Yellow, (int) (pw / sc) + 2, (int) (bandH / sc));
                        gd.dispose();
                        g2.setClip(null);
                        new Glyph(style.icon, 18).paintIcon(this, g2, px + pw - 23, py + ph - 21);
                        g2.setColor(new Color(0xC9A99A));
                        g2.fillRoundRect(px + 9, py + 30, 32, 4, 4, 4);
                        g2.fillRoundRect(px + 9, py + 40, 22, 4, 4, 4);
                    } else {
                        g2.setColor(new Color(0xFFE7EF));
                        g2.fillRoundRect(px, py, pw, ph, 16, 16);
                        g2.setClip(new java.awt.geom.RoundRectangle2D.Float(px, py, pw, ph, 16, 16));
                        g2.setColor(new Color(0xFFC7DA));
                        g2.fillRect(px, py, pw, 13);
                        g2.setClip(null);
                        g2.setColor(new Color(255, 160, 190, 220));
                        g2.fill(java.awt.geom.AffineTransform.getRotateInstance(-0.12, px + pw / 2.0, py)
                                .createTransformedShape(new java.awt.geom.Rectangle2D.Double(px + pw / 2.0 - 16, py - 5, 32, 10)));
                        g2.setColor(PINK);
                        g2.fill(NoteShape.Heart.outline(px + pw - 24, py + ph - 22, 14, 13));
                        g2.setColor(new Color(0xC9A99A));
                        g2.fillRoundRect(px + 9, py + 22, 40, 4, 4, 4);
                        g2.fillRoundRect(px + 9, py + 32, 30, 4, 4, 4);
                    }
                    // 이름·설명
                    g2.setFont(BASE_FONT.deriveFont(Font.BOLD, 13f));
                    g2.setColor(text());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(name, (w - fm.stringWidth(name)) / 2, py + ph + 22);
                    g2.setFont(BASE_FONT.deriveFont(11f));
                    g2.setColor(sub());
                    fm = g2.getFontMetrics();
                    g2.drawString(desc, (w - fm.stringWidth(desc)) / 2, py + ph + 39);
                    if (style == Style.forSeason(LocalDate.now())) {     // 지금 계절 추천 표시
                        g2.setFont(BASE_FONT.deriveFont(Font.BOLD, 10f));
                        fm = g2.getFontMetrics();
                        String tag = "추천";
                        int tw = fm.stringWidth(tag) + 10;
                        g2.setColor(style.accent);
                        g2.fillRoundRect(6, 6, tw, 16, 16, 16);
                        g2.setColor(Color.WHITE);
                        g2.drawString(tag, 11, 6 + (16 + fm.getAscent() - fm.getDescent()) / 2);
                    }
                    if (sel) {                                           // 선택 표시
                        g2.setColor(accent());
                        g2.fillOval(w - 24, 8, 16, 16);
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawPolyline(new int[]{w - 20, w - 17, w - 12}, new int[]{16, 19, 13}, 3);
                    }
                    g2.dispose();
                }
            }

            /** 둘 중 하나를 고르는 알약 모양 스위치 */
            final class Segmented extends JComponent {
                final String[] items;
                int selected;

                Segmented(String[] items, int selected, java.util.function.IntConsumer onChange) {
                    this.items = items;
                    this.selected = selected;
                    int tw = 0;
                    for (String it : items) tw = Math.max(tw, getFontMetrics(BASE_FONT.deriveFont(12.5f)).stringWidth(it));
                    setPreferredSize(new Dimension(Math.max(140, items.length * Math.max(50, tw + 16)), 32));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        public void mouseClicked(MouseEvent e) {
                            int i = Math.min(items.length - 1, e.getX() * items.length / Math.max(1, getWidth()));
                            if (i == Segmented.this.selected) return;
                            Segmented.this.selected = i;
                            repaint();
                            onChange.accept(i);
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);   // 회색조에서 맑은 고딕 획 빠짐 방지
                    int w = getWidth(), h = getHeight(), seg = w / items.length;
                    g2.setColor(shade());
                    g2.fillRoundRect(0, 0, w, h, h, h);
                    g2.setColor(cardBg());
                    g2.fillRoundRect(selected * seg + 3, 3, seg - 6, h - 6, h - 6, h - 6);
                    g2.setColor(new Color(0, 0, 0, dark() ? 60 : 25));
                    g2.drawRoundRect(selected * seg + 3, 3, seg - 7, h - 7, h - 6, h - 6);
                    g2.setFont(BASE_FONT.deriveFont(12.5f));
                    FontMetrics fm = g2.getFontMetrics();
                    for (int i = 0; i < items.length; i++) {
                        g2.setColor(i == selected ? text() : sub());
                        g2.drawString(items[i], i * seg + (seg - fm.stringWidth(items[i])) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
                    }
                    g2.dispose();
                }
            }

            /** 켜고 끄는 토글 스위치 */
            final class ToggleSwitch extends JComponent {
                boolean on;

                ToggleSwitch(boolean on, java.util.function.Consumer<Boolean> onChange) {
                    this.on = on;
                    setPreferredSize(new Dimension(44, 24));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        public void mouseClicked(MouseEvent e) {
                            ToggleSwitch.this.on = !ToggleSwitch.this.on;
                            repaint();
                            onChange.accept(ToggleSwitch.this.on);
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = getWidth(), h = getHeight();
                    if (on) {
                        g2.setColor(accent());
                        g2.fillRoundRect(0, 0, w, h, h, h);
                        g2.setColor(Color.WHITE);
                        g2.fillOval(w - h + 4, 4, h - 8, h - 8);
                    } else {
                        g2.setColor(sub());
                        g2.setStroke(new BasicStroke(1.4f));
                        g2.drawRoundRect(1, 1, w - 3, h - 3, h - 2, h - 2);
                        g2.fillOval(6, 6, h - 12, h - 12);
                    }
                    g2.dispose();
                }
            }
        }

        final class Card extends JPanel {
            final Note note;

            Card(Note note, String plain) {
                super(new BorderLayout());
                this.note = note;
                setOpaque(false);
                setBorder(new EmptyBorder(14, 14, 16, 12));
                setAlignmentX(LEFT_ALIGNMENT);

                this.plain = plain;
                Theme t = note.theme;
                time = new JLabel(when(note.updatedAt));
                time.setFont(BASE_FONT.deriveFont(11f));
                if (note.shape != NoteShape.Square) {                    // 사각이 아닌 스티커는 모양 표시
                    time.setIcon(new Icon() {
                        public int getIconWidth() { return 14; }
                        public int getIconHeight() { return 14; }
                        public void paintIcon(Component c, Graphics g, int x, int y) {
                            Graphics2D g2 = (Graphics2D) g.create();
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            Shape o = note.shape.outline(x + 1, y + 1, 12, 12);
                            g2.setColor(t.header);
                            g2.fill(o);
                            g2.setColor(t.accent);
                            g2.draw(o);
                            g2.dispose();
                        }
                    });
                    time.setIconTextGap(5);
                }
                more = new FlatButton("", "메뉴");
                more.setIcon(new Glyph("more", 16));
                more.setPreferredSize(new Dimension(30, 24));
                more.addActionListener(e -> popup().show(more, 0, more.getHeight()));
                star = new FlatButton("", note.favorite ? "즐겨찾기 해제" : "즐겨찾기 (목록 위쪽에 고정)");
                star.setIcon(new Glyph(note.favorite ? "starFilled" : "star", 16));
                star.setPreferredSize(new Dimension(30, 24));
                star.addActionListener(e -> app.toggleFavorite(note));
                tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                tools.setOpaque(false);
                tools.add(star);
                tools.add(more);
                tools.setPreferredSize(tools.getPreferredSize());          // 선택 모드에서 버튼을 숨겨도 같은 크기
                JPanel head = new JPanel(new BorderLayout());
                head.setOpaque(false);
                head.add(tools, BorderLayout.WEST);
                head.add(time, BorderLayout.EAST);
                if (note.remindAt != null) {                                  // 알림이 잡힌 메모: 종 + 시각
                    remind = new JLabel(App.remindText(note.remindAt), new Glyph("bell", 13), SwingConstants.LEFT);
                    remind.setFont(BASE_FONT.deriveFont(11f));
                    remind.setIconTextGap(3);
                    remind.setBorder(new EmptyBorder(0, 6, 0, 0));
                    head.add(remind, BorderLayout.CENTER);
                }

                String[] lines = plain.strip().split("\n");
                List<String> src = new ArrayList<>();
                List<String> media = Markup.mediaIds(note.text);
                for (int i = 0; i < Math.min(lines.length, 6); i++)
                    src.add(lines[i].length() > 120 ? lines[i].substring(0, 120) + "…" : lines[i]);
                if (lines.length > 6) src.add("…");
                body = new PreviewText(note.locked() ? List.of() : src, note.locked() ? "잠긴 메모" : plain.isBlank() && media.isEmpty() ? "빈 메모" : null);
                body.setFont(BASE_FONT.deriveFont(13f));

                add(head, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                java.awt.image.BufferedImage thumb = media.isEmpty() || note.locked() ? null : app.store.loadImage(media.get(0));
                if (thumb != null) {
                    pic = new JLabel(new ScaledImageIcon(thumb, 250, 140));
                    pic.setHorizontalAlignment(SwingConstants.LEFT);
                    pic.setBorder(new EmptyBorder(6, 0, 0, 0));
                    if (media.size() > 1) {
                        pic.setText("+" + (media.size() - 1));
                        pic.setVerticalTextPosition(SwingConstants.BOTTOM);
                    }
                    add(pic, BorderLayout.SOUTH);
                }
                restyle();
                selectModeChanged();
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                MouseAdapter ma = new MouseAdapter() {
                    Point start;

                    public void mouseClicked(MouseEvent e) {
                        if (!SwingUtilities.isLeftMouseButton(e) || dragging) return;
                        if (selecting) { toggleSelected(); return; }       // 선택 모드: 누르면 선택/해제
                        NoteWindow w = app.openNote(note);
                        if (searching) w.showFind(search.getText().trim());   // 찾던 글자 위치로
                    }

                    public void mousePressed(MouseEvent e) { start = e.getPoint(); maybePopup(e); }

                    /** 같은 묶음(즐겨찾기/일반) 안에서만 끌어서 순서 변경. 검색 중에는 끌기 안 됨 */
                    public void mouseDragged(MouseEvent e) {
                        if (searching || selecting || !SwingUtilities.isLeftMouseButton(e) || start == null) return;
                        if (!dragging && start.distance(e.getPoint()) < 6) return;
                        if (!dragging) { dragging = true; setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)); repaint(); }
                        List<Card> group = note.favorite ? favCards : normalCards;
                        Point p = SwingUtilities.convertPoint(Card.this, e.getPoint(), cards);
                        for (Card c : group) {
                            if (c != Card.this && c.getBounds().contains(p)) {
                                int to = group.indexOf(c);
                                group.remove(Card.this);
                                group.add(to, Card.this);
                                layoutCards();
                                break;
                            }
                        }
                    }

                    public void mouseReleased(MouseEvent e) {
                        maybePopup(e);
                        if (!dragging) return;
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        List<Note> order = new ArrayList<>();
                        for (Card c : note.favorite ? favCards : normalCards) order.add(c.note);
                        SwingUtilities.invokeLater(() -> { dragging = false; app.reorder(order); });
                    }

                    void maybePopup(MouseEvent e) { if (e.isPopupTrigger()) popup().show(Card.this, e.getX(), e.getY()); }
                };
                addMouseListener(ma);
                addMouseMotionListener(ma);
            }

            boolean dragging;
            final String plain;
            final JLabel time;
            final PreviewText body;
            final FlatButton more, star;
            final JPanel tools;
            JLabel pic, remind;

            boolean isSelected() { return selecting && selected.contains(note.id); }

            void toggleSelected() {
                if (!selected.remove(note.id)) selected.add(note.id);
                selectionChanged();
            }

            /** 선택 모드에서는 별·메뉴 버튼 대신 체크 상자를 그린다 */
            void selectModeChanged() {
                star.setVisible(!selecting);                         // 자리는 그대로 두어 카드 높이가 바뀌지 않게
                more.setVisible(!selecting);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                revalidate();
                repaint();
            }

            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                if (!selecting) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Insets in = getInsets();
                int s = 18, x = in.left + 2, y = in.top + 3;
                boolean on = isSelected();
                Color ac = cute() ? styleAccent() : new Color(0x0067C0);
                if (on) {
                    g2.setColor(ac);
                    g2.fillRoundRect(x, y, s, s, 6, 6);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[]{x + 4, x + 8, x + 14}, new int[]{y + 9, y + 13, y + 5}, 3);
                    // 선택된 카드 테두리 강조
                    g2.setStroke(new BasicStroke(2f));
                    g2.setColor(ac);
                    g2.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 9, cute() ? 20 : 8, cute() ? 20 : 8);
                } else {
                    g2.setColor(new Color(255, 255, 255, 200));
                    g2.fillRoundRect(x, y, s, s, 6, 6);
                    g2.setColor(new Color(note.theme.fg().getRed(), note.theme.fg().getGreen(), note.theme.fg().getBlue(), 140));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRoundRect(x, y, s, s, 6, 6);
                }
                g2.dispose();
            }
            /** 이 카드를 만들 때의 메모 상태. 같으면 다시 만들지 않고 재사용 */
            String stamp;
            int styleGen = -1;

            /** 디자인·밝기만 바뀌었을 때: 카드를 새로 만들지 않고 글자색만 바꾼다 */
            void restyle() {
                if (styleGen == NotesListWindow.styleGen) return;
                styleGen = NotesListWindow.styleGen;
                Theme t = note.theme;
                Color fg = t.fg();
                time.setForeground(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
                for (FlatButton b : new FlatButton[]{more, star}) {
                    b.setForeground(fg);
                    b.hoverColor = t.hover();
                }
                body.setForeground(fg);
                if (pic != null) pic.setForeground(fg);
                if (remind != null) remind.setForeground(fg);
                repaint();
            }

            JPopupMenu popup() {
                JPopupMenu m = new JPopupMenu();
                JMenuItem open = new JMenuItem(note.open ? "메모 닫기" : "메모 열기");
                open.addActionListener(e -> { if (note.open) app.closeNote(note); else app.openNote(note); });
                JMenuItem fav = new JMenuItem(note.favorite ? "즐겨찾기 해제" : "즐겨찾기");
                fav.addActionListener(e -> app.toggleFavorite(note));
                JMenuItem del = new JMenuItem("메모 삭제");
                del.addActionListener(e -> app.deleteNote(note, NotesListWindow.this));
                m.add(open);
                m.add(fav);
                m.add(del);
                return m;
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }

            @Override
            protected void paintComponent(Graphics g) {                  // 그림자 + 둥근 카드 + 마스킹테이프
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Theme t = note.theme;
                int w = getWidth(), h = getHeight(), r = 20;
                if (!cute()) {                                           // 심플: 원래 스티커 메모처럼 위쪽 색 줄
                    g2.setClip(new java.awt.geom.RoundRectangle2D.Float(2, 2, w - 4, h - 6, 8, 8));
                    g2.setColor(t.body);
                    g2.fillRect(0, 0, w, h);
                    g2.setColor(t.header);
                    g2.fillRect(0, 0, w, 6);
                    g2.setClip(null);
                    g2.setColor(new Color(0, 0, 0, dragging ? 0 : 30));
                    g2.drawRoundRect(2, 2, w - 5, h - 7, 8, 8);
                    if (dragging) {
                        g2.setColor(new Color(0x2B6CD9));
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawRoundRect(2, 2, w - 5, h - 7, 8, 8);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                    return;
                }
                for (int i = 4; i >= 1; i--) {
                    g2.setColor(new Color(120, 80, 60, 8));
                    g2.fillRoundRect(3 - i, 5 - i, w - 6 + 2 * i, h - 8 + 2 * i, r + i, r + i);
                }
                g2.setColor(t.body);
                g2.fillRoundRect(3, 3, w - 6, h - 8, r, r);
                g2.setColor(new Color(t.header.darker().getRed(), t.header.darker().getGreen(), t.header.darker().getBlue(), 110));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(3, 3, w - 7, h - 9, r, r);
                // 위에 살짝 기울여 붙인 마스킹테이프
                Shape tape = java.awt.geom.AffineTransform.getRotateInstance(-0.06, w / 2.0, 4)
                        .createTransformedShape(new java.awt.geom.Rectangle2D.Double(w / 2.0 - 30, -4, 60, 15));
                g2.setColor(xmas() ? new Color(255, 255, 255, 240) : new Color(t.header.getRed(), t.header.getGreen(), t.header.getBlue(), 230));
                g2.fill(tape);
                if (xmas()) {                                            // 크리스마스: 지팡이사탕 줄무늬 테이프
                    Shape clip = g2.getClip();
                    g2.clip(tape);
                    g2.setColor(XMAS_RED);
                    g2.setStroke(new BasicStroke(4f));
                    for (int sx = w / 2 - 50; sx < w / 2 + 50; sx += 10) g2.drawLine(sx, 16, sx + 16, -8);
                    g2.setClip(clip);
                }
                g2.setColor(new Color(255, 255, 255, 110));
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 3f}, 0f));
                g2.draw(tape);
                if (dragging) {                                          // 끌고 있는 카드 표시
                    g2.setColor(styleAccent());
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
                    g2.drawRoundRect(3, 3, w - 7, h - 9, r, r);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        }
    }
}
