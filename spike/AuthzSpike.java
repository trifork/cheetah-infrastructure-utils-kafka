import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.metrics.MetricValueProvider;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Spike: compare ways of letting Kafka's StandardAuthorizer decide on JWT-claim-derived ACLs.
 * Uses only public Kafka 4.2.0 API; the strategies are minimal re-implementations, not PR code.
 *
 * Workload: 8 request threads, 40 principals with 5 claim ACLs each (4 LITERAL READ topics,
 * 1 PREFIXED WRITE). Each request has one action. Every 5th request is for another
 * principal's topic and must be DENIED; all others must be ALLOWED. Claim ACL sets are
 * precomputed (JWT parsing is not measured for any strategy). SLF4J has no backend, so
 * Kafka's audit lines are not written (logging cost is not measured for any strategy).
 * Optional "churn": a separate thread adds and removes one stored ACL about every 1 ms,
 * standing in for the KRaft metadata publisher.
 *
 * Columns: req/s over a 3 s window after 1 s warm-up; wrongAllow / wrongDeny against the
 * expectation above; exceptions thrown by authorize; ACLs in the shared delegate afterwards
 * versus expected; metadata updates done/failed by the churn thread.
 */
public class AuthzSpike {
    static final Map<String, Object> CONFIGS = Map.of("node.id", "1", "super.users", "User:admin");
    static final Metrics METRICS = new Metrics();
    static final int PRINCIPALS = 40;
    static final int THREADS = Integer.getInteger("spike.threads", 8);

    /** Shares sensors by name so private authorizer instances record into the same metrics. */
    static final class SharedPluginMetrics implements PluginMetrics {
        public MetricName metricName(String name, String description, LinkedHashMap<String, String> tags) {
            return METRICS.metricName(name, "spike-authorizer", description, tags);
        }
        public synchronized void addMetric(MetricName n, MetricValueProvider<?> p) {
            if (METRICS.metric(n) == null) METRICS.addMetric(n, p);
        }
        public void removeMetric(MetricName n) { }
        public synchronized Sensor addSensor(String name) { return METRICS.sensor(name); }
        public void removeSensor(String name) { }
    }

    static StandardAuthorizer newKafkaAuthorizer(Map<Uuid, StandardAcl> acls) {
        StandardAuthorizer a = new StandardAuthorizer();
        a.configure(CONFIGS);
        a.withPluginMetrics(new SharedPluginMetrics());
        a.loadSnapshot(acls);
        a.completeInitialLoad();
        return a;
    }

    static AuthorizableRequestContext ctx(String user) {
        KafkaPrincipal p = new KafkaPrincipal("User", user);
        return new AuthorizableRequestContext() {
            public String listenerName() { return "OAUTH"; }
            public SecurityProtocol securityProtocol() { return SecurityProtocol.SASL_SSL; }
            public KafkaPrincipal principal() { return p; }
            public InetAddress clientAddress() { return InetAddress.getLoopbackAddress(); }
            public int requestType() { return 1; }
            public int requestVersion() { return 0; }
            public String clientId() { return "c"; }
            public int correlationId() { return 0; }
        };
    }

    static StandardAcl allow(String principal, ResourceType type, String name, PatternType pt, AclOperation op) {
        return new StandardAcl(type, name, pt, "User:" + principal, "*", op, AclPermissionType.ALLOW);
    }

    interface Strategy {
        String name();
        List<AuthorizationResult> authorize(AuthorizableRequestContext ctx, Set<StandardAcl> claims, List<Action> actions);
        void addStored(Uuid id, StandardAcl acl);
        void removeStored(Uuid id);
        int delegateAclCount();
        default String extra() { return ""; }
    }

    /** Reference: every claim ACL stored permanently in one authorizer (what plain Kafka would do). */
    static final class PureKafka implements Strategy {
        final StandardAuthorizer delegate;
        PureKafka(Map<Uuid, StandardAcl> stored, Collection<Set<StandardAcl>> allClaims) {
            Map<Uuid, StandardAcl> all = new HashMap<>(stored);
            Set<StandardAcl> seen = new HashSet<>(stored.values());
            for (Set<StandardAcl> s : allClaims) for (StandardAcl a : s) if (seen.add(a)) all.put(Uuid.randomUuid(), a);
            delegate = newKafkaAuthorizer(all);
        }
        public String name() { return "pure-kafka (reference)"; }
        public List<AuthorizationResult> authorize(AuthorizableRequestContext c, Set<StandardAcl> claims, List<Action> a) { return delegate.authorize(c, a); }
        public void addStored(Uuid id, StandardAcl acl) { delegate.addAcl(id, acl); }
        public void removeStored(Uuid id) { delegate.removeAcl(id); }
        public int delegateAclCount() { return delegate.aclCount(); }
    }

