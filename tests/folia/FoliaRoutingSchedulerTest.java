import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production methods are inserted by run.py; only scheduling/routing boundaries are doubles. */
public class FoliaRoutingSchedulerTest {
    private static int passed;
    private final MinecraftServer server = new MinecraftServer();
    private final AtomicBoolean routingUpdateQueued = new AtomicBoolean();
    private boolean running = true;
    private boolean failRouting;
    private int routeBatches;

    // PRODUCTION_ROUTING_METHOD

    private void sendProxyRoutingStates() {
        routeBatches++;
        if (failRouting) throw new IllegalStateException("routing callback failed");
        // Folia's entity snapshot futures retain queue ownership until their completion callback.
    }

    private void submit() {
        if (routingUpdateQueued.compareAndSet(false, true)) scheduleProxyRoutingUpdate();
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    private static void expectFailure(Runnable action, String name) {
        try { action.run(); } catch (RuntimeException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }

    public static void main(String[] args) {
        var folia = new FoliaRoutingSchedulerTest();
        folia.submit();
        check(Bukkit.scheduler.pending.size() == 1 && folia.server.mainSubmissions == 0,
                "Folia initial routing goes to the platform global scheduler");
        folia.submit();
        check(Bukkit.scheduler.pending.size() == 1, "only one routing batch can be queued");
        Bukkit.scheduler.runNext();
        check(folia.routeBatches == 1, "routing starts although MinecraftServer.execute is unsupported");
        check(folia.routingUpdateQueued.get(), "Folia retains ownership during asynchronous entity snapshots");
        folia.submit();
        check(Bukkit.scheduler.pending.isEmpty(), "an unfinished snapshot batch cannot overlap a new batch");
        folia.routingUpdateQueued.set(false); // Existing snapshot completion callback.
        folia.submit();
        Bukkit.scheduler.runNext();
        check(folia.routeBatches == 2, "snapshot completion permits the next metadata refresh");
        folia.routingUpdateQueued.set(false);

        Bukkit.scheduler.reject = true;
        expectFailure(folia::submit, "rejected platform submissions remain visible");
        check(!folia.routingUpdateQueued.get(), "rejected submission releases the routing flag");
        Bukkit.scheduler.reject = false;
        folia.submit();
        Bukkit.scheduler.runNext();
        check(folia.routeBatches == 3, "routing can recover after a submission failure");
        folia.routingUpdateQueued.set(false);

        folia.failRouting = true;
        folia.submit();
        expectFailure(Bukkit.scheduler::runNext, "routing initialization failures remain visible");
        check(!folia.routingUpdateQueued.get(), "routing failure releases the Folia queue flag");
        folia.failRouting = false;
        folia.submit();
        Bukkit.scheduler.runNext();
        check(folia.routeBatches == 5, "routing recovers after a callback failure");
        folia.routingUpdateQueued.set(false);

        folia.submit();
        folia.running = false;
        Bukkit.scheduler.runNext();
        check(folia.routeBatches == 5 && !folia.routingUpdateQueued.get(),
                "shutdown skips queued routing and releases ownership");

        CommonCompatibilityManager.INSTANCE.folia = false;
        var paper = new FoliaRoutingSchedulerTest();
        paper.submit();
        Bukkit.scheduler.runNext();
        check(paper.routeBatches == 1 && !paper.routingUpdateQueued.get(),
                "synchronous Paper routing releases ownership immediately");
        check(folia.server.mainSubmissions == 0 && paper.server.mainSubmissions == 0,
                "the unsupported Minecraft executor is never used");
        System.out.println("Folia scheduler: " + passed + " checks passed");
    }

    static class MinecraftServer {
        int mainSubmissions;
        public void execute(Runnable task) {
            mainSubmissions++;
            throw new UnsupportedOperationException("Folia does not have this main executor");
        }
    }
    static class CommonCompatibilityManager {
        static final CommonCompatibilityManager INSTANCE = new CommonCompatibilityManager();
        boolean folia = true;
        public boolean isFolia() { return folia; }
        // PRODUCTION_PLATFORM_METHOD
    }
    static class VoicechatPaperPlugin { static final Object INSTANCE = new Object(); }
    static class Bukkit {
        static final Scheduler scheduler = new Scheduler();
        static final Bukkit server = new Bukkit();
        static Bukkit getServer() { return server; }
        Scheduler getGlobalRegionScheduler() { return scheduler; }
    }
    static class Scheduler {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean reject;
        void execute(Object plugin, Runnable task) {
            if (reject) throw new IllegalStateException("scheduler rejected task");
            pending.add(task);
        }
        void runNext() { pending.remove().run(); }
    }
}
