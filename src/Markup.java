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

/** 원본 plum.sqlite 의 Text 형식을 따른 직렬화: 문단마다 "[bs]id=GUID " + [bs]b [bs]i [bs]ul [bs]strike (끄기는 0 접미), 글머리 [bs]l  ([bs] = 백슬래시) */
final class Markup {
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
                else if (tok.startsWith("img=") && QuickMemo.Store.validMediaId(tok.substring(4))) runs.add(new Run("", false, false, false, false, tok.substring(4)));
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
