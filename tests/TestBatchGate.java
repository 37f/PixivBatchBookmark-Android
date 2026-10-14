import com.openai.pixivbatchbookmark.BatchGate;

public class TestBatchGate {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        try {
            BatchGate seeded = BatchGate.class.getConstructor(long.class).newInstance(100L);
            check(seeded.begin("login", "", "https://www.pixiv.net/") == 101L, "seeded tokens must avoid reuse after recreation");
        } catch (ReflectiveOperationException e) { throw new AssertionError("task token seed required across recreation", e); }
        BatchGate gate = new BatchGate();
        long first = gate.begin("bookmark", "97003966", "https://www.pixiv.net/artworks/97003966");
        check(!gate.pageReady("https://accounts.pixiv.net/login"), "login redirect must not execute bookmark");
        check(!gate.pageReady("https://www.pixiv.net/artworks/123"), "previous artwork must not execute next request");
        check(gate.pageReady("https://www.pixiv.net/artworks/97003966?lang=zh"), "expected page should execute");
        check(!gate.pageReady("https://www.pixiv.net/artworks/97003966"), "duplicate page callback must not execute twice");
        check(!gate.complete(first, "bookmark", "123"), "wrong artwork result must be rejected");
        check(gate.complete(first, "bookmark", "97003966"), "matching result completes request");
        check(!gate.complete(first, "bookmark", "97003966"), "duplicate result must be ignored");
        long second = gate.begin("login", "", "https://www.pixiv.net/");
        check(!gate.complete(first, "bookmark", "97003966"), "late result from previous request must be rejected");
        check(!gate.complete(second, "login", ""), "result before page readiness must be rejected");
        check(gate.pageReady("https://www.pixiv.net"), "root slash normalization");
        gate.cancel();
        check(!gate.isActive(second), "cancelled request must not timeout or resume");
        check(!gate.complete(second, "login", ""), "cancelled result must be ignored");
        gate.begin("login", "", "https://www.pixiv.net/");
        check(!gate.pageReady("https://www.pixiv.net.evil.example/"), "hostname suffix must not pass origin check");
        check(!gate.pageReady("http://www.pixiv.net/"), "HTTP must not pass origin check");
        long older = gate.begin("bookmark", "*", "https://www.pixiv.net/");
        gate.pageReady("https://www.pixiv.net/"); gate.cancel();
        long newer = gate.begin("bookmark", "*", "https://www.pixiv.net/"); gate.pageReady("https://www.pixiv.net/");
        try {
            java.lang.reflect.Method decision = BatchGate.class.getMethod("shouldRejectQueueStart", long.class, boolean.class, boolean.class, String.class);
            check(!(Boolean)decision.invoke(gate, older, true, true, "false"), "old rejected completion must not stop new queue");
            check((Boolean)decision.invoke(gate, newer, true, true, "false"), "current rejected completion must stop current queue");
            check(!(Boolean)decision.invoke(gate, newer, true, true, "true"), "accepted current completion must continue");
            check(!(Boolean)decision.invoke(gate, newer, false, true, "false"), "stopped native state must ignore completion");
        } catch (ReflectiveOperationException e) { throw new AssertionError("queue start callback generation guard required", e); }
        System.out.println("PASS queue-start decision rejects only current active generation");
        System.out.println("PASS native batch lifecycle: page readiness, duplicates, late results, cancellation, origin");
    }
}
