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
 * exe(jpackage app-image)로 실행될 때만 동작. 시작 시 GitHub 최신 릴리스를 확인하고,
 * 더 새 버전이면 QuickMemo-x.y.z-windows-x64.zip 을 받아 설치 폴더 내용을 교체한 뒤 다시 실행한다.
 * 메모 데이터는 %APPDATA%\QuickMemo 에 있으므로 교체 대상이 아니다.
 * (1.0.0 의 업데이터는 폴더 교체 버그가 있어 -win64.zip 만 찾는다. 새 이름으로 올려 예전 빌드가 반복 시도하지 않게 함)
 */
final class Updater {
    static final String API = Optional.ofNullable(System.getenv("QUICKMEMO_UPDATE_API")).orElse("https://api.github.com");
    static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    static final Pattern ZIP = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+-windows-x64\\.zip)\"");
    /** 업데이트에 실패한 뒤 다시 시도하기까지 기다리는 시간 */
    static final long RETRY_AFTER_MS = 60 * 60 * 1000L;

    /** 마지막 업데이트 시도 기록: 목표 버전과 시각 */
    static Path attemptFile() { return QuickMemo.Store.dataDir().resolve("update-attempt.properties"); }

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
        if (compare(QuickMemo.VERSION, target) >= 0) {
            log("updated to " + QuickMemo.VERSION);
            try { Files.deleteIfExists(attemptFile()); } catch (IOException ignored) { }
            return true;
        }
        if (System.currentTimeMillis() - at >= RETRY_AFTER_MS) return true;
        log("previous update to " + target + " failed, retry after 1 hour");
        if (!"1".equals(p.getProperty("notified"))) {
            p.setProperty("notified", "1");
            writeAttempt(p);
            try {
                SwingUtilities.invokeAndWait(() -> Ask.notice(null, Ask.Kind.WARN,
                        target + " 업데이트를 적용하지 못했어요",
                        "지금 버전(" + QuickMemo.VERSION + ")으로 실행해요.\nQuickMemo 폴더를 연 탐색기 창이 있으면 닫아 주세요.\n1시간 뒤 실행할 때 다시 시도해요."));
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** sha256 은 GitHub 이 자산마다 알려 주는 digest (없으면 null: 예전 릴리스) */
    record Release(String version, String zipUrl, String sha256) {}

    /** 이 저장소의 릴리스 다운로드 주소만 받는다 (API 응답이 조작돼도 다른 곳의 파일을 설치하지 않게) */
    static boolean trustedDownload(String url) {
        return url.startsWith("https://github.com/" + QuickMemo.UPDATE_REPO + "/releases/download/") && !url.contains("..");
    }

    static final long MAX_DOWNLOAD = 300L << 20, MAX_UNZIPPED = 800L << 20;
    static final int MAX_ENTRIES = 20000;

    /** QuickMemo.exe 로 실행 중이면 그 설치 폴더, java/javaw 로 jar 를 실행 중이면 null */
    static Path installDir() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null) exe = ProcessHandle.current().info().command().orElse(null);
        if (exe == null || !Paths.get(exe).getFileName().toString().equalsIgnoreCase("QuickMemo.exe")) return null;
        return Paths.get(exe).toAbsolutePath().getParent();
    }

    static boolean updateIfAvailable() {
        Path dir = installDir();
        if (dir == null || QuickMemo.UPDATE_REPO.isBlank()) return false;
        try {
            if (!mayAttempt()) return false;
            Release r = latest();
            log("installed " + QuickMemo.VERSION + " at " + dir + ", latest " + (r == null ? "none" : r.version()));
            if (r == null || compare(r.version(), QuickMemo.VERSION) <= 0) return false;
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
            Path f = QuickMemo.Store.dataDir().resolve("update.log");
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
                .header("User-Agent", "QuickMemo/" + QuickMemo.VERSION)
                .header("Accept", "application/vnd.github+json");
    }

    static Release latest() throws Exception {
        var res = client().send(request(API + "/repos/" + QuickMemo.UPDATE_REPO + "/releases/latest")
                .timeout(java.time.Duration.ofSeconds(5)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        String body = res.body();
        Matcher tag = TAG.matcher(body), zip = ZIP.matcher(body);
        if (!tag.find() || !zip.find()) return null;
        if (!trustedDownload(zip.group(1))) { log("untrusted download url, skip: " + zip.group(1)); return null; }
        // 같은 자산 객체 안의 digest (자산 이름부터 다운로드 주소 사이)
        int nameAt = body.lastIndexOf("\"name\"", zip.start());
        Matcher dg = Pattern.compile("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"")
                .matcher(body.substring(Math.max(0, nameAt), zip.start()));
        return new Release(tag.group(1), zip.group(1), dg.find() ? dg.group(1).toLowerCase() : null);
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
            Path dir = QuickMemo.Store.dataDir();
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
            String sha = download(r.zipUrl(), zip, pw);
            if (r.sha256() != null && !r.sha256().equals(sha)) {
                Files.deleteIfExists(zip);
                throw new IOException("받은 파일이 손상되었거나 바뀌었습니다 (SHA-256 불일치)");
            }
            log("download sha256 " + sha + (r.sha256() != null ? " (verified)" : " (no digest published)"));
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
            attempt.setProperty("from", QuickMemo.VERSION);
            attempt.setProperty("time", String.valueOf(System.currentTimeMillis()));
            writeAttempt(attempt);
            log("applying " + QuickMemo.VERSION + " -> " + r.version());
            // 스크립트의 작업 폴더가 설치 폴더 안이면 그 폴더를 붙잡아 교체가 실패하므로 임시 폴더에서 실행
            new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(),
                    "-OldPid", String.valueOf(ProcessHandle.current().pid()),
                    "-Src", newApp.toString(), "-Dst", dir.toString(), "-Staging", staging.toString(),
                    "-Log", QuickMemo.Store.dataDir().resolve("update.log").toString())
                    .directory(script.getParent().toFile())
                    .start();
            System.exit(0);
            return true;
        } catch (Exception e) {
            log("apply failed: " + e);
            pw.close();
            try { deleteTree(staging); } catch (IOException ignored) { }
            try {
                SwingUtilities.invokeAndWait(() -> Ask.notice(null, Ask.Kind.WARN, "업데이트하지 못했어요",
                        "지금 버전(" + QuickMemo.VERSION + ")으로 실행해요.\n" + e.getMessage()));
            } catch (Exception ignored) { }
            return false;
        }
    }

    /** 받아서 저장하고 SHA-256 (소문자 16진수) 을 돌려준다. 너무 크면 중단 */
    static String download(String url, Path to, Progress pw) throws Exception {
        var res = client().send(request(url).header("Accept", "application/octet-stream").build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("다운로드 실패 (HTTP " + res.statusCode() + ")");
        long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (total > MAX_DOWNLOAD) throw new IOException("업데이트 파일이 너무 큽니다");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = res.body(); var out = Files.newOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (done > MAX_DOWNLOAD) throw new IOException("업데이트 파일이 너무 큽니다");
                if (total > 0) pw.set((int) (done * 100 / total));
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        long written = 0;
        int entries = 0;
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("압축 항목이 너무 많습니다");
                String name = e.getName().replace('\\', '/');      // PowerShell 5 Compress-Archive 는 역슬래시를 씀
                if (name.contains(":")) throw new IOException("잘못된 압축 항목: " + e.getName());   // 드라이브·대체 데이터 스트림
                Path p;
                try { p = root.resolve(name).normalize(); } catch (InvalidPathException ex) { throw new IOException("잘못된 압축 항목: " + e.getName()); }
                if (!p.startsWith(root)) throw new IOException("잘못된 압축 항목: " + e.getName());
                if (name.endsWith("/")) Files.createDirectories(p);
                else {
                    Files.createDirectories(p.getParent());
                    try (var out = Files.newOutputStream(p)) {          // 압축 폭탄 방지: 풀린 크기 합계 제한
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            written += n;
                            if (written > MAX_UNZIPPED) throw new IOException("압축을 푼 크기가 너무 큽니다");
                            out.write(buf, 0, n);
                        }
                    }
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
                    l.setFont(QuickMemo.BASE_FONT);
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
