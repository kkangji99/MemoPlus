import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * 어디서든 누르는 전역 단축키 (Windows RegisterHotKey).
 * 추가 라이브러리 없이 JDK 의 외부 함수 API(java.lang.foreign, JDK 21 에서는 미리보기)로 user32.dll 을 부른다.
 * 이 파일만 --enable-preview 로 컴파일하고, 메모플러스 는 반사로 불러 실패하면(미리보기 꺼짐 등) 단축키 없이 동작한다.
 */
public final class MemoPlusHotkey {
    static final int MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000, WM_HOTKEY = 0x0312;

    /** 메시지를 기다리는 스레드의 Win32 ID (테스트에서 WM_HOTKEY 를 직접 보내 확인할 때) */
    public static volatile int threadId;

    private MemoPlusHotkey() { }

    /** 단축키 해제: 기다리는 스레드에 WM_QUIT 를 보내 끝내면 그 스레드가 해제한다 */
    public static void stop() throws Throwable {
        int id = threadId;
        if (id == 0) return;
        MethodHandle post = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("user32", Arena.global()).find("PostThreadMessageW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        int ignored = (int) post.invokeExact(id, 0x0012, 0L, 0L);    // WM_QUIT
        for (int i = 0; i < 30 && threadId != 0; i++) Thread.sleep(50);
    }

    /**
     * 단축키를 등록하고 메시지를 기다리는 스레드를 띄운다. 등록에 성공하면 true.
     * RegisterHotKey 와 GetMessage 는 같은 스레드에서 불러야 하므로 등록 결과를 기다렸다가 돌려준다.
     */
    public static boolean start(int mods, int vk, Runnable onPress) throws Exception {
        boolean[] ok = new boolean[1];
        Object lock = new Object();
        boolean[] done = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
                MethodHandle register = linker.downcallHandle(user32.find("RegisterHotKey").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                MethodHandle getMessage = linker.downcallHandle(user32.find("GetMessageW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                MethodHandle tid = linker.downcallHandle(SymbolLookup.libraryLookup("kernel32", Arena.global()).find("GetCurrentThreadId").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                threadId = (int) tid.invokeExact();
                int r = (int) register.invokeExact(MemorySegment.NULL, 1, mods | MOD_NOREPEAT, vk);
                synchronized (lock) { ok[0] = r != 0; done[0] = true; lock.notifyAll(); }
                if (r == 0) return;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment msg = arena.allocate(48);              // MSG 구조체 (x64): hwnd(8) message(4) ...
                    while ((int) getMessage.invokeExact(msg, MemorySegment.NULL, 0, 0) > 0) {
                        if (msg.get(ValueLayout.JAVA_INT, 8) == WM_HOTKEY) javax.swing.SwingUtilities.invokeLater(onPress);
                    }
                }
                MethodHandle unregister = linker.downcallHandle(user32.find("UnregisterHotKey").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                int ignored = (int) unregister.invokeExact(MemorySegment.NULL, 1);
                threadId = 0;
            } catch (Throwable e) {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        }, "hotkey");
        t.setDaemon(true);
        t.start();
        synchronized (lock) {
            long until = System.currentTimeMillis() + 3000;
            while (!done[0] && System.currentTimeMillis() < until) lock.wait(500);
        }
        return ok[0];
    }
}