    /** PR #83: add claim ACLs to the shared delegate, authorize, remove. No lock. */
    static class Pr83 implements Strategy {
        final StandardAuthorizer delegate;
        final AtomicLong mutationErrors = new AtomicLong();
        Pr83(Map<Uuid, StandardAcl> stored) { delegate = newKafkaAuthorizer(stored); }
        public String name() { return "PR #83 as is (no lock)"; }
        public List<AuthorizationResult> authorize(AuthorizableRequestContext c, Set<StandardAcl> claims, List<Action> a) {
            List<Uuid> added = new ArrayList<>(claims.size());
            for (StandardAcl acl : claims) {
                Uuid id = Uuid.randomUuid();
                try { delegate.addAcl(id, acl); added.add(id); } catch (RuntimeException e) { mutationErrors.incrementAndGet(); }
            }
            try {
                return delegate.authorize(c, a);
            } finally {
                for (Uuid id : added) {
                    try { delegate.removeAcl(id); } catch (RuntimeException e) { mutationErrors.incrementAndGet(); }
                }
            }
        }
        public void addStored(Uuid id, StandardAcl acl) { delegate.addAcl(id, acl); }
        public void removeStored(Uuid id) { delegate.removeAcl(id); }
        public int delegateAclCount() { return delegate.aclCount(); }
        public String extra() { return "add/remove exceptions=" + mutationErrors.get(); }
    }

    /** Option (a): PR #83 under one lock shared with metadata updates; duplicates of stored ACLs are skipped. */
    static final class Locked extends Pr83 {
        final Object lock = new Object();
        final AtomicLong skippedDuplicates = new AtomicLong();
        Locked(Map<Uuid, StandardAcl> stored) { super(stored); }
        public String name() { return "(a) lock"; }
        public List<AuthorizationResult> authorize(AuthorizableRequestContext c, Set<StandardAcl> claims, List<Action> a) {
            synchronized (lock) {
                List<Uuid> added = new ArrayList<>(claims.size());
                for (StandardAcl acl : claims) {
                    Uuid id = Uuid.randomUuid();
                    try { delegate.addAcl(id, acl); added.add(id); } catch (RuntimeException e) { skippedDuplicates.incrementAndGet(); }
                }
                try {
                    return delegate.authorize(c, a);
                } finally {
                    for (Uuid id : added) delegate.removeAcl(id);
                }
            }
        }
        public void addStored(Uuid id, StandardAcl acl) { synchronized (lock) { delegate.addAcl(id, acl); } }
        public void removeStored(Uuid id) { synchronized (lock) { delegate.removeAcl(id); } }
        public String extra() { return "skipped duplicates=" + skippedDuplicates.get(); }
    }

