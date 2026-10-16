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
    static final String VERSION = "1.0.0";
    /** 자동 업데이트를 확인할 GitHub 저장소 (owner/name) */
    static final String UPDATE_REPO = "kkangji99/QuickMemo";

    public static void main(String[] args) throws Exception {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        });
        if (Updater.updateIfAvailable()) return;      // 새 버전을 받아 재시작하는 경우
        SwingUtilities.invokeLater(() -> new App().start());
    }

    // ===================================================================== 자동 업데이트
    /**
     * exe(jpackage app-image)로 실행될 때만 동작. 시작 시 GitHub 최신 릴리스를 확인하고,
     * 더 새 버전이면 QuickMemo-x.y.z-win64.zip 을 받아 설치 폴더를 교체한 뒤 다시 실행한다.
     * 메모 데이터는 %APPDATA%\QuickMemo 에 있으므로 교체 대상이 아니다.
     */
    static final class Updater {
        static final String API = Optional.ofNullable(System.getenv("QUICKMEMO_UPDATE_API")).orElse("https://api.github.com");
        static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
        static final Pattern ZIP = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+-win64\\.zip)\"");

        record Release(String version, String zipUrl) {}

        /** QuickMemo.exe 로 실행 중이면 그 설치 폴더, java/javaw 로 jar 를 실행 중이면 null */
        static Path installDir() {
            String exe = System.getProperty("jpackage.app-path");
            if (exe == null) exe = ProcessHandle.current().info().command().orElse(null);
            if (exe == null || !Paths.get(exe).getFileName().toString().equalsIgnoreCase("QuickMemo.exe")) return null;
            return Paths.get(exe).toAbsolutePath().getParent();
        }

        static boolean updateIfAvailable() {
            Path dir = installDir();
            if (dir == null || UPDATE_REPO.isBlank()) return false;
            try {
                Release r = latest();
                log("installed " + VERSION + " at " + dir + ", latest " + (r == null ? "none" : r.version()));
                if (r == null || compare(r.version(), VERSION) <= 0) return false;
                if (anotherInstanceRunning()) { log("another instance running, skip"); return false; }
                return apply(r, dir);
            } catch (Exception e) {
                log("check failed: " + e);
                return false;
            }
        }

        /** %APPDATA%/QuickMemo/update.log 에 기록 (64KB 넘으면 새로 시작) */
        static void log(String msg) {
            try {
                Path f = Store.dataDir().resolve("update.log");
                Files.createDirectories(f.getParent());
                if (Files.exists(f) && Files.size(f) > 64 * 1024) Files.delete(f);
                Files.writeString(f, java.time.LocalDateTime.now() + " " + msg + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }

        static java.net.http.HttpClient client() {
            return java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(4))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
        }

        static java.net.http.HttpRequest.Builder request(String url) {
            return java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("User-Agent", "QuickMemo/" + VERSION)
                    .header("Accept", "application/vnd.github+json");
        }

        static Release latest() throws Exception {
            var res = client().send(request(API + "/repos/" + UPDATE_REPO + "/releases/latest")
                    .timeout(java.time.Duration.ofSeconds(5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            Matcher tag = TAG.matcher(res.body()), zip = ZIP.matcher(res.body());
            return tag.find() && zip.find() ? new Release(tag.group(1), zip.group(1)) : null;
        }

        static int compare(String a, String b) {
            String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
                if (p != q) return Integer.compare(p, q);
            }
            return 0;
        }

        static int num(String s) {
            try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
        }

        /** 이미 실행 중인 QuickMemo 가 있으면 폴더를 바꿀 수 없으므로 업데이트를 미룬다 */
        static boolean anotherInstanceRunning() {
            try {
                Path dir = Store.dataDir();
                Files.createDirectories(dir);
                try (FileChannel ch = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock l = ch.tryLock();
                    if (l == null) return true;
                    l.release();
                    return false;
                }
            } catch (Exception e) {
                return true;
            }
        }

        static boolean apply(Release r, Path dir) {
            Path staging = dir.resolveSibling(dir.getFileName() + ".update");
            Progress pw = Progress.show("QuickMemo " + r.version() + " 업데이트 중...");
            try {
                if (!Files.isWritable(dir.getParent())) throw new IOException("설치 폴더 위치에 쓸 수 없습니다: " + dir.getParent());
                deleteTree(staging);
                Path zip = Files.createTempFile("QuickMemo-", ".zip");
                download(r.zipUrl(), zip, pw);
                unzip(zip, staging);
                Files.deleteIfExists(zip);
                Path newApp = Files.exists(staging.resolve("QuickMemo.exe")) ? staging : null;
                if (newApp == null) {
                    try (var ds = Files.newDirectoryStream(staging)) {
                        for (Path p : ds) if (Files.exists(p.resolve("QuickMemo.exe"))) newApp = p;
                    }
                }
                if (newApp == null) throw new IOException("받은 파일에 QuickMemo.exe 가 없습니다.");
                Path script = Files.createTempFile("QuickMemo-update-", ".ps1");
                Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
                new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", script.toString(),
                        "-OldPid", String.valueOf(ProcessHandle.current().pid()),
                        "-Src", newApp.toString(), "-Dst", dir.toString(), "-Staging", staging.toString())
                        .start();
                System.exit(0);
                return true;
            } catch (Exception e) {
                log("apply failed: " + e);
                pw.close();
                try { deleteTree(staging); } catch (IOException ignored) { }
                try {
                    SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                            "업데이트하지 못해 현재 버전(" + VERSION + ")으로 실행합니다.\n" + e.getMessage(),
                            "QuickMemo", JOptionPane.WARNING_MESSAGE));
                } catch (Exception ignored) { }
                return false;
            }
        }

        static void download(String url, Path to, Progress pw) throws Exception {
            var res = client().send(request(url).header("Accept", "application/octet-stream").build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("다운로드 실패 (HTTP " + res.statusCode() + ")");
            long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
            try (var in = res.body(); var out = Files.newOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) pw.set((int) (done * 100 / total));
                }
            }
        }

        static void unzip(Path zip, Path dest) throws IOException {
            Files.createDirectories(dest);
            Path root = dest.toAbsolutePath().normalize();
            try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');      // PowerShell 5 Compress-Archive 는 역슬래시를 씀
                    Path p = root.resolve(name).normalize();
                    if (!p.startsWith(root)) throw new IOException("잘못된 압축 항목: " + e.getName());
                    if (name.endsWith("/")) Files.createDirectories(p);
                    else {
                        Files.createDirectories(p.getParent());
                        Files.copy(zin, p, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        static void deleteTree(Path p) throws IOException {
            if (!Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        }

        /** 앱 종료를 기다렸다가 설치 폴더를 새 버전으로 바꾸고 다시 실행 (실패하면 원래 폴더 복구) */
        static final String SCRIPT = String.join("\r\n",
                "param([int]$OldPid, [string]$Src, [string]$Dst, [string]$Staging)",
                "try { Wait-Process -Id $OldPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}",
                "Start-Sleep -Milliseconds 500",
                "$bak = \"$Dst.old\"",
                "if (Test-Path -LiteralPath $bak) { Remove-Item -LiteralPath $bak -Recurse -Force -ErrorAction SilentlyContinue }",
                "$ok = $false",
                "for ($i = 0; $i -lt 15 -and -not $ok; $i++) {",
                "  try { Move-Item -LiteralPath $Dst -Destination $bak -ErrorAction Stop; $ok = $true } catch { Start-Sleep -Seconds 1 }",
                "}",
                "if ($ok) {",
                "  try { Move-Item -LiteralPath $Src -Destination $Dst -ErrorAction Stop }",
                "  catch { Move-Item -LiteralPath $bak -Destination $Dst; $ok = $false }",
                "}",
                "Start-Process -FilePath (Join-Path $Dst 'QuickMemo.exe')",
                "if ($ok) { Remove-Item -LiteralPath $bak -Recurse -Force -ErrorAction SilentlyContinue }",
                "if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force -ErrorAction SilentlyContinue }",
                "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue",
                "");

        /** 업데이트 진행 창 */
        static final class Progress {
            JWindow win;
            JProgressBar bar;

            static Progress show(String text) {
                Progress p = new Progress();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        p.win = new JWindow();
                        JPanel root = new JPanel(new BorderLayout(0, 10));
                        root.setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(new Color(0xD9B700)), new EmptyBorder(16, 20, 16, 20)));
                        root.setBackground(new Color(0xFFF7D1));
                        JLabel l = new JLabel(text);
                        l.setFont(BASE_FONT);
                        p.bar = new JProgressBar(0, 100);
                        p.bar.setIndeterminate(true);
                        root.add(l, BorderLayout.NORTH);
                        root.add(p.bar, BorderLayout.CENTER);
                        p.win.setContentPane(root);
                        p.win.setSize(320, 90);
                        p.win.setLocationRelativeTo(null);
                        p.win.setAlwaysOnTop(true);
                        p.win.setVisible(true);
                    });
                } catch (Exception ignored) { }
                return p;
            }

            void set(int pct) {
                SwingUtilities.invokeLater(() -> {
                    if (bar == null) return;
                    bar.setIndeterminate(false);
                    bar.setValue(pct);
                });
            }

            void close() {
                SwingUtilities.invokeLater(() -> { if (win != null) win.dispose(); });
            }
        }
    }

    // ===================================================================== 테마
    enum Theme {
        Yellow("노랑", 0xFFF7D1, 0xFFF2AB, false),
        Green("초록", 0xE4F9E0, 0xCBF1C4, false),
        Pink("분홍", 0xFFE4F1, 0xFFCCE5, false),
        Purple("보라", 0xF2E6FF, 0xE7CFFF, false),
        Blue("파랑", 0xE2F1FF, 0xCDE9FF, false),
        Gray("회색", 0xF3F2F1, 0xE1DFDD, false),
        Charcoal("차콜", 0x5A5A5A, 0x454545, true);

        final String label;
        final Color body, header;
        final boolean dark;

        Theme(String label, int body, int header, boolean dark) {
            this.label = label;
            this.body = new Color(body);
            this.header = new Color(header);
            this.dark = dark;
        }

        Color fg() { return dark ? new Color(0xF5F5F5) : new Color(0x1F1F1F); }
        Color hover() { return dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 28); }
        /** 메뉴 패널은 메모 색과 구분되는 중립색 */
        Color menuBg() { return dark ? new Color(0x2B2B2B) : new Color(0xFAFAFA); }
        Color menuFg() { return dark ? new Color(0xF5F5F5) : new Color(0x1F1F1F); }
        Color menuHover() { return dark ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 18); }

        static Theme parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return Yellow; }
        }
    }

    static final Font BASE_FONT = new Font("Malgun Gothic", Font.PLAIN, 14);

    // ===================================================================== 시간 (.NET Ticks, 원본과 동일)
    static final long TICKS_EPOCH = 621355968000000000L;

    static long nowTicks() { return System.currentTimeMillis() * 10000L + TICKS_EPOCH; }

    static long ticksToMillis(long t) { return (t - TICKS_EPOCH) / 10000L; }

    // ===================================================================== 모델
    static final class Note {
        String id = UUID.randomUUID().toString();
        Theme theme = Theme.Yellow;
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

        boolean deleted() { return deletedAt != null; }
    }

    // ===================================================================== 본문 마크업
    /** 원본 plum.sqlite 의 Text 형식을 따른 직렬화: 문단마다 "[bs]id=GUID " + [bs]b [bs]i [bs]ul [bs]strike (끄기는 0 접미), 글머리 [bs]l  ([bs] = 백슬래시) */
    static final class Markup {
        static final String BULLET = "• ";
        /** 들여쓰기 단계별 글머리 (맑은 고딕에서 표시되는 기호만 사용). 단계당 공백 4칸 */
        static final String[] BULLET_MARKS = {"•", "–", "·"};
        static final String INDENT = "    ";
        static final int MAX_DEPTH = 5;
        static final Pattern BULLET_PREFIX = Pattern.compile("^((?: {4})*)[•–·] ");

        static String bulletPrefix(int depth) {
            return INDENT.repeat(depth) + BULLET_MARKS[depth % BULLET_MARKS.length] + " ";
        }

        /** 문단 글이 글머리로 시작하면 {깊이, 접두어 길이}, 아니면 null */
        static int[] bulletOf(String paraText) {
            Matcher m = BULLET_PREFIX.matcher(paraText);
            return m.find() ? new int[]{m.group(1).length() / INDENT.length(), m.end()} : null;
        }
        /** 문서 안 이미지 요소에 붙는 미디어 ID 속성 (이미지는 [bs]img=ID 토큰으로 저장) */
        static final Object MEDIA = "quickmemo.media";
        static final Pattern IMG = Pattern.compile("(?<!\\\\)\\\\img=([0-9a-fA-F\\-]+)");

        /** media != null 이면 이미지 런 (text 는 빈 문자열) */
        record Run(String text, boolean b, boolean i, boolean u, boolean s, String media) {}

        static List<String> mediaIds(String text) {
            List<String> ids = new ArrayList<>();
            Matcher m = IMG.matcher(text);
            while (m.find()) ids.add(m.group(1));
            return ids;
        }

        /** 할 일 항목 체크박스 요소에 붙는 속성 (값: 완료 여부 Boolean). 저장 토큰은 [bs]todo / [bs]done */
        static final Object TODO = "quickmemo.todo";
        /** 할 일 항목의 들여쓰기 단계 (Integer). 저장 토큰은 [bs]todo2 처럼 숫자 접미 */
        static final Object TODO_DEPTH = "quickmemo.todoDepth";
        static final Pattern TODO_TOKEN = Pattern.compile("^(\\\\id=\\S+ )?\\\\(todo|done)([1-5])? ");
        static final Pattern STRIKE_TOKEN = Pattern.compile("(?<!\\\\)\\\\strike0? ");

        /** todo: 0 = 일반 문단, 1 = 할 일, 2 = 완료 */
        record Para(boolean bullet, int depth, int todo, List<Run> runs) {
            String plain() {
                StringBuilder sb = new StringBuilder(todo > 0 ? INDENT.repeat(depth) + (todo == 1 ? "□ " : "▣ ") : bullet ? bulletPrefix(depth) : "");
                for (Run r : runs) sb.append(r.text);
                return sb.toString();
            }
        }

        static String serialize(StyledDocument doc) {
            StringBuilder sb = new StringBuilder();
            Element root = doc.getDefaultRootElement();
            try {
                for (int p = 0; p < root.getElementCount(); p++) {
                    Element para = root.getElement(p);
                    int ps = para.getStartOffset();
                    int pe = Math.min(para.getEndOffset() - 1, doc.getLength());
                    String ptext = doc.getText(ps, pe - ps);
                    Boolean todo = todoState(doc, ps);
                    int[] bl = todo == null ? bulletOf(ptext) : null;
                    boolean bullet = bl != null;
                    int cs = ps + (todo != null ? 1 : bullet ? bl[1] : 0);

                    if (p > 0) sb.append('\n');
                    sb.append("\\id=").append(UUID.randomUUID()).append(' ');
                    if (todo != null) {
                        int td = todoDepth(doc, ps);
                        sb.append(todo ? "\\done" : "\\todo").append(td > 0 ? String.valueOf(td) : "").append(' ');
                    }
                    if (bullet) sb.append(bl[0] == 0 ? "\\l " : "\\l" + bl[0] + " ");

                    boolean b = false, i = false, u = false, s = false;
                    for (int k = 0; k < para.getElementCount(); k++) {
                        Element leaf = para.getElement(k);
                        int ls = Math.max(leaf.getStartOffset(), cs);
                        int le = Math.min(leaf.getEndOffset(), pe);
                        if (le <= ls) continue;
                        AttributeSet a = leaf.getAttributes();
                        Object media = a.getAttribute(MEDIA);
                        if (media != null && StyleConstants.getIcon(a) != null) {
                            sb.append("\\img=").append(media).append(' ');
                            continue;
                        }
                        boolean nb = StyleConstants.isBold(a), ni = StyleConstants.isItalic(a),
                                nu = StyleConstants.isUnderline(a), ns = StyleConstants.isStrikeThrough(a);
                        if (nb != b) { sb.append(nb ? "\\b " : "\\b0 "); b = nb; }
                        if (ni != i) { sb.append(ni ? "\\i " : "\\i0 "); i = ni; }
                        if (nu != u) { sb.append(nu ? "\\ul " : "\\ul0 "); u = nu; }
                        if (ns != s) { sb.append(ns ? "\\strike " : "\\strike0 "); s = ns; }
                        sb.append(doc.getText(ls, le - ls).replace("\\", "\\\\"));
                    }
                }
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            return sb.toString();
        }

        static List<Para> parse(String text) {
            List<Para> out = new ArrayList<>();
            for (String line : text.split("\n", -1)) out.add(parseLine(line));
            return out;
        }

        static Para parseLine(String line) {
            List<Run> runs = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean b = false, it = false, u = false, s = false, bullet = false;
            int todo = 0, depth = 0;
            int n = line.length(), i = 0;
            while (i < n) {
                char c = line.charAt(i);
                if (c == '\\') {
                    if (i + 1 < n && line.charAt(i + 1) == '\\') { cur.append('\\'); i += 2; continue; }
                    int j = i + 1;
                    while (j < n && line.charAt(j) != ' ') j++;
                    String tok = line.substring(i + 1, j);
                    boolean known = true;
                    if (cur.length() > 0) { runs.add(new Run(cur.toString(), b, it, u, s, null)); cur.setLength(0); }
                    if (tok.startsWith("id=")) { /* 문단 ID: 무시 */ }
                    else if (tok.startsWith("img=") && tok.length() > 4) runs.add(new Run("", false, false, false, false, tok.substring(4)));
                    else switch (tok) {
                        case "b" -> b = true;
                        case "b0" -> b = false;
                        case "i" -> it = true;
                        case "i0" -> it = false;
                        case "ul" -> u = true;
                        case "ul0" -> u = false;
                        case "strike" -> s = true;
                        case "strike0" -> s = false;
                        case "l" -> { bullet = true; depth = 0; }
                        case "l1", "l2", "l3", "l4", "l5" -> { bullet = true; depth = tok.charAt(1) - '0'; }
                        case "todo" -> { todo = 1; depth = 0; }
                        case "done" -> { todo = 2; depth = 0; }
                        case "todo1", "todo2", "todo3", "todo4", "todo5" -> { todo = 1; depth = tok.charAt(4) - '0'; }
                        case "done1", "done2", "done3", "done4", "done5" -> { todo = 2; depth = tok.charAt(4) - '0'; }
                        case "ltrpar", "rtlpar" -> { }
                        default -> known = false;
                    }
                    if (known) { i = j < n ? j + 1 : j; continue; }
                }
                cur.append(c);
                i++;
            }
            if (cur.length() > 0) runs.add(new Run(cur.toString(), b, it, u, s, null));
            return new Para(bullet && todo == 0, depth, todo, runs);
        }

        /** 0 = 일반, 1 = 할 일, 2 = 완료 (직렬화된 한 줄 기준) */
        static int todoOf(String line) {
            Matcher m = TODO_TOKEN.matcher(line);
            return !m.find() ? 0 : m.group(2).equals("done") ? 2 : 1;
        }

        /** 한 줄의 할 일 상태를 바꾼다. 완료면 내용 전체에 취소선, 아니면 취소선 제거 */
        /** 할 일 줄의 들여쓰기 단계 (직렬화된 한 줄 기준) */
        static int todoDepthOf(String line) {
            Matcher m = TODO_TOKEN.matcher(line);
            return m.find() && m.group(3) != null ? m.group(3).charAt(0) - '0' : 0;
        }

        /** 한 줄의 할 일 상태를 바꾼다(단계 유지). 완료면 내용 전체에 취소선, 아니면 취소선 제거 */
        static String withTodo(String line, boolean done) {
            int depth = todoDepthOf(line);
            Matcher m = TODO_TOKEN.matcher(line);
            String rest = m.find() ? line.substring(m.end()) : line.replaceFirst("^\\\\id=\\S+ ", "");
            rest = STRIKE_TOKEN.matcher(rest).replaceAll("");
            String tok = (done ? "\\done" : "\\todo") + (depth > 0 ? String.valueOf(depth) : "") + " ";
            return "\\id=" + UUID.randomUUID() + " " + tok + (done ? "\\strike " : "") + rest;
        }

        /** 문단 시작 위치의 체크박스 상태 (체크박스가 없으면 null) */
        static Boolean todoState(StyledDocument doc, int paraStart) {
            if (paraStart >= doc.getLength()) return null;
            AttributeSet a = doc.getCharacterElement(paraStart).getAttributes();
            Object v = a.getAttribute(TODO);
            return v instanceof Boolean bv && StyleConstants.getIcon(a) != null ? bv : null;
        }

        static int todoDepth(StyledDocument doc, int paraStart) {
            if (paraStart >= doc.getLength()) return 0;
            return doc.getCharacterElement(paraStart).getAttributes().getAttribute(TODO_DEPTH) instanceof Integer d ? d : 0;
        }

        static SimpleAttributeSet todoAttrs(boolean done) { return todoAttrs(done, 0); }

        static SimpleAttributeSet todoAttrs(boolean done, int depth) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setIcon(a, new CheckIcon(done, depth));
            a.addAttribute(TODO, done);
            a.addAttribute(TODO_DEPTH, depth);
            return a;
        }

        static void load(StyledDocument doc, String text) {
            load(doc, text, id -> new Glyph("image", 48));
        }

        /** resolver: 미디어 ID → 표시할 아이콘 */
        static void load(StyledDocument doc, String text, java.util.function.Function<String, Icon> resolver) {
            try {
                doc.remove(0, doc.getLength());
                List<Para> paras = parse(text);
                int pos = 0;
                for (int p = 0; p < paras.size(); p++) {
                    Para para = paras.get(p);
                    if (p > 0) { doc.insertString(pos, "\n", null); pos++; }
                    if (para.todo > 0) { doc.insertString(pos, " ", todoAttrs(para.todo == 2, para.depth)); pos++; }
                    if (para.bullet) {
                        String pre = bulletPrefix(para.depth);
                        doc.insertString(pos, pre, null);
                        pos += pre.length();
                    }
                    for (Run r : para.runs) {
                        if (r.media != null) {
                            doc.insertString(pos, " ", imageAttrs(r.media, resolver.apply(r.media)));
                            pos++;
                            continue;
                        }
                        SimpleAttributeSet a = new SimpleAttributeSet();
                        StyleConstants.setBold(a, r.b);
                        StyleConstants.setItalic(a, r.i);
                        StyleConstants.setUnderline(a, r.u);
                        StyleConstants.setStrikeThrough(a, r.s);
                        doc.insertString(pos, r.text, a);
                        pos += r.text.length();
                    }
                }
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }

        static SimpleAttributeSet imageAttrs(String mediaId, Icon icon) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setIcon(a, icon);
            a.addAttribute(MEDIA, mediaId);
            return a;
        }

        static String plain(String text) {
            StringBuilder sb = new StringBuilder();
            List<Para> paras = parse(text);
            for (int p = 0; p < paras.size(); p++) {
                if (p > 0) sb.append('\n');
                sb.append(paras.get(p).plain());
            }
            return sb.toString();
        }
    }

    // ===================================================================== Windows 스티커 메모 가져오기
    /**
     * 외부 라이브러리 없이 SQLite 파일을 읽는 최소 리더 (읽기 전용).
     * 테이블 B-트리, 오버플로 페이지, WAL(커밋된 프레임) 을 지원한다. UTF-8 DB 만 처리.
     */
    static final class SqliteReader {
        final byte[] db;
        final int pageSize, usable;
        final Map<Integer, byte[]> wal = new HashMap<>();

        SqliteReader(Path dbFile, Path walFile) throws IOException {
            db = Files.readAllBytes(dbFile);
            if (db.length < 100 || !new String(db, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3"))
                throw new IOException("SQLite 파일이 아닙니다.");
            int ps = u16(db, 16);
            pageSize = ps == 1 ? 65536 : ps;
            usable = pageSize - (db[20] & 0xff);
            if (u32(db, 56) > 1) throw new IOException("UTF-8 이 아닌 SQLite 파일은 지원하지 않습니다.");
            if (walFile != null && Files.exists(walFile)) readWal(Files.readAllBytes(walFile));
        }

        static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
        static int u32(byte[] b, int o) { return (u16(b, o) << 16) | u16(b, o + 2); }

        /** 헤더 salt 가 맞는 프레임만, 커밋 프레임까지 묶어서 반영 */
        void readWal(byte[] w) {
            if (w.length < 32 || (u32(w, 0) & 0xFFFFFFFE) != 0x377f0682 || u32(w, 8) != pageSize) return;
            int s1 = u32(w, 16), s2 = u32(w, 20);
            Map<Integer, byte[]> pending = new HashMap<>();
            for (int off = 32; off + 24 + pageSize <= w.length; off += 24 + pageSize) {
                if (u32(w, off + 8) != s1 || u32(w, off + 12) != s2) break;
                pending.put(u32(w, off), Arrays.copyOfRange(w, off + 24, off + 24 + pageSize));
                if (u32(w, off + 4) != 0) { wal.putAll(pending); pending.clear(); }
            }
        }

        byte[] page(int n) {
            byte[] p = wal.get(n);
            if (p != null) return p;
            int off = (n - 1) * pageSize;
            return off + pageSize <= db.length ? Arrays.copyOfRange(db, off, off + pageSize) : new byte[pageSize];
        }

        /** varint: [값, 길이] */
        static long[] varint(byte[] b, int o) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                int c = b[o + i] & 0xff;
                v = (v << 7) | (c & 0x7f);
                if ((c & 0x80) == 0) return new long[]{v, i + 1};
            }
            return new long[]{(v << 8) | (b[o + 8] & 0xff), 9};
        }

        /** 테이블의 모든 행을 열 이름 → 값(Long/Double/String/byte[]/null) 으로 */
        List<Map<String, Object>> table(String name) throws IOException {
            String sql = null;
            int root = 0;
            for (Object[] r : scan(1)) {
                if (r.length >= 5 && "table".equals(r[0]) && name.equals(r[1])) { root = ((Long) r[3]).intValue(); sql = (String) r[4]; }
            }
            if (sql == null) throw new IOException(name + " 테이블이 없습니다.");
            List<String> cols = new ArrayList<>();
            for (String def : sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')')).split(",")) {
                String c = def.trim().split("\\s+")[0].replaceAll("[\"`\\[\\]]", "");
                if (!c.isEmpty() && !c.equalsIgnoreCase("primary") && !c.equalsIgnoreCase("constraint")) cols.add(c);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] r : scan(root)) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) m.put(cols.get(i), i < r.length ? r[i] : null);
                out.add(m);
            }
            return out;
        }

        List<Object[]> scan(int root) throws IOException {
            List<Object[]> out = new ArrayList<>();
            scan(root, out, 0);
            return out;
        }

        void scan(int pgno, List<Object[]> out, int depth) throws IOException {
            if (depth > 40 || pgno < 1) throw new IOException("손상된 B-트리");
            byte[] p = page(pgno);
            int h = pgno == 1 ? 100 : 0;
            int type = p[h] & 0xff, cells = u16(p, h + 3);
            if (type == 0x0D) {
                for (int i = 0; i < cells; i++) out.add(leafCell(p, u16(p, h + 8 + 2 * i)));
            } else if (type == 0x05) {
                for (int i = 0; i < cells; i++) scan(u32(p, u16(p, h + 12 + 2 * i)), out, depth + 1);
                scan(u32(p, h + 8), out, depth + 1);
            } else {
                throw new IOException("지원하지 않는 페이지 형식: " + type);
            }
        }

        Object[] leafCell(byte[] p, int off) {
            long[] size = varint(p, off);
            off += (int) size[1];
            off += (int) varint(p, off)[1];            // rowid
            int total = (int) size[0];
            int x = usable - 35, m = ((usable - 12) * 32 / 255) - 23;
            int local = total;
            if (total > x) {
                int k = m + ((total - m) % (usable - 4));
                local = k <= x ? k : m;
            }
            byte[] payload = new byte[total];
            System.arraycopy(p, off, payload, 0, local);
            int got = local, next = local < total ? u32(p, off + local) : 0;
            while (got < total && next > 0) {
                byte[] ov = page(next);
                int n = Math.min(usable - 4, total - got);
                System.arraycopy(ov, 4, payload, got, n);
                got += n;
                next = u32(ov, 0);
            }
            return record(payload);
        }

        static Object[] record(byte[] b) {
            long[] hs = varint(b, 0);
            List<Long> types = new ArrayList<>();
            for (int o = (int) hs[1]; o < hs[0]; ) {
                long[] t = varint(b, o);
                types.add(t[0]);
                o += (int) t[1];
            }
            Object[] vals = new Object[types.size()];
            int o = (int) hs[0];
            for (int i = 0; i < vals.length; i++) {
                long t = types.get(i);
                if (t == 0) vals[i] = null;
                else if (t >= 1 && t <= 6) {
                    int n = t <= 4 ? (int) t : t == 5 ? 6 : 8;
                    long v = b[o];                       // 부호 확장
                    for (int k = 1; k < n; k++) v = (v << 8) | (b[o + k] & 0xff);
                    vals[i] = v;
                    o += n;
                } else if (t == 7) {
                    long v = 0;
                    for (int k = 0; k < 8; k++) v = (v << 8) | (b[o + k] & 0xff);
                    vals[i] = Double.longBitsToDouble(v);
                    o += 8;
                } else if (t == 8 || t == 9) vals[i] = t - 8;
                else if (t >= 12) {
                    int n = (int) ((t - (t % 2 == 0 ? 12 : 13)) / 2);
                    vals[i] = t % 2 == 0 ? Arrays.copyOfRange(b, o, o + n) : new String(b, o, n, StandardCharsets.UTF_8);
                    o += n;
                }
            }
            return vals;
        }
    }

    /** %LOCALAPPDATA% 의 Microsoft Sticky Notes 데이터(plum.sqlite) 를 읽는다 */
    static final class StickyImport {
        static Path source() {
            String base = System.getenv("LOCALAPPDATA");
            return base == null ? null : Paths.get(base, "Packages", "Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe", "LocalState", "plum.sqlite");
        }

        static boolean available() {
            Path p = source();
            return p != null && Files.exists(p);
        }

        /** 사용 중인 DB 를 건드리지 않도록 임시 폴더로 복사해서 읽는다. 삭제되지 않은 메모만 */
        static List<Map<String, Object>> readNotes() throws IOException {
            Path src = source();
            if (src == null || !Files.exists(src)) throw new IOException("이 PC에서 Windows 스티커 메모 데이터를 찾지 못했습니다.");
            Path tmp = Files.createTempDirectory("quickmemo-import");
            try {
                Path db = tmp.resolve("plum.sqlite"), wal = tmp.resolve("plum.sqlite-wal");
                Files.copy(src, db);
                Path srcWal = src.resolveSibling("plum.sqlite-wal");
                if (Files.exists(srcWal)) Files.copy(srcWal, wal);
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> r : new SqliteReader(db, wal).table("Note")) {
                    if (r.get("DeletedAt") == null && r.get("Id") instanceof String) out.add(r);
                }
                return out;
            } finally {
                Updater.deleteTree(tmp);
            }
        }
    }

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

        java.awt.image.BufferedImage loadImage(String id) {
            return imageCache.computeIfAbsent(id, k -> {
                try {
                    Path p = mediaDir.resolve(k + ".png");
                    return Files.exists(p) ? javax.imageio.ImageIO.read(p.toFile()) : null;
                } catch (IOException e) {
                    return null;
                }
            });
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
                    case "Order" -> n.order = v.isEmpty() ? null : Long.parseLong(v);
                    case "WindowPosition" -> {
                        Matcher m = POS.matcher(v);
                        if (m.find()) n.bounds = new Rectangle(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                    }
                    default -> { }
                }
            }
            return n;
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
            if (n.order != null) sb.append("Order=").append(n.order).append('\n');
            sb.append('\n').append(n.text);

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
    }

    // ===================================================================== 공용 UI
    static class FlatButton extends JButton {
        Color hoverColor = new Color(0, 0, 0, 28);
        boolean active;

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
            if (active || getModel().isRollover()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(active ? hoverColor.darker() : hoverColor);
                if (active) g2.setColor(new Color(hoverColor.getRed(), hoverColor.getGreen(), hoverColor.getBlue(),
                        Math.min(255, hoverColor.getAlpha() * 2)));
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 글꼴에 의존하지 않는 벡터 아이콘 (버튼 글자색을 따라감) */
    static final class Glyph implements Icon {
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
            g2.setColor(c != null ? c.getForeground() : Color.DARK_GRAY);
            g2.translate(x, y);
            float s = size;
            g2.setStroke(new BasicStroke(Math.max(1.4f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case "plus" -> { line(g2, .2, .5, .8, .5); line(g2, .5, .2, .5, .8); }
                case "close" -> { line(g2, .27, .27, .73, .73); line(g2, .73, .27, .27, .73); }
                case "more" -> { for (double cx : new double[]{.2, .5, .8}) dot(g2, cx, .5, .08); }
                case "list" -> { for (double cy : new double[]{.3, .5, .7}) line(g2, .2, cy, .8, cy); }
                case "bullets" -> {
                    for (double cy : new double[]{.28, .5, .72}) { dot(g2, .2, cy, .07); line(g2, .38, cy, .84, cy); }
                }
                case "star", "starFilled" -> {
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    for (int k = 0; k < 10; k++) {
                        double r = (k % 2 == 0 ? .44 : .19) * s, a = -Math.PI / 2 + k * Math.PI / 5;
                        double px = s * .5 + Math.cos(a) * r, py = s * .54 + Math.sin(a) * r;
                        if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
                    }
                    p.closePath();
                    if (kind.equals("starFilled")) {
                        g2.setColor(new Color(0xF2B600));
                        g2.fill(p);
                        g2.setColor(new Color(0xC99700));
                        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    }
                    g2.draw(p);
                }
                case "checklist" -> {
                    g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .1, s * .14, s * .28, s * .28, s * .08, s * .08));
                    poly(g2, new double[]{.16, .23, .34}, new double[]{.28, .35, .2});
                    line(g2, .5, .28, .9, .28);
                    g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .1, s * .58, s * .28, s * .28, s * .08, s * .08));
                    line(g2, .5, .72, .9, .72);
                }
                case "trash" -> {
                    line(g2, .18, .26, .82, .26);
                    line(g2, .4, .14, .6, .14);
                    poly(g2, new double[]{.27, .32, .68, .73}, new double[]{.26, .86, .86, .26});
                    line(g2, .43, .42, .43, .72);
                    line(g2, .57, .42, .57, .72);
                }
                case "pin" -> {
                    poly(g2, new double[]{.38, .62, .62, .72, .28, .38}, new double[]{.14, .14, .48, .62, .62, .48});
                    line(g2, .5, .62, .5, .88);
                }
                case "gear" -> {
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI / 4 * k;
                        line(g2, .5 + Math.cos(a) * .28, .5 + Math.sin(a) * .28, .5 + Math.cos(a) * .42, .5 + Math.sin(a) * .42);
                    }
                    g2.draw(new java.awt.geom.Ellipse2D.Double(s * .27, s * .27, s * .46, s * .46));
                    g2.draw(new java.awt.geom.Ellipse2D.Double(s * .42, s * .42, s * .16, s * .16));
                }
                case "image" -> {
                    g2.draw(new java.awt.geom.RoundRectangle2D.Double(s * .12, s * .2, s * .76, s * .6, s * .1, s * .1));
                    poly(g2, new double[]{.16, .4, .56, .66, .84}, new double[]{.76, .48, .64, .54, .72});
                    dot(g2, .64, .36, .06);
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
    static final class CheckIcon implements Icon {
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
            Color bg = c != null ? c.getBackground() : Color.WHITE;
            java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Double(x + 0.75, y + 1.75, BOX - 1.5, BOX - 1.5, 4, 4);
            if (done) {
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
                g2.fill(box);
                g2.setColor(bg);
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
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                Math.max(1, loaded.getIconWidth()), Math.max(1, loaded.getIconHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.drawImage(loaded.getImage(), 0, 0, null);
        g.dispose();
        return bi;
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
                    JOptionPane.showMessageDialog(null, "QuickMemo가 이미 실행 중입니다.", "QuickMemo", JOptionPane.INFORMATION_MESSAGE);
                    System.exit(0);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "저장소를 열 수 없습니다: " + e.getMessage());
                System.exit(1);
            }
            notes.addAll(store.loadAll());
            list = new NotesListWindow(this);

            int opened = 0;
            for (Note n : new ArrayList<>(notes)) {
                if (n.open && !n.deleted()) { openNote(n); opened++; }
            }
            registerStartMenu();
            // 처음 실행이고 Windows 스티커 메모가 있으면 한 번 가져오기를 제안
            if (notes.isEmpty() && StickyImport.available() && !"true".equals(store.settings.getProperty("importOffered"))) {
                store.set("importOffered", "true");
                int r = JOptionPane.showConfirmDialog(null, "Windows 스티커 메모가 있습니다. QuickMemo로 가져올까요?\n(나중에 메모 목록 > 설정에서도 가져올 수 있습니다)",
                        "QuickMemo", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (r == JOptionPane.YES_OPTION && importStickyNotes(null, false) > 0) { showList(); return; }
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
            if (exe.toString().equals(store.settings.getProperty("startMenuTarget")) && Files.exists(lnk)) return;
            store.set("startMenuTarget", exe.toString());
            Thread t = new Thread(() -> {
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
                            "-File", script.toString(), "-Lnk", lnk.toString(), "-Exe", exe.toString(), "-Dir", dir.toString())
                            .redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    p.waitFor();
                    Files.deleteIfExists(script);
                    Updater.log("start menu shortcut: " + lnk + " -> " + exe + " (exit " + p.exitValue() + ")");
                } catch (Exception e) {
                    Updater.log("start menu shortcut failed: " + e);
                }
            }, "start-menu");
            t.setDaemon(true);
            t.start();
        }

        /** Windows 스티커 메모를 가져와 닫힌 메모로 추가. 이미 가져온 메모는 건너뜀. 가져온 개수 반환 */
        int importStickyNotes(Component parent, boolean confirm) {
            List<Map<String, Object>> rows;
            try {
                rows = StickyImport.readNotes();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(parent, "스티커 메모를 읽지 못했습니다.\n" + e.getMessage(), "가져오기", JOptionPane.WARNING_MESSAGE);
                return 0;
            }
            Set<String> done = new HashSet<>();
            for (Note n : notes) if (n.importedFrom != null) done.add(n.importedFrom);
            List<Map<String, Object>> todo = new ArrayList<>();
            for (Map<String, Object> r : rows) if (!done.contains((String) r.get("Id"))) todo.add(r);
            int skipped = rows.size() - todo.size();
            if (todo.isEmpty()) {
                JOptionPane.showMessageDialog(parent, rows.isEmpty() ? "가져올 스티커 메모가 없습니다."
                        : "새로 가져올 메모가 없습니다. (이미 가져온 메모 " + skipped + "개)", "가져오기", JOptionPane.INFORMATION_MESSAGE);
                return 0;
            }
            if (confirm && JOptionPane.showConfirmDialog(parent, "Windows 스티커 메모 " + todo.size() + "개를 가져올까요?"
                    + (skipped > 0 ? "\n(이미 가져온 " + skipped + "개는 건너뜁니다)" : ""), "가져오기",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return 0;
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
            JOptionPane.showMessageDialog(parent, todo.size() + "개의 메모를 가져왔습니다. 메모 목록에서 열어 보세요.", "가져오기", JOptionPane.INFORMATION_MESSAGE);
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

        void newNote(Window near) {
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
            store.save(n);
            list.refresh();
            checkExit();
        }

        void deleteNote(Note n, Component parent) {
            if (store.confirmDelete()) {
                JCheckBox dontAsk = new JCheckBox("다시 묻지 않음");
                Object[] msg = {"이 메모를 삭제할까요?", dontAsk};
                int r = JOptionPane.showConfirmDialog(parent, msg, "메모 삭제", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (r != JOptionPane.OK_OPTION) return;
                if (dontAsk.isSelected()) store.set("confirmDelete", "false");
            }
            NoteWindow w = windows.remove(n.id);
            if (w != null) { w.flush(); w.dispose(); }
            n.open = false;
            n.deletedAt = nowTicks();
            store.save(n);
            list.refresh();
            checkExit();
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
                if (windows.isEmpty() && !list.isVisible()) exit();
            });
        }

        void exit() {
            for (NoteWindow w : windows.values()) w.flush();
            System.exit(0);
        }
    }

    // ===================================================================== 노트 창
    static final class NoteWindow extends JFrame {
        static final int EDGE = 6;

        final App app;
        final Note note;
        final JTextPane pane;
        final StyledDocument doc;
        final UndoManager undo = new UndoManager();
        final JPanel header = new JPanel(new BorderLayout());
        final JPanel menu = new JPanel();
        final JPanel swatches = new JPanel(new GridLayout(1, Theme.values().length, 0, 0));
        final JPanel formatBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 2));
        /** 서식 버튼 (id → 버튼), 기본 순서 */
        final Map<String, FlatButton> formatButtons = new LinkedHashMap<>();
        boolean dragSuppress;
        final JScrollPane scroll;
        final List<FlatButton> flatButtons = new ArrayList<>();
        final List<FlatButton> menuButtons = new ArrayList<>();
        final FlatButton bBold, bItalic, bUnder, bStrike, bBullet;
        final FlatButton topButton;
        final javax.swing.Timer saveTimer;
        boolean loading;
        boolean dirtyText;

        NoteWindow(App app, Note note) {
            super("QuickMemo");
            this.app = app;
            this.note = note;
            setUndecorated(true);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(220, 160));
            setIconImage(makeIcon());

            // ---- 헤더
            FlatButton add = iconButton("plus", "새 메모 (Ctrl+N)", () -> app.newNote(this));
            FlatButton more = iconButton("more", "메뉴", this::toggleMenu);
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
            header.setPreferredSize(new Dimension(10, 32));
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

            // ---- 메뉴 (색 선택 / 항상 위 / 노트 목록 / 삭제)
            menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));
            for (Theme t : Theme.values()) swatches.add(new Swatch(t));
            swatches.setBorder(new EmptyBorder(4, 6, 2, 6));
            swatches.setPreferredSize(new Dimension(10, 44));
            swatches.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            swatches.setAlignmentX(LEFT_ALIGNMENT);
            menu.add(swatches);
            topButton = menuItem("pin", "항상 위에 표시", () -> {
                note.onTop = !note.onTop;
                setAlwaysOnTop(note.onTop);
                updateTopButton();
                app.store.save(note);
            });
            menu.add(topButton);
            menu.add(menuItem("list", "메모 목록", () -> { toggleMenu(); app.showList(); }));
            menu.add(menuItem("trash", "메모 삭제", () -> app.deleteNote(note, this)));
            menu.setVisible(false);

            JPanel north = new JPanel();
            north.setOpaque(false);
            north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
            header.setAlignmentX(LEFT_ALIGNMENT);
            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            menu.setAlignmentX(LEFT_ALIGNMENT);
            north.add(header);
            north.add(menu);

            // ---- 본문
            pane = new JTextPane() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (getDocument().getLength() == 0) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        Color fg = getForeground();
                        g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 110));
                        g2.setFont(getFont());
                        Insets in = getInsets();
                        g2.drawString("메모 작성...", in.left + 2, in.top + g2.getFontMetrics().getAscent());
                        g2.dispose();
                    }
                }
            };
            pane.setFont(BASE_FONT);
            pane.setBorder(new EmptyBorder(8, 12, 8, 12));
            doc = pane.getStyledDocument();
            pane.setTransferHandler(new NoteTransfer());   // 이미지 붙여넣기·끌어놓기
            scroll =new JScrollPane(pane, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);

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

            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(new EmptyBorder(EDGE - 2, EDGE - 2, EDGE - 2, EDGE - 2));
            content.add(north, BorderLayout.NORTH);
            content.add(scroll, BorderLayout.CENTER);
            content.add(formatBar, BorderLayout.SOUTH);
            setContentPane(content);
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
            updateTopButton();

            if (note.bounds != null && onAnyScreen(note.bounds)) setBounds(note.bounds);
            else {
                setSize(320, 320);
                Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                int k = app.windows.size() % 8;
                setLocation(scr.x + scr.width - 360 - k * 30, scr.y + 40 + k * 30);
                note.bounds = getBounds();
            }

            addComponentListener(new ComponentAdapter() {
                public void componentMoved(ComponentEvent e) { boundsChanged(); }
                public void componentResized(ComponentEvent e) { boundsChanged(); }
            });
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
                JOptionPane.showMessageDialog(this, "이미지를 넣지 못했습니다: " + ex.getMessage());
            }
        }

        boolean insertImageFile(java.io.File f) {
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) return false;
                insertImage(img);
                return true;
            } catch (IOException e) {
                return false;
            }
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
                        insertImage(toBuffered((Image) t.getTransferData(DataFlavor.imageFlavor)));
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

        FlatButton menuItem(String kind, String text, Runnable r) {
            FlatButton b = flat(text, null, r);
            flatButtons.remove(b);
            menuButtons.add(b);
            b.setIcon(new Glyph(kind, 16));
            b.setIconTextGap(10);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setFont(BASE_FONT.deriveFont(13f));
            b.setMargin(new Insets(8, 14, 8, 14));
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
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
            getContentPane().setBackground(t.body);
            header.setBackground(t.header);
            menu.setBackground(t.menuBg());
            menu.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, t.dark ? new Color(0x1A1A1A) : new Color(0xE0E0E0)));
            swatches.setBackground(t.menuBg());
            for (FlatButton b : menuButtons) {
                b.setForeground(t.menuFg());
                b.hoverColor = t.menuHover();
            }
            formatBar.setBackground(t.body);
            pane.setBackground(t.body);
            pane.setForeground(t.fg());
            pane.setCaretColor(t.fg());
            pane.setSelectionColor(t.dark ? new Color(0x2B5797) : new Color(0xB5D5FF));
            pane.setSelectedTextColor(t.fg());
            scroll.getViewport().setBackground(t.body);
            for (FlatButton b : flatButtons) {
                b.setForeground(t.fg());
                b.hoverColor = t.hover();
            }
            swatches.repaint();
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

        void toggleMenu() {
            menu.setVisible(!menu.isVisible());
            revalidate();
            repaint();
        }

        void updateTopButton() {
            topButton.active = note.onTop;
            topButton.repaint();
        }

        // ---------- 저장
        void textChanged() {
            if (loading) return;
            dirtyText = true;
            saveTimer.restart();
            updateFormatButtons();
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

        Image makeIcon() {
            Image img = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = (Graphics2D) img.getGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(note.theme.header);
            g.fillRoundRect(2, 2, 28, 28, 6, 6);
            g.setColor(new Color(0, 0, 0, 80));
            g.drawRoundRect(2, 2, 27, 27, 6, 6);
            g.dispose();
            return img;
        }

        // ---------- 색 견본
        final class Swatch extends JComponent {
            final Theme theme;

            Swatch(Theme theme) {
                this.theme = theme;
                setToolTipText(theme.label);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) { setTheme(Swatch.this.theme); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int in = 4, w = getWidth() - 2 * in, h = getHeight() - 2 * in;
                g2.setColor(theme.header);
                g2.fillRoundRect(in, in, w, h, 6, 6);
                g2.setColor(note.theme == theme ? note.theme.menuFg() : new Color(0, 0, 0, 35));
                g2.setStroke(new BasicStroke(note.theme == theme ? 1.6f : 1f));
                g2.drawRoundRect(in, in, w - 1, h - 1, 6, 6);
                if (note.theme == theme) {
                    g2.setColor(theme.fg());
                    g2.setStroke(new BasicStroke(2f));
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g2.drawPolyline(new int[]{cx - 6, cx - 2, cx + 6}, new int[]{cy, cy + 4, cy - 5}, 3);
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
                return e;
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
                if (edge == 0) return;
                Point p = e.getLocationOnScreen();
                int dx = p.x - start.x, dy = p.y - start.y;
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
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Color.GRAY);
                    Insets in = getInsets();
                    g2.drawString("검색...", in.left + 2, in.top + g2.getFontMetrics().getAscent() + 1);
                    g2.dispose();
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

            title.setFont(BASE_FONT.deriveFont(Font.BOLD, 20f));
            FlatButton add = new FlatButton("", "새 메모 (Ctrl+N)");
            add.setIcon(new Glyph("plus", 20));
            add.setPreferredSize(new Dimension(40, 36));
            add.addActionListener(e -> app.newNote(this));
            FlatButton gear = new FlatButton("", "설정");
            gear.setIcon(new Glyph("gear", 20));
            gear.setPreferredSize(new Dimension(40, 36));
            gear.addActionListener(e -> showSettings());
            buttons.add(add);
            buttons.add(gear);
            JPanel tb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            tb.setOpaque(false);
            tb.add(add);
            tb.add(gear);
            top.add(title, BorderLayout.WEST);
            top.add(tb, BorderLayout.EAST);
            top.setBorder(new EmptyBorder(12, 16, 8, 10));
            top.setOpaque(false);   // 기본 밝은 배경이 칠해지면 어둡게 모드에서 제목·버튼이 안 보임

            search.setFont(BASE_FONT);
            search.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0, 0, 0, 40)), new EmptyBorder(6, 8, 6, 8)));
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { refresh(); }
                public void removeUpdate(DocumentEvent e) { refresh(); }
                public void changedUpdate(DocumentEvent e) { refresh(); }
            });
            JPanel sp = new JPanel(new BorderLayout());
            sp.setOpaque(false);
            sp.setBorder(new EmptyBorder(0, 16, 10, 16));
            sp.add(search);

            JPanel north = new JPanel(new BorderLayout());
            north.setOpaque(false);
            north.add(top, BorderLayout.NORTH);
            north.add(sp, BorderLayout.SOUTH);

            cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
            cards.setBorder(new EmptyBorder(0, 16, 16, 16));
            JPanel holder = new TrackWidthPanel();   // 스크롤바가 생겨도 카드가 보이는 너비에 맞춰짐
            holder.add(cards, BorderLayout.NORTH);
            scroll = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);

            JPanel root = new JPanel(new BorderLayout());
            root.add(north, BorderLayout.NORTH);
            root.add(scroll, BorderLayout.CENTER);
            setContentPane(root);

            JRootPane rp = getRootPane();
            NoteWindow.bind(rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), rp.getActionMap(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "newNote", () -> app.newNote(this));
            NoteWindow.bind(rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), rp.getActionMap(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "search", search::requestFocusInWindow);

            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    setVisible(false);
                    app.checkExit();
                }
            });
            applyMode();
        }

        Color bg() { return app.store.darkMode() ? new Color(0x202020) : new Color(0xF3F3F3); }
        Color fg() { return app.store.darkMode() ? new Color(0xF0F0F0) : new Color(0x1F1F1F); }

        void applyMode() {
            Color bg = bg(), fg = fg();
            getContentPane().setBackground(bg);
            for (Component c : List.of(scroll.getViewport(), (Component) scroll.getViewport().getView(), cards)) c.setBackground(bg);
            title.setForeground(fg);
            for (FlatButton b : buttons) {
                b.setForeground(fg);
                b.hoverColor = app.store.darkMode() ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 28);
            }
            search.setBackground(app.store.darkMode() ? new Color(0x2D2D2D) : Color.WHITE);
            search.setForeground(fg);
            search.setCaretColor(fg);
            refresh();
            repaint();
        }

        /** 화면에 보이는 카드 순서 (즐겨찾기 / 일반). 끌기 중에는 이 목록을 바꿔 가며 다시 배치 */
        final List<Card> favCards = new ArrayList<>(), normalCards = new ArrayList<>();
        boolean searching;

        void refresh() {
            if (cards == null) return;
            String q = search.getText().trim().toLowerCase();
            searching = !q.isEmpty();
            favCards.clear();
            normalCards.clear();
            for (Note n : app.activeNotes()) {
                String plain = Markup.plain(n.text);
                if (searching && !plain.toLowerCase().contains(q)) continue;
                (n.favorite ? favCards : normalCards).add(new Card(n, plain));
            }
            layoutCards();
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
            l.setForeground(app.store.darkMode() ? new Color(0xBBBBBB) : new Color(0x666666));
            l.setBorder(new EmptyBorder(4, 2, 6, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            return l;
        }

        static String when(long ticks) {
            ZonedDateTime t = Instant.ofEpochMilli(ticksToMillis(ticks)).atZone(ZoneId.systemDefault());
            return t.toLocalDate().equals(LocalDate.now()) ? t.format(TIME) : t.format(DATE);
        }

        void showSettings() {
            JRadioButton light = new JRadioButton("밝게", !app.store.darkMode());
            JRadioButton dark = new JRadioButton("어둡게", app.store.darkMode());
            ButtonGroup g = new ButtonGroup();
            g.add(light);
            g.add(dark);
            JCheckBox confirm = new JCheckBox("삭제 전에 확인", app.store.confirmDelete());
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
            p.add(new JLabel("색 모드"));
            p.add(light);
            p.add(dark);
            p.add(new JSeparator());
            p.add(confirm);
            p.add(new JSeparator());
            JButton imp = new JButton("Windows 스티커 메모 가져오기...");
            imp.setEnabled(StickyImport.available());
            if (!imp.isEnabled()) imp.setToolTipText("이 PC에서 Windows 스티커 메모 데이터를 찾지 못했습니다.");
            imp.addActionListener(e -> app.importStickyNotes(SwingUtilities.getWindowAncestor(imp), true));
            p.add(imp);
            p.add(new JLabel("<html><small>저장 위치: " + esc(app.store.dir.toString()) + "</small></html>"));
            p.add(new JLabel("<html><small>버전 " + VERSION + (Updater.installDir() != null ? " · 실행할 때 자동 업데이트" : "") + "</small></html>"));
            int r = JOptionPane.showConfirmDialog(this, p, "설정", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r == JOptionPane.OK_OPTION) {
                app.store.set("darkMode", String.valueOf(dark.isSelected()));
                app.store.set("confirmDelete", String.valueOf(confirm.isSelected()));
                applyMode();
            }
        }

        final class Card extends JPanel {
            final Note note;

            Card(Note note, String plain) {
                super(new BorderLayout());
                this.note = note;
                setOpaque(false);
                setBorder(new EmptyBorder(10, 12, 12, 8));
                setAlignmentX(LEFT_ALIGNMENT);

                Theme t = note.theme;
                JLabel time = new JLabel(when(note.updatedAt));
                time.setFont(BASE_FONT.deriveFont(11f));
                time.setForeground(t.dark ? new Color(0xCCCCCC) : new Color(0x666666));
                FlatButton more = new FlatButton("", "메뉴");
                more.setIcon(new Glyph("more", 16));
                more.setForeground(t.fg());
                more.hoverColor = t.hover();
                more.setPreferredSize(new Dimension(30, 24));
                more.addActionListener(e -> popup().show(more, 0, more.getHeight()));
                FlatButton star = new FlatButton("", note.favorite ? "즐겨찾기 해제" : "즐겨찾기 (목록 위쪽에 고정)");
                star.setIcon(new Glyph(note.favorite ? "starFilled" : "star", 16));
                star.setForeground(t.fg());
                star.hoverColor = t.hover();
                star.setPreferredSize(new Dimension(30, 24));
                star.addActionListener(e -> app.toggleFavorite(note));
                JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                tools.setOpaque(false);
                tools.add(star);
                tools.add(more);
                JPanel head = new JPanel(new BorderLayout());
                head.setOpaque(false);
                head.add(tools, BorderLayout.WEST);
                head.add(time, BorderLayout.EAST);

                String[] lines = plain.strip().split("\n");
                StringBuilder html = new StringBuilder("<html><div style='width:250px'>");
                List<String> media = Markup.mediaIds(note.text);
                if (plain.isBlank() && media.isEmpty()) html.append("<i>빈 메모</i>");
                for (int i = 0; i < Math.min(lines.length, 6); i++) {
                    String l = lines[i].length() > 120 ? lines[i].substring(0, 120) + "…" : lines[i];
                    html.append(i > 0 ? "<br>" : "").append(esc(l));
                }
                if (lines.length > 6) html.append("<br>…");
                html.append("</div></html>");
                JLabel body = new JLabel(html.toString());
                body.setFont(BASE_FONT.deriveFont(13f));
                body.setForeground(t.fg());
                body.setVerticalAlignment(SwingConstants.TOP);

                add(head, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                java.awt.image.BufferedImage thumb = media.isEmpty() ? null : app.store.loadImage(media.get(0));
                if (thumb != null) {
                    JLabel pic = new JLabel(new ScaledImageIcon(thumb, 250, 140));
                    pic.setHorizontalAlignment(SwingConstants.LEFT);
                    pic.setBorder(new EmptyBorder(6, 0, 0, 0));
                    if (media.size() > 1) {
                        pic.setText("+" + (media.size() - 1));
                        pic.setForeground(t.fg());
                        pic.setVerticalTextPosition(SwingConstants.BOTTOM);
                    }
                    add(pic, BorderLayout.SOUTH);
                }
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                MouseAdapter ma = new MouseAdapter() {
                    Point start;

                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e) && !dragging) app.openNote(note);
                    }

                    public void mousePressed(MouseEvent e) { start = e.getPoint(); maybePopup(e); }

                    /** 같은 묶음(즐겨찾기/일반) 안에서만 끌어서 순서 변경. 검색 중에는 끌기 안 됨 */
                    public void mouseDragged(MouseEvent e) {
                        if (searching || !SwingUtilities.isLeftMouseButton(e) || start == null) return;
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
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Shape clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setClip(clip);
                g2.setColor(note.theme.body);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(note.theme.header);
                g2.fillRect(0, 0, getWidth(), 4);
                if (dragging) {                                          // 끌고 있는 카드 표시
                    g2.setClip(null);
                    g2.setColor(new Color(0x2B6CD9));
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 8, 8));
                }
                g2.dispose();
                super.paintComponent(g);
            }
        }
    }
}
