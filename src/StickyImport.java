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
 * 외부 라이브러리 없이 SQLite 파일을 읽는 최소 리더 (읽기 전용).
 * 테이블 B-트리, 오버플로 페이지, WAL(커밋된 프레임) 을 지원한다. UTF-8 DB 만 처리.
 */
final class SqliteReader {
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
final class StickyImport {
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