    /** Option (b): a private StandardAuthorizer per request, loaded with stored + claim ACLs. Delegate keeps stored ACLs only. */
    static class PrivatePerRequest implements Strategy {
        final StandardAuthorizer delegate;
        final Object writeLock = new Object();
        volatile Map<Uuid, StandardAcl> stored;
        volatile Set<StandardAcl> storedSet;
        final AtomicLong built = new AtomicLong();
        PrivatePerRequest(Map<Uuid, StandardAcl> initial) {
            delegate = newKafkaAuthorizer(initial);
            stored = Map.copyOf(initial);
            storedSet = Set.copyOf(initial.values());
        }
        public String name() { return "(b) private authorizer per request"; }
        StandardAuthorizer build(Set<StandardAcl> claims) {
            Map<Uuid, StandardAcl> s = stored;
            Set<StandardAcl> ss = storedSet;
            Map<Uuid, StandardAcl> all = new HashMap<>(s);
            for (StandardAcl acl : claims) if (!ss.contains(acl)) all.put(Uuid.randomUuid(), acl);
            built.incrementAndGet();
            return newKafkaAuthorizer(all);
        }
        public List<AuthorizationResult> authorize(AuthorizableRequestContext c, Set<StandardAcl> claims, List<Action> a) {
            return build(claims).authorize(c, a);
        }
        public void addStored(Uuid id, StandardAcl acl) {
            synchronized (writeLock) {
                delegate.addAcl(id, acl);
                Map<Uuid, StandardAcl> m = new HashMap<>(stored); m.put(id, acl);
                storedSet = Set.copyOf(m.values()); stored = Map.copyOf(m);
                changed();
            }
        }
        public void removeStored(Uuid id) {
            synchronized (writeLock) {
                delegate.removeAcl(id);
                Map<Uuid, StandardAcl> m = new HashMap<>(stored); m.remove(id);
                storedSet = Set.copyOf(m.values()); stored = Map.copyOf(m);
                changed();
            }
        }
        void changed() { }
        public int delegateAclCount() { return delegate.aclCount(); }
        public String extra() { return "authorizers built=" + built.get(); }
    }

    /** Option (c): (b) cached per claim-ACL set; cache dropped whenever stored ACLs change. */
    static final class PrivateCached extends PrivatePerRequest {
        record Key(long generation, Set<StandardAcl> claims) { }
        final AtomicLong generation = new AtomicLong();
        final ConcurrentHashMap<Key, StandardAuthorizer> cache = new ConcurrentHashMap<>();
        PrivateCached(Map<Uuid, StandardAcl> initial) { super(initial); }
        public String name() { return "(c) private authorizer, cached"; }
        public List<AuthorizationResult> authorize(AuthorizableRequestContext c, Set<StandardAcl> claims, List<Action> a) {
            if (cache.size() > 10_000) cache.clear();
            return cache.computeIfAbsent(new Key(generation.get(), claims), k -> build(k.claims())).authorize(c, a);
        }
        void changed() { generation.incrementAndGet(); cache.clear(); }
    }

    record Result(long requests, long wrongAllow, long wrongDeny, long exceptions, long churnOps, long churnFailures) { }

    static Result run(Strategy s, Map<String, Set<StandardAcl>> claimsByPrincipal, long warmupMs, long measureMs, boolean churn) throws Exception {
        AtomicBoolean measuring = new AtomicBoolean(false), stop = new AtomicBoolean(false);
        AtomicLong req = new AtomicLong(), wa = new AtomicLong(), wd = new AtomicLong(), ex = new AtomicLong(), churnOps = new AtomicLong(), churnFail = new AtomicLong();
        CountDownLatch done = new CountDownLatch(THREADS);
        List<AuthorizableRequestContext> ctxs = new ArrayList<>();
        List<Set<StandardAcl>> claims = new ArrayList<>();
        for (int p = 0; p < PRINCIPALS; p++) { ctxs.add(ctx("svc-" + p)); claims.add(claimsByPrincipal.get("svc-" + p)); }

        for (int t = 0; t < THREADS; t++) {
            final int tt = t;
            new Thread(() -> {
                long i = 0;
                while (!stop.get()) {
                    int p = (int) ((tt * 13L + i) % PRINCIPALS);
                    String topic; AclOperation op; boolean expectAllowed;
                    if (i % 5 == 4) { topic = "svc-" + ((p + 1) % PRINCIPALS) + "-t0"; op = AclOperation.READ; expectAllowed = false; }
                    else if (i % 5 == 3) { topic = "svc-" + p + "-pre-x"; op = AclOperation.WRITE; expectAllowed = true; }
                    else { topic = "svc-" + p + "-t" + (i % 4); op = AclOperation.READ; expectAllowed = true; }
                    List<Action> actions = List.of(new Action(op, new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL), 1, true, true));
                    try {
                        boolean allowed = s.authorize(ctxs.get(p), claims.get(p), actions).get(0) == AuthorizationResult.ALLOWED;
                        if (measuring.get()) {
                            req.incrementAndGet();
                            if (allowed && !expectAllowed) wa.incrementAndGet();
                            if (!allowed && expectAllowed) wd.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        if (measuring.get()) ex.incrementAndGet();
                    }
                    i++;
                }
                done.countDown();
            }).start();
        }
        Thread churner = null;
        if (churn) {
            churner = new Thread(() -> {
                long n = 0;
                while (!stop.get()) {
                    Uuid id = Uuid.randomUuid();
                    try { s.addStored(id, allow("churn", ResourceType.TOPIC, "churn-" + (n++), PatternType.LITERAL, AclOperation.READ)); }
                    catch (RuntimeException e) { churnFail.incrementAndGet(); }
                    try { Thread.sleep(1); } catch (InterruptedException e) { return; }
                    try { s.removeStored(id); } catch (RuntimeException e) { churnFail.incrementAndGet(); }
                    churnOps.addAndGet(2);
                }
            });
            churner.start();
        }
        Thread.sleep(warmupMs);
        measuring.set(true);
        Thread.sleep(measureMs);
        measuring.set(false);
        stop.set(true);
        done.await();
        if (churner != null) churner.join();
        return new Result(req.get(), wa.get(), wd.get(), ex.get(), churnOps.get(), churnFail.get());
    }

