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
 * 백업: notes/*.note, media/*.png, settings.properties, lock.key 를 zip 하나로.
 * 매일 한 번 backups 폴더에 자동으로 (최근 7개 보관), 설정에서 내보내기·가져오기.
 * 잠긴 메모는 암호화된 채로 들어가며, 키(lock.key)도 Windows 계정으로 보호돼 있어 다른 계정에서는 열 수 없다.
 */
final class Backup {
    static final int KEEP = 7;
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    static final long MAX_TOTAL = 500L << 20;
    static final int MAX_ENTRIES = 20000;

    static Path dir(QuickMemo.Store st) { return st.dir.resolve("backups"); }

    /** 저장소 전체를 zip 으로 */
    static void write(QuickMemo.Store st, Path out) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Path folder : List.of(st.notesDir, st.mediaDir)) {
                if (!Files.isDirectory(folder)) continue;
                try (var ds = Files.newDirectoryStream(folder)) {
                    for (Path f : ds) {
                        String name = f.getFileName().toString();
                        if (!Files.isRegularFile(f) || !(name.endsWith(".note") || name.endsWith(".png"))) continue;
                        zos.putNextEntry(new java.util.zip.ZipEntry(folder.getFileName() + "/" + name));
                        Files.copy(f, zos);
                        zos.closeEntry();
                    }
                }
            }
            for (String name : List.of("settings.properties", "lock.key")) {
                Path f = st.dir.resolve(name);
                if (!Files.isRegularFile(f)) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 오늘 백업이 없으면 만들고, 오래된 것은 지운다 (백그라운드) */
    static void daily(QuickMemo.Store st) {
        if ("false".equals(st.settings.getProperty("autoBackup"))) return;
        Thread t = new Thread(() -> {
            try {
                Path d = dir(st);
                Files.createDirectories(d);
                String today = "QuickMemo-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
                List<Path> all = list(st);
                if (all.stream().noneMatch(p -> p.getFileName().toString().startsWith(today)))
                    write(st, d.resolve("QuickMemo-" + java.time.LocalDateTime.now().format(STAMP) + ".zip"));
                all = list(st);
                for (int i = KEEP; i < all.size(); i++) Files.deleteIfExists(all.get(i));
            } catch (Exception e) {
                System.err.println("backup failed: " + e);
            }
        }, "backup");
        t.setDaemon(true);
        t.start();
    }

    /** 백업 파일 목록, 최신순 */
    static List<Path> list(QuickMemo.Store st) throws IOException {
        Path d = dir(st);
        if (!Files.isDirectory(d)) return new ArrayList<>();
        try (var s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().matches("QuickMemo-\\d{8}-\\d{4}\\.zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).collect(java.util.stream.Collectors.toList());
        }
    }

    record Result(int added, int updated, int skipped) {}

    /**
     * zip 의 메모를 합친다: 없는 메모는 추가, 같은 메모는 zip 쪽이 더 최근일 때만 바꿈, 열려 있는 메모는 건너뜀.
     * 항목 이름·개수·크기를 검사해 notes/·media/ 의 정해진 형식 파일만 받는다.
     */
    static Result restore(QuickMemo.App app, Path zip) throws IOException {
        QuickMemo.Store st = app.store;
        Path tmp = Files.createTempDirectory(st.dir, "import-");
        try {
            long total = 0;
            int entries = 0;
            byte[] keyFile = null;
            try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) throw new IOException("백업 파일 항목이 너무 많아요");
                    String name = e.getName().replace('\\', '/');
                    Path target = null;
                    if (name.matches("notes/[0-9A-Za-z][0-9A-Za-z\\-]{0,63}\\.note")) target = tmp.resolve(name.substring(6));
                    else if (name.matches("media/[0-9a-fA-F][0-9a-fA-F\\-]{7,63}\\.png")) {
                        Files.createDirectories(tmp.resolve("media"));
                        target = tmp.resolve("media").resolve(name.substring(6));
                    } else if (name.equals("lock.key")) {
                        keyFile = zin.readNBytes(4096);
                        continue;
                    }
                    if (target == null) continue;                     // 모르는 항목은 무시
                    try (var out = Files.newOutputStream(target)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_TOTAL) throw new IOException("백업 파일이 너무 커요");
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            // 이 PC 에 아직 잠금 키가 없으면 백업의 키를 쓴다 (같은 Windows 계정이면 잠긴 메모를 열 수 있음)
            Path localKey = st.dir.resolve("lock.key");
            if (keyFile != null && !Files.exists(localKey)) { Files.write(localKey, keyFile); Crypto.key = null; Crypto.failed = false; }
            int added = 0, updated = 0, skipped = 0;
            try (var ds = Files.newDirectoryStream(tmp, "*.note")) {
                for (Path f : ds) {
                    QuickMemo.Note n;
                    try { n = st.read(f); } catch (Exception ex) { skipped++; continue; }
                    QuickMemo.Note cur = null;
                    for (QuickMemo.Note x : app.notes) if (x.id.equals(n.id)) cur = x;
                    if (cur == null) {
                        n.open = false;
                        app.notes.add(n);
                        st.save(n);
                        added++;
                    } else if (n.updatedAt > cur.updatedAt && !app.windows.containsKey(cur.id)) {
                        n.open = false;
                        app.notes.set(app.notes.indexOf(cur), n);
                        st.save(n);
                        updated++;
                    } else {
                        skipped++;
                    }
                }
            }
            Path media = tmp.resolve("media");
            if (Files.isDirectory(media)) try (var ds = Files.newDirectoryStream(media)) {
                for (Path f : ds) {
                    Path dst = st.mediaDir.resolve(f.getFileName().toString());
                    if (!Files.exists(dst)) Files.copy(f, dst);
                }
            }
            app.list.refresh();
            return new Result(added, updated, skipped);
        } finally {
            Updater.deleteTree(tmp);
        }
    }
}
