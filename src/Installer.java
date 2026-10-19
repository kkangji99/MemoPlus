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
 * MSI 없이 스스로 설치: 압축을 푼 곳에서 실행 중이면 %LOCALAPPDATA%\Programs\MemoPlus 로 복사해 다시 실행.
 * exe 로 실행될 때마다 "앱 및 기능" 목록(HKCU Uninstall)에 등록하고, 제거는 MemoPlus.exe --uninstall.
 * 제거는 앱 파일(MemoPlus.exe, app, runtime)만 지우고 메모(%APPDATA%\MemoPlus)는 남긴다.
 */
final class Installer {
    /** 테스트에서 실제 레지스트리를 건드리지 않게 바꿀 수 있음 (exe 시험은 환경 변수로). HKCU\Software 아래만 */
    static final String KEY = testKey(Optional.ofNullable(System.getProperty("memoplus.uninstallKey")).orElse(System.getenv("MEMOPLUS_UNINSTALL_KEY")));

    static String testKey(String k) {
        return k != null && k.startsWith("HKCU\\Software\\") && !k.contains("..") ? k : "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MemoPlus";
    }
    /**
     * jpackage app-image 의 내용. 제거할 때 이것만 지운다 (다른 파일이 있는 폴더를 통째로 지우지 않게).
     * QuickMemo.exe·QuickMemo.ico 는 예전 이름 설치본(1.0.x, 1.1.0~1.1.1)에 남아 있을 수 있는 파일
     */
    static final String[] APP_FILES = {"MemoPlus.exe", "QuickMemo.exe", "MemoPlus.ico", "QuickMemo.ico", "app", "runtime"};

    /** 표준 설치 위치 */
    static Path home() {
        String la = System.getenv("LOCALAPPDATA");
        return la == null ? null : Paths.get(la, "Programs", "MemoPlus");
    }

    /** exe 로 실행 중인데 표준 위치가 아닌 곳(다운로드 폴더 등)이면 true */
    static boolean canInstall() {
        Path dir = Updater.installDir(), h = home();
        return dir != null && h != null && !dir.toAbsolutePath().normalize().equals(h.toAbsolutePath().normalize());
    }