    public static void main(String[] args) throws Exception {
        Map<String, Set<StandardAcl>> claimsByPrincipal = new HashMap<>();
        for (int p = 0; p < PRINCIPALS; p++) {
            String u = "svc-" + p;
            Set<StandardAcl> s = new HashSet<>();
            for (int k = 0; k < 4; k++) s.add(allow(u, ResourceType.TOPIC, u + "-t" + k, PatternType.LITERAL, AclOperation.READ));
            s.add(allow(u, ResourceType.TOPIC, u + "-pre-", PatternType.PREFIXED, AclOperation.WRITE));
            claimsByPrincipal.put(u, Set.copyOf(s));
        }
        System.out.printf("JVM %s, %d cores, %d request threads, %d principals x 5 claim ACLs, 1 action per request%n",
                Runtime.version(), Runtime.getRuntime().availableProcessors(), THREADS, PRINCIPALS);

        for (int storedCount : new int[] {0, 100, 1000}) {
            Map<Uuid, StandardAcl> stored = new HashMap<>();
            for (int i = 0; i < storedCount; i++)
                stored.put(Uuid.randomUuid(), allow("stored-" + i, ResourceType.TOPIC, "stored-topic-" + i, PatternType.LITERAL, AclOperation.READ));
            // One stored ACL identical to a claim ACL: Kafka rejects adding an ACL whose content already exists.
            if (storedCount > 0) stored.put(Uuid.randomUuid(), allow("svc-0", ResourceType.TOPIC, "svc-0-t0", PatternType.LITERAL, AclOperation.READ));
            int expectedStored = stored.size();

            List<Supplier<Strategy>> strategies = List.of(
                    () -> new PureKafka(stored, claimsByPrincipal.values()),
                    () -> new Pr83(stored),
                    () -> new Locked(stored),
                    () -> new PrivatePerRequest(stored),
                    () -> new PrivateCached(stored));
            for (boolean churn : new boolean[] {false, true}) {
                System.out.printf("%n== stored KRaft ACLs: %d, %s ==%n", expectedStored,
                        churn ? "metadata churn: 1 stored-ACL add + 1 remove every ~1 ms" : "no metadata changes");
                System.out.printf("%-36s %12s %10s %10s %10s %16s  %s%n", "strategy", "req/s", "wrongAllow", "wrongDeny", "exceptions", "delegateACLs", "notes");
                for (Supplier<Strategy> sup : strategies) {
                    Strategy s = sup.get();
                    Result r = run(s, claimsByPrincipal, 1000, 3000, churn);
                    int expectedDelegate = s instanceof PureKafka ? expectedStored + PRINCIPALS * 5 - (storedCount > 0 ? 1 : 0) : expectedStored;
                    System.out.printf(Locale.ROOT, "%-36s %,12d %10d %10d %10d %7d (exp %d)  %s%s%n", s.name(), r.requests() / 3, r.wrongAllow(), r.wrongDeny(),
                            r.exceptions(), s.delegateAclCount(), expectedDelegate, s.extra(),
                            churn ? "; metadata updates done=" + r.churnOps() + ", failed=" + r.churnFailures() : "");
                }
            }
        }
    }
}
