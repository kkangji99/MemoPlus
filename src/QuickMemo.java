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
    static final String VERSION = "1.0.10";
    /** 자동 업데이트를 확인할 GitHub 저장소 (owner/name) */
    static final String UPDATE_REPO = "kkangji99/QuickMemo";

    public static void main(String[] args) throws Exception {
        boolean dark = false;                          // 글꼴 안티앨리어싱은 Windows(ClearType) 설정을 따른다: 회색조로 강제하면 맑은 고딕 획이 빠짐
        try (var r = Files.newBufferedReader(Store.dataDir().resolve("settings.properties"), StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            dark = Boolean.parseBoolean(p.getProperty("darkMode", "false"));
        } catch (IOException ignored) { }
        boolean d = dark;
        SwingUtilities.invokeAndWait(() -> setupLaf(d));
        if (Updater.updateIfAvailable()) return;      // 새 버전을 받아 재시작하는 경우
        SwingUtilities.invokeLater(() -> new App().start());
    }

    /**
     * 화면 테마: FlatLaf (Windows 11 느낌의 현대적인 Swing 테마). 설정 창·확인 창·버튼·스크롤바·창 제목 표시줄에 적용.
     * FlatLaf 를 못 쓰면 Windows 기본 테마로.
     */
    static void setupLaf(boolean dark) {
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

    /** 실행 중에 밝게/어둡게를 바꾸면 모든 창의 테마를 다시 적용 */
    static void switchLaf(boolean dark) {
        setupLaf(dark);
        try { com.formdev.flatlaf.FlatLaf.updateUI(); } catch (Throwable e) { for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w); }
    }

    // ===================================================================== 자동 업데이트
    /**
     * exe(jpackage app-image)로 실행될 때만 동작. 시작 시 GitHub 최신 릴리스를 확인하고,
     * 더 새 버전이면 QuickMemo-x.y.z-windows-x64.zip 을 받아 설치 폴더 내용을 교체한 뒤 다시 실행한다.
     * 메모 데이터는 %APPDATA%\QuickMemo 에 있으므로 교체 대상이 아니다.
     * (1.0.0 의 업데이터는 폴더 교체 버그가 있어 -win64.zip 만 찾는다. 새 이름으로 올려 예전 빌드가 반복 시도하지 않게 함)
     */
    static final class Updater {
        static final String API = Optional.ofNullable(System.getenv("QUICKMEMO_UPDATE_API")).orElse("https://api.github.com");
        static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
        static final Pattern ZIP = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+-windows-x64\\.zip)\"");
        /** 업데이트에 실패한 뒤 다시 시도하기까지 기다리는 시간 */
        static final long RETRY_AFTER_MS = 60 * 60 * 1000L;

        /** 마지막 업데이트 시도 기록: 목표 버전과 시각 */
        static Path attemptFile() { return Store.dataDir().resolve("update-attempt.properties"); }

        static Properties readAttempt() {
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(attemptFile(), StandardCharsets.UTF_8)) {
                p.load(r);
                return p;
            } catch (IOException e) {
                return null;
            }
        }

        static void writeAttempt(Properties p) {
            try {
                Files.createDirectories(attemptFile().getParent());
                try (var w = Files.newBufferedWriter(attemptFile(), StandardCharsets.UTF_8)) { p.store(w, null); }
            } catch (IOException ignored) { }
        }

        /**
         * 직전 시도 결과 확인. 목표 버전에 도달했으면 기록을 지우고 true,
         * 실패한 지 얼마 안 됐으면 (한 번만 안내하고) false — 실패한 업데이트를 계속 반복하지 않기 위함.
         */
        static boolean mayAttempt() {
            Properties p = readAttempt();
            if (p == null) return true;
            String target = p.getProperty("target", "0");
            long at = Long.parseLong(p.getProperty("time", "0"));
            if (compare(VERSION, target) >= 0) {
                log("updated to " + VERSION);
                try { Files.deleteIfExists(attemptFile()); } catch (IOException ignored) { }
                return true;
            }
            if (System.currentTimeMillis() - at >= RETRY_AFTER_MS) return true;
            log("previous update to " + target + " failed, retry after 1 hour");
            if (!"1".equals(p.getProperty("notified"))) {
                p.setProperty("notified", "1");
                writeAttempt(p);
                try {
                    SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                            "QuickMemo " + target + " 업데이트를 적용하지 못해 현재 버전(" + VERSION + ")으로 실행합니다.\n"
                                    + "QuickMemo 폴더를 연 탐색기 창이 있으면 닫아 주세요. 1시간 뒤 실행할 때 다시 시도합니다.\n"
                                    + "자세한 내용: " + Store.dataDir().resolve("update.log"),
                            "QuickMemo 업데이트", JOptionPane.WARNING_MESSAGE));
                } catch (Exception ignored) { }
            }
            return false;
        }

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
                if (!mayAttempt()) return false;
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
                Properties attempt = new Properties();
                attempt.setProperty("target", r.version());
                attempt.setProperty("from", VERSION);
                attempt.setProperty("time", String.valueOf(System.currentTimeMillis()));
                writeAttempt(attempt);
                log("applying " + VERSION + " -> " + r.version());
                // 스크립트의 작업 폴더가 설치 폴더 안이면 그 폴더를 붙잡아 교체가 실패하므로 임시 폴더에서 실행
                new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                        "-File", script.toString(),
                        "-OldPid", String.valueOf(ProcessHandle.current().pid()),
                        "-Src", newApp.toString(), "-Dst", dir.toString(), "-Staging", staging.toString(),
                        "-Log", Store.dataDir().resolve("update.log").toString())
                        .directory(script.getParent().toFile())
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

        /**
         * 앱 종료를 기다렸다가 설치 폴더의 "내용"을 새 버전으로 바꾸고 다시 실행.
         * 폴더 자체를 옮기지 않으므로 탐색기가 그 폴더를 열고 있어도 된다. 실패하면 옮긴 것을 되돌린다.
         */
        static final String SCRIPT = String.join("\r\n",
                "param([int]$OldPid, [string]$Src, [string]$Dst, [string]$Staging, [string]$Log)",
                "function Log($m) { try { Add-Content -LiteralPath $Log -Value ((Get-Date -Format s) + ' [updater] ' + $m) -Encoding UTF8 } catch {} }",
                "try { Wait-Process -Id $OldPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}",
                "Start-Sleep -Milliseconds 500",
                "$bak = \"$Dst.old\"",
                "Remove-Item -LiteralPath $bak -Recurse -Force -ErrorAction SilentlyContinue",
                "New-Item -ItemType Directory -Path $bak -Force | Out-Null",
                "$moved = @()",
                "$ok = $true",
                "foreach ($item in @(Get-ChildItem -LiteralPath $Dst -Force)) {",
                "  $done = $false",
                "  for ($i = 0; $i -lt 10 -and -not $done; $i++) {",
                "    try { Move-Item -LiteralPath $item.FullName -Destination $bak -ErrorAction Stop; $done = $true } catch { Start-Sleep -Milliseconds 700 }",
                "  }",
                "  if (-not $done) { $ok = $false; Log \"cannot move $($item.Name) (in use)\"; break }",
                "  $moved += $item.Name",
                "}",
                "if ($ok) {",
                "  try {",
                "    foreach ($n in @(Get-ChildItem -LiteralPath $Src -Force)) { Move-Item -LiteralPath $n.FullName -Destination $Dst -ErrorAction Stop }",
                "    Log 'swapped to new version'",
                "  } catch {",
                "    $ok = $false; Log \"install failed: $_\"",
                "    Get-ChildItem -LiteralPath $Dst -Force | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue",
                "  }",
                "}",
                "if (-not $ok) {",
                "  foreach ($n in $moved) { Move-Item -LiteralPath (Join-Path $bak $n) -Destination $Dst -ErrorAction SilentlyContinue }",
                "  Log 'kept previous version'",
                "}",
                "Start-Process -FilePath (Join-Path $Dst 'QuickMemo.exe') -WorkingDirectory $Dst",
                "if ($ok -or -not @(Get-ChildItem -LiteralPath $bak -Force)) { Remove-Item -LiteralPath $bak -Recurse -Force -ErrorAction SilentlyContinue }",
                "Remove-Item -LiteralPath $Staging -Recurse -Force -ErrorAction SilentlyContinue",
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
    // ===================================================================== 디자인 스타일
    /** 화면 스타일: 심플(기본, 원래 스티커 메모 느낌) / 아기자기(파스텔·둥근 모양·마스킹테이프) / 크리스마스(아기자기 + 빨강·초록, 가랜드·눈송이) */
    enum Style {
        Simple("심플"), Cute("아기자기"), Christmas("크리스마스");

        final String label;

        Style(String label) { this.label = label; }

        static Style parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return Simple; }
        }
    }

    static volatile Style STYLE = Style.Simple;

    /** 둥근 모양·통통한 선 등 아기자기 계열 (크리스마스 포함) */
    static boolean cute() { return STYLE == Style.Cute || STYLE == Style.Christmas; }

    static boolean xmas() { return STYLE == Style.Christmas; }

    static final Color XMAS_RED = new Color(0xD63A3A), XMAS_GREEN = new Color(0x2E8B57), XMAS_GOLD = new Color(0xE8B83A);

    /** 스타일 포인트 색: 아기자기 분홍 / 크리스마스 빨강 */
    static Color styleAccent() { return xmas() ? XMAS_RED : PINK; }

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

        void useStyle(Style s) {
            if (s == Style.Christmas) {
                Object[] x = XMAS[ordinal()];
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
        Color menuHover() { return dark ? new Color(255, 255, 255, 28) : xmas() ? new Color(214, 58, 58, 40) : cute() ? new Color(255, 160, 190, 50) : new Color(0, 0, 0, 18); }

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
        Cloud("구름", .08, .08, .25, .08, 300, 230),
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
                    case "Shape" -> n.shape = NoteShape.parse(v);
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
            if (n.shape != NoteShape.Square) sb.append("Shape=").append(n.shape.name()).append('\n');
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

        /** 메모 파일을 지운다 (아무것도 쓰지 않은 메모를 남기지 않을 때) */
        void remove(Note n) {
            try { Files.deleteIfExists(notesDir.resolve(n.id + ".note")); } catch (IOException ignored) { }
        }
    }

    /** 글도 그림도 없는 메모 */
    static boolean isEmptyNote(Note n) {
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
            Color fg = c != null ? c.getForeground() : Color.DARK_GRAY;
            g2.setColor(fg);
            g2.translate(x, y);
            float s = size;
            // 통통하고 끝이 둥근 선 = 아기자기한 느낌
            g2.setStroke(cute() ? new BasicStroke(Math.max(1.6f, s / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    : new BasicStroke(Math.max(1.4f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case "plus" -> { line(g2, .24, .5, .76, .5); line(g2, .5, .24, .5, .76); }
                case "close" -> { line(g2, .3, .3, .7, .7); line(g2, .7, .3, .3, .7); }
                case "more" -> { for (double cx : new double[]{.22, .5, .78}) dot(g2, cx, .52, .095); }
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
                    Shape h = NoteShape.Heart.outline(s * .1, s * .14, s * .8, s * .74);
                    g2.setColor(PINK);
                    g2.fill(h);
                }
                case "tree" -> {                                     // 크리스마스 트리: 3단 초록 + 금별 + 빨간 장식
                    g2.setColor(new Color(0x8B5A2B));
                    g2.fill(new java.awt.geom.Rectangle2D.Double(s * .44, s * .8, s * .12, s * .14));
                    g2.setColor(XMAS_GREEN);
                    for (double[] tier : new double[][]{{.16, .24, .5}, {.32, .16, .66}, {.5, .08, .84}}) {
                        java.awt.geom.Path2D.Double tri = new java.awt.geom.Path2D.Double();
                        tri.moveTo(s * .5, s * tier[0]);
                        tri.lineTo(s * (.5 + tier[2] / 2 + .04), s * (tier[0] + .34 - tier[1] * .2));
                        tri.lineTo(s * (.5 - tier[2] / 2 - .04), s * (tier[0] + .34 - tier[1] * .2));
                        tri.closePath();
                        g2.fill(tri);
                    }
                    g2.setColor(XMAS_RED);
                    dot(g2, .4, .52, .045);
                    dot(g2, .62, .68, .045);
                    g2.setColor(XMAS_GOLD);
                    g2.fill(NoteShape.Star.outline(s * .38, s * .02, s * .24, s * .22));
                }
                case "star", "starFilled" -> {
                    Shape st = NoteShape.Star.outline(s * .06, s * .04, s * .88, s * .88);
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
                    if (!cute()) {
                        poly(g2, new double[]{.38, .62, .62, .72, .28, .38}, new double[]{.14, .14, .48, .62, .62, .48});
                        line(g2, .5, .62, .5, .88);
                        break;
                    }
                    line(g2, .5, .55, .5, .9);
                    g2.setColor(styleAccent());
                    g2.fill(new java.awt.geom.Ellipse2D.Double(s * .22, s * .08, s * .56, s * .5));
                    g2.setColor(new Color(255, 255, 255, 170));
                    g2.fill(new java.awt.geom.Ellipse2D.Double(s * .33, s * .17, s * .14, s * .12));
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
                    g2.setColor(cute() ? new Color(0xFFC93C) : fg);
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
            // 메모 테마의 포인트 색 (JTextPane 의 client property "accent")
            Color accent = c instanceof JComponent jc && jc.getClientProperty("accent") instanceof Color a ? a : fg;
            double arc = cute() ? 7 : 4;
            java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Double(x + 0.75, y + 1.75, BOX - 1.5, BOX - 1.5, arc, arc);
            if (!cute()) {                                         // 심플: 회색 테두리 / 채운 회색 + 흰 체크
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
            applyStyle(Style.parse(store.settings.getProperty("uiStyle", Style.Simple.name())));   // 기본은 심플
            notes.addAll(store.loadAll());
            for (Note n : new ArrayList<>(notes))                   // 예전에 남은 빈 메모(닫힌 것) 정리
                if (!n.open && !n.deleted() && isEmptyNote(n)) { notes.remove(n); store.remove(n); }
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

        /** 밝게/어둡게: 설정 저장 + 창 테마(FlatLaf) 전환 + 열린 창 다시 칠하기 */
        void setDark(boolean dark) {
            store.set("darkMode", String.valueOf(dark));
            switchLaf(dark);
            for (NoteWindow w : windows.values()) w.applyTheme();
            list.applyMode();
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
        /** 그림자를 그릴 바깥 여백 (이 여백 가장자리를 잡으면 크기 조절) */
        static final int M = 12, EDGE = 10, RING = 14, HEADER_H = 34;
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
            pane.setFont(BASE_FONT);
            pane.setBorder(new EmptyBorder(6, 12, 6, 10));
            pane.setOpaque(false);                         // 스티커 모양(ShapePanel)이 배경을 그림
            doc = pane.getStyledDocument();
            pane.setTransferHandler(new NoteTransfer());   // 이미지 붙여넣기·끌어놓기
            scroll =new JScrollPane(pane, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
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
            Point p = anchor.getLocationOnScreen();
            Rectangle scr = getGraphicsConfiguration().getBounds();
            int x = Math.max(scr.x, Math.min(p.x + anchor.getWidth() - popup.getWidth() + 10, scr.x + scr.width - popup.getWidth()));
            int y = p.y + anchor.getHeight() + 2;
            if (y + popup.getHeight() > scr.y + scr.height) y = p.y - popup.getHeight() - 2;
            popup.setLocation(x, y);
            popup.setVisible(true);
            popup.toFront();
            popup.getContentPane().requestFocusInWindow();
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
                int bandBottom = getInsets().top + HEADER_H - 2;
                g2.setColor(t.header);
                g2.fillRect(0, 0, getWidth(), bandBottom);
                if (xmas()) {
                    paintSnow(g2, t, bandBottom);
                    paintGarland(g2, bandBottom);
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

            /** 크리스마스: 본문에 은은한 눈송이 (메모마다 같은 자리) */
            void paintSnow(Graphics2D g2, Theme t, int top) {
                Random rnd = new Random(note.id.hashCode());
                int w = getWidth(), h = getHeight(), n = Math.max(6, w * (h - top) / 9000);
                Color c = t.dark ? new Color(255, 255, 255, 55) : new Color(t.accent.getRed(), t.accent.getGreen(), t.accent.getBlue(), 38);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < n; i++) {
                    double x = rnd.nextDouble() * w, y = top + 8 + rnd.nextDouble() * Math.max(1, h - top - 16), r = 4 + rnd.nextDouble() * 5;
                    for (int k = 0; k < 3; k++) {
                        double a = Math.PI / 3 * k + Math.PI / 6;
                        g2.draw(new java.awt.geom.Line2D.Double(x - Math.cos(a) * r, y - Math.sin(a) * r, x + Math.cos(a) * r, y + Math.sin(a) * r));
                    }
                }
            }

            /** 크리스마스: 색 띠 아래로 늘어진 줄에 매달린 오너먼트 */
            void paintGarland(Graphics2D g2, int bandBottom) {
                int w = getWidth(), step = 34;
                double y0 = bandBottom - 3, sag = 6;
                java.awt.geom.Path2D.Double rope = new java.awt.geom.Path2D.Double();
                rope.moveTo(0, y0);
                for (int x = 0; x < w; x += step) rope.quadTo(x + step / 2.0, y0 + sag * 2, x + step, y0);
                g2.setColor(new Color(0x2E6B45));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(rope);
                Color[] cs = {XMAS_RED, XMAS_GOLD, XMAS_GREEN};
                for (int i = 0, x = step / 2; x < w; i++, x += step) {
                    double cx = x, cy = y0 + sag + 6, r = 5;
                    g2.setColor(new Color(0xB8B8B8));
                    g2.fill(new java.awt.geom.Rectangle2D.Double(cx - 2, cy - r - 3, 4, 3));
                    g2.setColor(cs[i % 3]);
                    g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                    g2.setColor(new Color(255, 255, 255, 170));
                    g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r * .5, cy - r * .55, r * .6, r * .5));
                }
            }
        }

        // ---------- 색깔·모양·메뉴 팝업
        /** ⋯ 를 누르면 뜨는 둥근 팝업. 바깥을 누르면 닫힘 */
        final class MenuPopup extends JWindow {
            MenuPopup() {
                super(NoteWindow.this);
                Theme t = note.theme;
                if (TRANSLUCENT) setBackground(new Color(0, 0, 0, 0));
                JPanel root = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        int w = getWidth(), h = getHeight(), arc = cute() ? 24 : 10;
                        if (TRANSLUCENT) for (int i = 5; i >= 1; i--) {
                            g2.setColor(cute() ? new Color(120, 80, 60, 9) : new Color(0, 0, 0, 8));
                            g2.fillRoundRect(6 - i, 6 - i + 2, w - 12 + 2 * i, h - 12 + 2 * i, arc + 2 + i, arc + 2 + i);
                        }
                        g2.setColor(t.menuBg());
                        g2.fillRoundRect(6, 6, w - 12, h - 12, arc, arc);
                        g2.setColor(cute() ? new Color(t.accent.getRed(), t.accent.getGreen(), t.accent.getBlue(), 90)
                                : new Color(0, 0, 0, t.dark ? 120 : 40));
                        g2.setStroke(new BasicStroke(1.2f));
                        g2.drawRoundRect(6, 6, w - 13, h - 13, arc, arc);
                        g2.dispose();
                    }
                };
                root.setOpaque(!TRANSLUCENT);
                root.setBackground(t.menuBg());
                root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
                root.setBorder(new EmptyBorder(16, 18, 14, 18));
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
            return xmas() ? (d ? new Color(0x14261D) : new Color(0xFBF6EE)) : cute() ? (d ? new Color(0x2A2633) : CREAM) : (d ? new Color(0x202020) : new Color(0xF3F3F3));
        }

        Color fg() {
            boolean d = app.store.darkMode();
            return cute() ? (d ? new Color(0xFFF4F8) : new Color(0x5A4636)) : (d ? new Color(0xF0F0F0) : new Color(0x1F1F1F));
        }

        void applyMode() {
            Color bg = bg(), fg = fg();
            getContentPane().setBackground(bg);
            for (Component c : List.of(scroll.getViewport(), (Component) scroll.getViewport().getView(), cards)) c.setBackground(bg);
            title.setForeground(fg);
            title.setIcon(xmas() ? new Glyph("tree", 22) : cute() ? new Glyph("heart", 20) : null);
            for (FlatButton b : buttons) {
                b.setForeground(fg);
                b.setBackground(bg);
                b.hoverColor = app.store.darkMode() ? new Color(255, 255, 255, 40) : xmas() ? new Color(214, 58, 58, 45) : cute() ? new Color(255, 160, 190, 60) : new Color(0, 0, 0, 28);
            }
            scroll.getVerticalScrollBar().setUI(new CuteScrollBarUI());
            scroll.getVerticalScrollBar().putClientProperty("accent", cute() ? styleAccent() : new Color(0xB0B0B0));
            search.setBackground(app.store.darkMode() ? (cute() ? new Color(0x37323F) : new Color(0x2D2D2D)) : Color.WHITE);
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
            l.setForeground(xmas() ? (app.store.darkMode() ? new Color(0xF28B8B) : new Color(0xC23434)) : cute() ? (app.store.darkMode() ? new Color(0xFFB0CC) : new Color(0xE07799))
                    : (app.store.darkMode() ? new Color(0xBBBBBB) : new Color(0x666666)));
            l.setBorder(new EmptyBorder(4, 2, 6, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            return l;
        }

        static String when(long ticks) {
            ZonedDateTime t = Instant.ofEpochMilli(ticksToMillis(ticks)).atZone(ZoneId.systemDefault());
            return t.toLocalDate().equals(LocalDate.now()) ? t.format(TIME) : t.format(DATE);
        }

        void showSettings() {
            new SettingsDialog().setVisible(true);
        }

        /** 설정 창: 섹션 카드 + 미리보기 선택 + 토글. 바꾸면 바로 적용·저장 (확인 버튼 없음) */
        final class SettingsDialog extends JDialog {
            SettingsDialog() {
                super(NotesListWindow.this, "설정", true);
                setDefaultCloseOperation(DISPOSE_ON_CLOSE);
                build();
                setSize(480, 700);
                setLocationRelativeTo(NotesListWindow.this);
                NoteWindow.bind(getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW), getRootPane().getActionMap(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::dispose);
            }

            boolean dark() { return app.store.darkMode(); }
            Color accent() { return cute() ? styleAccent() : dark() ? new Color(0x4CC2FF) : new Color(0x0067C0); }
            Color pageBg() { return dark() ? new Color(0x202020) : new Color(0xF3F3F3); }
            Color cardBg() { return dark() ? new Color(0x2B2B2B) : Color.WHITE; }
            Color text() { return dark() ? new Color(0xF0F0F0) : new Color(0x1B1B1B); }
            Color sub() { return dark() ? new Color(0xA8A8A8) : new Color(0x6B6B6B); }

            /** 색·스타일이 바뀌면 내용을 다시 만든다 */
            void build() {
                JPanel page = new TrackWidthPanel();               // 창 너비에 맞춰 배치
                page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
                page.setBackground(pageBg());
                page.setBorder(new EmptyBorder(16, 18, 16, 18));

                // 디자인
                JPanel styles = new JPanel(new GridLayout(1, 3, 8, 0));
                styles.setOpaque(false);
                styles.add(new StyleCard(Style.Simple, "심플", "깔끔한 기본"));
                styles.add(new StyleCard(Style.Cute, "아기자기", "파스텔 · 테이프"));
                styles.add(new StyleCard(Style.Christmas, "크리스마스", "트리 · 눈송이"));
                page.add(section("디자인", styles));

                // 화면
                page.add(section("화면 모드", row("밝기", "메모 목록과 창의 밝기", new Segmented(new String[]{"밝게", "어둡게"}, dark() ? 1 : 0, i -> {
                    if ((i == 1) != dark()) { app.setDark(i == 1); rebuild(); }
                }))));

                // 동작
                page.add(section("동작", row("삭제 전에 확인", "메모를 지울 때 한 번 더 묻기", new ToggleSwitch(app.store.confirmDelete(), on ->
                        app.store.set("confirmDelete", String.valueOf(on))))));

                // 가져오기
                JButton imp = new JButton("가져오기");
                imp.setEnabled(StickyImport.available());
                imp.addActionListener(e -> app.importStickyNotes(this, true));
                page.add(section("가져오기", row("Windows 스티커 메모", StickyImport.available()
                        ? "이 PC의 스티커 메모를 QuickMemo로 복사" : "이 PC에서 스티커 메모를 찾지 못했습니다", imp)));

                // 정보
                JButton open = new JButton("폴더 열기");
                open.addActionListener(e -> { try { Desktop.getDesktop().open(app.store.dir.toFile()); } catch (Exception ignored) { } });
                page.add(section("정보", row("버전 " + VERSION, Updater.installDir() != null ? "실행할 때 새 버전을 자동으로 받습니다" : "개발용 실행 (자동 업데이트 꺼짐)", null),
                        row("메모 저장 위치", app.store.dir.toString().replace(System.getProperty("user.home"), "~"), open)));

                JScrollPane sp = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                sp.setBorder(null);
                sp.getViewport().setBackground(pageBg());
                sp.getVerticalScrollBar().setUnitIncrement(16);

                // 스크롤과 상관없이 항상 보이는 아래쪽 닫기 바
                JButton close = new JButton("닫기");
                close.addActionListener(e -> dispose());
                close.setPreferredSize(new Dimension(96, 32));
                JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                bottom.setBackground(dark() ? new Color(0x1A1A1A) : new Color(0xEBEBEB));
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
                wrap.add(t);
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

            /** 디자인 선택 카드: 작은 메모 미리보기 + 이름 */
            final class StyleCard extends JComponent {
                final Style style;
                final String name, desc;

                StyleCard(Style style, String name, String desc) {
                    this.style = style;
                    this.name = name;
                    this.desc = desc;
                    setPreferredSize(new Dimension(130, 128));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        public void mouseClicked(MouseEvent e) {
                            if (STYLE == style) return;
                            app.store.set("uiStyle", style.name());
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
                    g2.setColor(dark() ? new Color(0x333333) : new Color(0xF7F7F7));
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
                    } else if (style == Style.Christmas) {
                        g2.setColor(new Color(0xFFEDED));
                        g2.fillRoundRect(px, py, pw, ph, 16, 16);
                        g2.setClip(new java.awt.geom.RoundRectangle2D.Float(px, py, pw, ph, 16, 16));
                        g2.setColor(new Color(0xF5AFAF));
                        g2.fillRect(px, py, pw, 13);
                        g2.setColor(new Color(0x2E6B45));                  // 가랜드
                        g2.setStroke(new BasicStroke(1.4f));
                        g2.draw(new java.awt.geom.QuadCurve2D.Double(px, py + 12, px + pw / 2.0, py + 20, px + pw, py + 12));
                        Color[] cs = {XMAS_RED, XMAS_GOLD, XMAS_GREEN, XMAS_RED};
                        for (int k = 0; k < 4; k++) {
                            g2.setColor(cs[k]);
                            g2.fillOval(px + 9 + k * 17, py + 14 + (k == 1 || k == 2 ? 2 : 0), 7, 7);
                        }
                        g2.setClip(null);
                        new Glyph("tree", 18).paintIcon(this, g2, px + pw - 24, py + ph - 22);
                        g2.setColor(new Color(0xC9A99A));
                        g2.fillRoundRect(px + 9, py + 30, 36, 4, 4, 4);
                        g2.fillRoundRect(px + 9, py + 40, 26, 4, 4, 4);
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
                    setPreferredSize(new Dimension(140, 32));
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
                    g2.setColor(dark() ? new Color(0x3A3A3A) : new Color(0xEDEDED));
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

                Theme t = note.theme;
                JLabel time = new JLabel(when(note.updatedAt));
                time.setFont(BASE_FONT.deriveFont(11f));
                time.setForeground(new Color(t.fg().getRed(), t.fg().getGreen(), t.fg().getBlue(), 150));
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
