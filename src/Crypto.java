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
 * 잠긴 메모 암호화. 본문은 AES-256-GCM, 그 키는 Windows DPAPI(현재 사용자)로 보호해 lock.key 에 둔다.
 * 그래서 메모 파일·백업을 다른 PC 나 다른 Windows 계정으로 가져가면 열 수 없다.
 * DPAPI 는 PowerShell 로 부르며, 키는 명령줄이 아니라 표준 입력으로 주고받는다 (다른 프로그램이 볼 수 없게).
 */
final class Crypto {
    static final String PREFIX = "ENC1:";
    static byte[] key;
    static boolean failed;

    /** 키를 읽거나(처음이면 만들어) 메모리에 둔다. 실패하면 null */
    static synchronized byte[] key(Path dataDir) {
        if (key != null) return key;
        if (failed) return null;
        try {
            Path f = dataDir.resolve("lock.key");
            if (Files.exists(f)) {
                byte[] k = dpapi(false, Base64.getDecoder().decode(Files.readString(f).trim()));
                if (k == null || k.length != 32) { failed = true; return null; }
                key = k;
            } else {
                byte[] k = new byte[32];
                new java.security.SecureRandom().nextBytes(k);
                byte[] prot = dpapi(true, k);
                if (prot == null || !Arrays.equals(dpapi(false, prot), k)) { failed = true; return null; }   // 되풀리는지 확인 후 저장
                Files.writeString(f, Base64.getEncoder().encodeToString(prot));
                key = k;
            }
            return key;
        } catch (Exception e) {
            failed = true;
            return null;
        }
    }

    static String encrypt(Path dataDir, String plain) {
        byte[] k = key(dataDir);
        if (k == null) return null;
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(k, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            return null;
        }
    }

    /** 풀 수 없으면(키 없음·다른 계정·변조) null */
    static String decrypt(Path dataDir, String text) {
        if (text == null || !text.startsWith(PREFIX)) return null;
        byte[] k = key(dataDir);
        if (k == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(text.substring(PREFIX.length()).trim());
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(k, "AES"), new javax.crypto.spec.GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** DPAPI 보호/해제. 데이터는 표준 입력·출력(base64)으로만 */
    static byte[] dpapi(boolean protect, byte[] data) throws Exception {
        String op = protect ? "Protect" : "Unprotect";
        String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                + "$b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); "
                + "$r=[Security.Cryptography.ProtectedData]::" + op + "($b,[Text.Encoding]::UTF8.GetBytes('QuickMemo'),'CurrentUser'); "
                + "[Console]::Out.Write([Convert]::ToBase64String($r))";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var out = p.getOutputStream()) { out.write(Base64.getEncoder().encode(data)); }
        String res = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0 || res.isEmpty()) return null;
        return Base64.getDecoder().decode(res);
    }
}