    /** 메모플러스 앱 폴더가 맞는지 (exe + app\*.jar + runtime). 1.0.x 의 QuickMemo 폴더 모양도 인정 */
    static boolean isAppDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir.resolve("runtime"))) return false;
        return Files.isRegularFile(dir.resolve("MemoPlus.exe")) && Files.isRegularFile(dir.resolve("app").resolve("MemoPlus.jar"))
                || Files.isRegularFile(dir.resolve("QuickMemo.exe")) && Files.isRegularFile(dir.resolve("app").resolve("QuickMemo.jar"));
    }

    /** 지금 실행 중인 앱 폴더를 표준 위치로 복사 (있던 앱 파일은 바꿈) */
    static Path copyToHome() throws IOException {
        Path src = Updater.installDir(), dst = home();
        if (!isAppDir(src) || dst == null) throw new IOException("설치할 메모플러스 파일을 찾지 못했어요.");
        Path staging = dst.resolveSibling("MemoPlus.install");
        Updater.deleteTree(staging);
        Path root = src.toAbsolutePath().normalize();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                Path rel = root.relativize(p);
                if (rel.getNameCount() > 0 && !Arrays.asList(APP_FILES).contains(rel.getName(0).toString())) continue;
                Path to = staging.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.createDirectories(dst);
        for (String f : APP_FILES) {                                // 있던 설치본의 앱 파일만 바꾼다
            Updater.deleteTree(dst.resolve(f));
            if (Files.exists(staging.resolve(f))) Files.move(staging.resolve(f), dst.resolve(f));
        }
        Updater.deleteTree(staging);
        return dst;
    }

    /** "앱 및 기능" 목록에 등록 (버전·위치가 바뀌면 다시) */
    static boolean register(Path dir) {
        String exe = Updater.exeIn(dir).toString();
        String[][] values = {{"DisplayName", "REG_SZ", MemoPlus.APP_NAME + " (" + MemoPlus.APP_NAME_EN + ")"}, {"DisplayVersion", "REG_SZ", MemoPlus.VERSION},
                {"Publisher", "REG_SZ", "MemoPlus"},
                {"DisplayIcon", "REG_SZ", exe}, {"InstallLocation", "REG_SZ", dir.toString()},
                {"UninstallString", "REG_SZ", "\"" + exe + "\" --uninstall"}, {"NoModify", "REG_DWORD", "1"}, {"NoRepair", "REG_DWORD", "1"}};
        boolean ok = true;
        // 값 안의 큰따옴표는 \" 로 넘겨야 reg 가 받는다 (안 그러면 경로에 공백이 있을 때 제거 명령이 깨짐)
        for (String[] v : values) ok &= reg("add", KEY, "/v", v[0], "/t", v[1], "/d", v[2].replace("\"", "\\\""), "/f");
        return ok;
    }

    static boolean unregister() { return reg("delete", KEY, "/f"); }

    static boolean registered() { return reg("query", KEY, "/v", "UninstallString"); }

    /** reg.exe 실행 (인자는 따로 넘기므로 셸 해석 없음) */
    static boolean reg(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("reg");
            cmd.addAll(Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** MemoPlus.exe --uninstall: 확인 → 바로가기·등록 삭제 → 끝난 뒤 앱 파일 삭제 */
    static void uninstall() throws Exception {
        Path dir = Updater.installDir();
        boolean[] go = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            if (Updater.anotherInstanceRunning()) {
                Ask.notice(null, Ask.Kind.WARN, "메모플러스가 실행 중이에요", "알림 영역의 메모플러스 아이콘에서 종료한 뒤 다시 제거해 주세요.");
                return;
            }
            go[0] = Ask.confirm(null, Ask.Kind.DANGER, "메모플러스를 제거할까요?",
                    "메모는 지우지 않아요.\n" + MemoPlus.Store.dataDir(), "제거", "취소");
        });
        if (!go[0]) return;
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path menu = Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs");
            for (String n : new String[]{MemoPlus.App.START_MENU_NAME, MemoPlus.App.OLD_START_MENU_NAME}) Files.deleteIfExists(menu.resolve(n));
            for (String n : new String[]{"MemoPlus.lnk", MemoPlus.App.OLD_AUTO_START_NAME}) Files.deleteIfExists(menu.resolve("Startup").resolve(n));
        }
        unregister();
        if (commonRegistered()) setCommonShortcut(false);             // 공용 시작 메뉴 바로가기 (관리자 확인)
        if (isAppDir(dir)) scheduleRemove(dir, ProcessHandle.current().pid());   // 실행 중인 파일은 지울 수 없으므로 끝난 뒤 지운다
        SwingUtilities.invokeAndWait(() -> Ask.notice(null, Ask.Kind.INFO, "메모플러스를 제거했어요", "메모는 " + MemoPlus.Store.dataDir() + " 에 남아 있어요."));
    }

    // ---------- 모든 사용자 시작 메뉴 (Windows 검색)
    // 사용자 시작 메뉴(%APPDATA%)를 검색 색인에서 빼 둔 PC 에서도 '메모'·'memo' 로 찾을 수 있게 공용 시작 메뉴에 바로가기를 둔다.
    // 쓰려면 관리자 권한이 필요하므로 UAC 확인을 거친다.

    /** 관리자 확인 창에서 취소했을 때 돌려주는 값 (ERROR_CANCELLED) */
    static final int CANCELED = 1223;

    /** 공용 시작 메뉴 바로가기 경로 (테스트에서는 MEMOPLUS_COMMON_START_MENU 로 다른 폴더) */
    static Path commonLink() {
        String test = System.getenv("MEMOPLUS_COMMON_START_MENU");
        if (test != null && !test.isBlank()) return Paths.get(test).resolve(MemoPlus.App.START_MENU_NAME);
        String pd = System.getenv("ProgramData");
        return pd == null ? null : Paths.get(pd, "Microsoft", "Windows", "Start Menu", "Programs", MemoPlus.App.START_MENU_NAME);
    }

    static boolean commonRegistered() { Path l = commonLink(); return l != null && Files.exists(l); }

    /** 바로가기 내용: 실행 파일, 인자, 작업 폴더, 아이콘 */
    record Shortcut(String target, String args, String dir, String icon) {
        /** 같은 대상으로 등록했는지 비교할 때 쓰는 값 */
        String key() { return target + "|" + args; }
    }

    /**
     * 지금 실행 방식에 맞는 바로가기. exe 면 그 exe, jar 면 javaw -jar MemoPlus.jar (실행 옵션 그대로).
     * 클래스 폴더에서 실행 중(개발·테스트)이면 null
     */
    static Shortcut currentShortcut() {
        Path dir = Updater.installDir();
        if (dir != null) {
            String exe = Updater.exeIn(dir).toString();
            return new Shortcut(exe, "", dir.toString(), exe);
        }
        try {
            Path jar = Paths.get(MemoPlus.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().toLowerCase().endsWith(".jar")) return null;
            Path javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
            if (!Files.isRegularFile(javaw)) return null;
            Path ico = jar.resolveSibling("MemoPlus.ico");
            String args = "--enable-preview --enable-native-access=ALL-UNNAMED -Dsun.java2d.uiScale.enabled=true -jar \"" + jar + "\"";
            return new Shortcut(javaw.toString(), args, jar.getParent().toString(), Files.isRegularFile(ico) ? ico.toString() : javaw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** PowerShell 작은따옴표 문자열 */
    static String psQuote(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** 바로가기를 만들거나(on) 지우는 PowerShell 명령. 값은 모두 작은따옴표 문자열로 넣어 해석되지 않게 */
    static String shortcutCommand(Path lnk, Shortcut sc, boolean on) {
        if (!on) return "$ErrorActionPreference='Stop'; Remove-Item -LiteralPath " + psQuote(lnk.toString()) + " -Force -ErrorAction SilentlyContinue; exit 0";
        return "$ErrorActionPreference='Stop'; "
                + "New-Item -ItemType Directory -Force -Path " + psQuote(lnk.getParent().toString()) + " | Out-Null; "
                + "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(" + psQuote(lnk.toString()) + "); "
                + "$s.TargetPath=" + psQuote(sc.target()) + "; $s.Arguments=" + psQuote(sc.args()) + "; "
                + "$s.WorkingDirectory=" + psQuote(sc.dir()) + "; $s.IconLocation=" + psQuote(sc.icon() + ",0") + "; "
                + "$s.Description='Memo+'; $s.Save(); exit 0";
    }

    /** 명령을 -EncodedCommand 로 (임시 스크립트 파일 없이: 관리자 권한으로 도는 동안 다른 프로그램이 바꿔치기할 수 없게) */
    static String encoded(String command) {
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * 공용 시작 메뉴 바로가기를 만들거나 지운다. UAC 확인 창이 뜨고, 끝날 때까지 기다린다 (EDT 밖에서 부를 것).
     * 0 성공, CANCELED 취소, 그 밖은 실패
     */
    static int setCommonShortcut(boolean on) { return setCommonShortcut(on, true); }

    /** elevate=false 는 테스트용: 관리자 확인 없이 같은 경로로 실행 (임시 폴더에만) */
    static int setCommonShortcut(boolean on, boolean elevate) {
        Path lnk = commonLink();
        Shortcut sc = currentShortcut();
        if (lnk == null || on && sc == null) return -1;
        String inner = encoded(shortcutCommand(lnk, sc, on));
        // 바깥 PowerShell 이 관리자 권한으로 안쪽 명령을 띄우고 종료 코드를 넘긴다. UAC 에서 취소하면 Start-Process 가 예외
        String outer = "try { $p = Start-Process powershell" + (elevate ? " -Verb RunAs" : "") + " -WindowStyle Hidden -Wait -PassThru "
                + "-ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','" + inner + "'; exit $p.ExitCode } "
                + "catch { exit " + CANCELED + " }";
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded(outer))
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return -1; }
            int code = p.exitValue();
            if (code == 0 && on != Files.exists(lnk)) code = -1;
            Updater.log("common start menu " + (on ? "add" : "remove") + ": exit " + code);
            return code;
        } catch (Exception e) {
            Updater.log("common start menu failed: " + e);
            return -1;
        }
    }

    /** pid 프로세스가 끝나면 앱 파일만 지우고, 폴더가 비면 폴더도 지운다 */
    static Process scheduleRemove(Path dir, long pid) throws IOException {
        {
            Path script = Files.createTempFile("MemoPlus-uninstall-", ".ps1");
            Files.writeString(script, String.join("\r\n",
                    "param([int]$OldPid, [string]$Dir)",
                    "try { Wait-Process -Id $OldPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}",
                    "Start-Sleep -Milliseconds 500",
                    "foreach ($n in @('" + String.join("','", APP_FILES) + "')) {",
                    "  $p = Join-Path $Dir $n",
                    "  for ($i = 0; $i -lt 10 -and (Test-Path -LiteralPath $p); $i++) { Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue; if (Test-Path -LiteralPath $p) { Start-Sleep -Milliseconds 700 } }",
                    "}",
                    "if (-not @(Get-ChildItem -LiteralPath $Dir -Force -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $Dir -Force -ErrorAction SilentlyContinue }",
                    "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue",
                    ""), StandardCharsets.UTF_8);
            return new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-OldPid", String.valueOf(pid), "-Dir", dir.toString())
                    .directory(script.getParent().toFile()).start();
        }
    }
}
