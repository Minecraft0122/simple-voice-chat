import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the production Bukkit submission method against rejecting/cancelled work. */
public class BukkitRoutingSchedulerTest {
    private static int passed;
    private final AtomicBoolean routingUpdateQueued = new AtomicBoolean(true);
    private boolean running = true, failRouting;
    private int batches;
    // PRODUCTION_ROUTING_METHOD
    void sendProxyRoutingStates() { batches++; if(failRouting) throw new IllegalStateException("route failure"); }
    static void check(boolean ok, String label) { if(!ok) throw new AssertionError(label); passed++; System.out.println("PASS "+label); }
    public static void main(String[] args) {
        var b=new BukkitRoutingSchedulerTest();
        Voicechat.compatibility.reject=true;
        try {b.scheduleProxyRoutingUpdate();throw new AssertionError("must reject");} catch(IllegalStateException expected){}
        check(!b.routingUpdateQueued.get(),"Bukkit rejected submission releases routing ownership");
        Voicechat.compatibility.reject=false;b.routingUpdateQueued.set(true);b.scheduleProxyRoutingUpdate();
        check(b.batches==0 && b.routingUpdateQueued.get(),"Bukkit routing executes only inside scheduler");
        Voicechat.compatibility.runNext();
        check(b.batches==1 && !b.routingUpdateQueued.get(),"Bukkit routing recovers after rejection");
        b.failRouting=true;b.routingUpdateQueued.set(true);b.scheduleProxyRoutingUpdate();
        try{Voicechat.compatibility.runNext();throw new AssertionError("must fail");}catch(IllegalStateException expected){}
        check(!b.routingUpdateQueued.get(),"Bukkit routing callback failure releases ownership");
        b.failRouting=false;b.running=false;b.routingUpdateQueued.set(true);b.scheduleProxyRoutingUpdate();Voicechat.compatibility.runNext();
        check(b.batches==2 && !b.routingUpdateQueued.get(),"Bukkit shutdown skips stale queued routing");
        System.out.println("Bukkit scheduler: "+passed+" checks passed");
    }
    static class Voicechat { static final Compatibility compatibility=new Compatibility(); }
    static class Compatibility {
        boolean reject;final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        void runTask(Runnable r){if(reject)throw new IllegalStateException("rejected");queue.add(r);}
        void runNext(){queue.remove().run();}
    }
}
