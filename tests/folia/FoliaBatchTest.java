import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Production batch and adapter methods are inserted verbatim by run.py. */
public class FoliaBatchTest {
    private static int passed;
    private static final Queue<Runnable> global = new ConcurrentLinkedQueue<>();
    private static boolean rejectGlobal;
    private final AtomicBoolean routingUpdateQueued = new AtomicBoolean(true);
    private volatile boolean running = true;
    private final MinecraftServer server = new MinecraftServer();
    private List<ProxyPlayerSnapshot> sent = List.of();
    private int flushes, samples;
    private boolean failSample, failFlush;
    // PRODUCTION_BATCH_METHOD
    private ProxyPlayerSnapshot createProxyPlayerSnapshot(ServerPlayer player, List<ServerPlayer> players) {
        samples++;
        if (failSample) throw new IllegalStateException("bad sample");
        return new ProxyPlayerSnapshot(player.getUUID());
    }
    private void sendFoliaProxyRoutingStates(List<ProxyPlayerSnapshot> players, Map<UUID,ServerPlayer> refs, long now) {
        if(failFlush) throw new IllegalStateException("bad flush");
        flushes++; sent = players;
    }
    record ProxyPlayerSnapshot(UUID id) {}
    static void check(boolean b, String label) { if(!b) throw new AssertionError(label); passed++; System.out.println("PASS "+label); }
    static void drain() { Runnable r; while((r=global.poll())!=null) r.run(); }
    static FoliaBatchTest batch(ServerPlayer... players) {
        var b=new FoliaBatchTest(); b.server.players.addAll(List.of(players)); return b;
    }
    public static void main(String[] args) throws Exception {
        var a=new ServerPlayer(); var b=new ServerPlayer(); var first=batch(a,b);
        first.sendFoliaProxyRoutingStates();
        check(first.samples==0 && first.routingUpdateQueued.get(),"entity state is not sampled on global scheduler");
        a.scheduler.runNext(); drain();
        check(first.flushes==0,"batch waits for every player snapshot");
        b.scheduler.runNext(); drain();
        check(first.sent.size()==2 && first.flushes==1 && !first.routingUpdateQueued.get(),"complete batch flushes once and releases ownership");
        var retired=new ServerPlayer();retired.scheduler.retired=true;var gone=batch(retired);
        gone.sendFoliaProxyRoutingStates();drain();
        check(gone.flushes==1 && gone.sent.isEmpty() && !gone.routingUpdateQueued.get(),"already retired scheduler returning null cannot stall routing");
        var later=new ServerPlayer();var logout=batch(later);logout.sendFoliaProxyRoutingStates();
        later.scheduler.retireQueued();drain();
        check(logout.sent.isEmpty() && !logout.routingUpdateQueued.get(),"logout after enqueue completes through retired callback");
        var rejected=new ServerPlayer();rejected.scheduler.reject=true;var failed=batch(rejected);
        failed.sendFoliaProxyRoutingStates();drain();
        check(!failed.routingUpdateQueued.get() && failed.sent.isEmpty(),"rejected entity scheduling cannot stall routing");
        var broken=new ServerPlayer();var sampling=batch(broken);sampling.failSample=true;
        sampling.sendFoliaProxyRoutingStates();broken.scheduler.runNext();drain();
        check(!sampling.routingUpdateQueued.get() && sampling.sent.isEmpty(),"snapshot exception completes batch without stale player state");
        var hanging=new ServerPlayer();var timeout=batch(hanging);timeout.sendFoliaProxyRoutingStates();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(timeout.routingUpdateQueued.get() && System.nanoTime()<deadline){drain();Thread.sleep(10);}
        check(!timeout.routingUpdateQueued.get() && timeout.sent.isEmpty(),"missing entity callback times out and releases routing");
        hanging.scheduler.runNext();drain();
        check(timeout.samples==0 && timeout.flushes==1,"late callback cannot resample or publish timed-out batch");
        timeout.routingUpdateQueued.set(true);timeout.sendFoliaProxyRoutingStates();hanging.scheduler.runNext();drain();
        check(timeout.samples==1 && timeout.sent.size()==1 && !timeout.routingUpdateQueued.get(),"routing resumes after missing callback");
        var closing=new ServerPlayer();var shutdown=batch(closing);shutdown.sendFoliaProxyRoutingStates();closing.scheduler.runNext();shutdown.running=false;drain();
        check(shutdown.flushes==0 && !shutdown.routingUpdateQueued.get(),"shutdown before global completion prevents stale publication");
        var gl=new ServerPlayer();var rejectedFlush=batch(gl);rejectedFlush.sendFoliaProxyRoutingStates();rejectGlobal=true;gl.scheduler.runNext();rejectGlobal=false;drain();
        check(!rejectedFlush.routingUpdateQueued.get(),"rejected completion scheduling releases ownership");
        var err=new ServerPlayer();var flushError=batch(err);flushError.failFlush=true;flushError.sendFoliaProxyRoutingStates();err.scheduler.runNext();drain();
        check(!flushError.routingUpdateQueued.get(),"routing flush exception releases ownership");
        var empty=batch();empty.sendFoliaProxyRoutingStates();check(!empty.routingUpdateQueued.get(),"empty server does not retain batch ownership");
        Voicechat.SERVER_CONFIG.proxyMode.value=false;var direct=batch(a);direct.sendFoliaProxyRoutingStates();
        check(!direct.routingUpdateQueued.get(),"disabled proxy mode releases batch ownership");
        System.out.println("Folia batches: "+passed+" checks passed");
    }
    static class Voicechat {
        static final Config SERVER_CONFIG=new Config(); static final Logger LOGGER=new Logger();
        static class Config { final Value proxyMode=new Value(); boolean isProxyMode(){return proxyMode.get();} }
        static class Value { boolean value=true;boolean get(){return value;} }
        static class Logger { void warn(String m,Object... a){} void error(String m,Object... a){} }
    }
    static class MinecraftServer {
        final List<ServerPlayer> players=new ArrayList<>(); MinecraftServer getPlayerList(){return this;} List<ServerPlayer> getPlayers(){return players;}
        void execute(Runnable r){throw new UnsupportedOperationException("Folia main executor");}
    }
    static class ServerPlayer {
        final UUID uuid=UUID.randomUUID();final EntityScheduler scheduler=new EntityScheduler();UUID getUUID(){return uuid;}
        ServerPlayer getBukkitEntity(){return this;}EntityScheduler getScheduler(){return scheduler;}
    }
    static class EntityScheduler {
        boolean retired,reject;Runnable pending,onRetired;
        Object run(Object plugin,java.util.function.Consumer<Object> task,Runnable retiredCallback){
            if(reject)throw new IllegalStateException("rejected");if(retired)return null;
            pending=()->task.accept(this);onRetired=retiredCallback;return this;
        }
        void runNext(){Runnable p=pending;pending=null;p.run();}
        void retireQueued(){pending=null;onRetired.run();}
    }
    static class CommonCompatibilityManager {
        static final CommonCompatibilityManager INSTANCE=new CommonCompatibilityManager();
        void execute(MinecraftServer s,Runnable task){if(rejectGlobal)throw new IllegalStateException("rejected flush");global.add(task);}
        // PRODUCTION_ENTITY_METHOD
    }
    static class VoicechatPaperPlugin {static final Object INSTANCE=new Object();}
}

