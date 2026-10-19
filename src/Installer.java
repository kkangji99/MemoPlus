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
 * MSI 없이 스스로 설치: 압축을 푼 곳에서 실행 중이면 %LOCALAPPDATA%\Programs\QuickMemo 로 복사해 다시 실행.
 * exe 로 실행될 때마다 "앱 및 기능" 목록(HKCU Uninstall)에 등록하고, 제거는 QuickMemo.exe --uninstall.
 * 제거는 앱 파일(QuickMemo.exe, app, runtime)만 지우고 메모(%APPDATA%\QuickMemo)는 남긴다.
 */
final class Installer {
    /** 테스트에서 실제 레지스트리를 건드리지 않게 바꿀 수 있음 */
    static final String KEY = System.getProperty("quickmemo.uninstallKey", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\QuickMemo");
    /** jpackage app-image 의 내용. 제거할 때 이것만 지운다 (다른 파일이 있는 폴더를 통째로 지우지 않게) */
    static final String[] APP_FILES = {"QuickMemo.exe", "QuickMemo.ico", "app", "runtime"};

    /** 표준 설치 위치 */
    static Path home() {
        String la = System.getenv("LOCALAPPDATA");
        return la == null ? null : Paths.get(la, "Programs", "QuickMemo");
    }

    /** exe 로 실행 중인데 표준 위치가 아닌 곳(다운로드 폴더 등)이면 true */
    static boolean canInstall() {
        Path dir = Updater.installDir(), h = home();
        return dir != null && h != null && !dir.toAbsolutePath().normalize().equals(h.toAbsolutePath().normalize());
    }

    /** QuickMemo 앱 폴더가 맞는지 (exe + app\QuickMemo.jar + runtime) */
    static boolean isAppDir(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("QuickMemo.exe")) && Files.isRegularFile(dir.resolve("app").resolve("QuickMemo.jar"))
                && Files.isDirectory(dir.resolve("runtime"));
    }

    /** 지금 실행 중인 앱 폴더를 표준 위치로 복사 (있던 앱 파일은 바꿈) */
    static Path copyToHome() throws IOException {
        Path src = Updater.installDir(), dst = home();
        if (!isAppDir(src) || dst == null) throw new IOException("설치할 QuickMemo 파일을 찾지 못했어요.");
        Path staging = dst.resolveSibling("QuickMemo.install");
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
        String exe = dir.resolve("QuickMemo.exe").toString();
        String[][] values = {{"DisplayName", "REG_SZ", "QuickMemo"}, {"DisplayVersion", "REG_SZ", QuickMemo.VERSION}, {"Publisher", "REG_SZ", "QuickMemo"},
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

    /** QuickMemo.exe --uninstall: 확인 → 바로가기·등록 삭제 → 끝난 뒤 앱 파일 삭제 */
    static void uninstall() throws Exception {
        Path dir = Updater.installDir();
        boolean[] go = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            if (Updater.anotherInstanceRunning()) {
                Ask.notice(null, Ask.Kind.WARN, "QuickMemo가 실행 중이에요", "알림 영역의 QuickMemo 아이콘에서 종료한 뒤 다시 제거해 주세요.");
                return;
            }
            go[0] = Ask.confirm(null, Ask.Kind.DANGER, "QuickMemo를 제거할까요?",
                    "메모는 지우지 않아요.\n" + QuickMemo.Store.dataDir(), "제거", "취소");
        });
        if (!go[0]) return;
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path menu = Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs");
            Files.deleteIfExists(menu.resolve(QuickMemo.App.START_MENU_NAME));
            Files.deleteIfExists(menu.resolve("Startup").resolve("QuickMemo.lnk"));
        }
        unregister();
        if (isAppDir(dir)) scheduleRemove(dir, ProcessHandle.current().pid());   // 실행 중인 파일은 지울 수 없으므로 끝난 뒤 지운다
        SwingUtilities.invokeAndWait(() -> Ask.notice(null, Ask.Kind.INFO, "QuickMemo를 제거했어요", "메모는 " + QuickMemo.Store.dataDir() + " 에 남아 있어요."));
    }

    /** pid 프로세스가 끝나면 앱 파일만 지우고, 폴더가 비면 폴더도 지운다 */
    static Process scheduleRemove(Path dir, long pid) throws IOException {
        {
            Path script = Files.createTempFile("QuickMemo-uninstall-", ".ps1");
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
