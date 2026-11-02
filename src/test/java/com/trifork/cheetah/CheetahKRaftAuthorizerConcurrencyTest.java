package com.trifork.cheetah;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.trifork.cheetah.AuthorizerTestSupport.action;
import static com.trifork.cheetah.AuthorizerTestSupport.oauth;
import static com.trifork.cheetah.AuthorizerTestSupport.readyAuthorizer;
import static com.trifork.cheetah.AuthorizerTestSupport.storedAcl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request threads authorize in parallel while a metadata thread adds and removes stored ACLs, like a broker with
 * several request handler threads ({@code num.io.threads}, default 8). Adding and removing per-request ACLs on the
 * shared Kafka authorizer fails this test with wrong decisions, exceptions and lost metadata updates.
 */
class CheetahKRaftAuthorizerConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PRINCIPALS = 20;
    private static final long RUN_MILLIS = 1500;

    @Test
    void parallelRequestsAndMetadataUpdatesStayCorrect() throws Exception {
        CheetahKRaftAuthorizer authorizer = readyAuthorizer("User:superuser");
        Uuid permanent = Uuid.randomUuid();
        authorizer.loadSnapshot(Map.of(permanent,
                storedAcl("tls-user", ResourceType.TOPIC, "orders", AclOperation.READ, AclPermissionType.ALLOW)));

        List<AuthorizableRequestContext> users = new ArrayList<>();
        for (int p = 0; p < PRINCIPALS; p++) {
            users.add(oauth("svc-" + p, "Kafka_svc-" + p + "_read", "Kafka_svc-" + p + "-out*_write"));
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong requests = new AtomicLong();
        AtomicLong wrong = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(THREADS + 1);

        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            new Thread(() -> {
                try {
                    for (long i = 0; !stop.get(); i++) {
                        // Threads overlap on principals, so the same user is also authorized concurrently.
                        int p = (int) ((thread * 3 + i) % PRINCIPALS);
                        String topic;
                        AclOperation operation;
                        AuthorizationResult expected;
                        switch ((int) (i % 3)) {
                            case 0:
                                topic = "svc-" + p;
                                operation = AclOperation.READ;
                                expected = AuthorizationResult.ALLOWED;
                                break;
                            case 1:
                                topic = "svc-" + p + "-out-x";
                                operation = AclOperation.WRITE;
                                expected = AuthorizationResult.ALLOWED;
                                break;
                            default:
                                topic = "svc-" + ((p + 1) % PRINCIPALS);
                                operation = AclOperation.READ;
                                expected = AuthorizationResult.DENIED;
                        }
                        AuthorizationResult result = authorizer.authorize(users.get(p),
                                action(operation, ResourceType.TOPIC, topic)).get(0);
                        requests.incrementAndGet();
                        if (result != expected) {
                            wrong.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        AtomicLong metadataUpdates = new AtomicLong();
        new Thread(() -> {
            try {
                for (int n = 0; !stop.get(); n++) {
                    Uuid id = Uuid.randomUuid();
                    authorizer.addAcl(id, storedAcl("churn", ResourceType.TOPIC, "churn-" + n, AclOperation.READ,
                            AclPermissionType.ALLOW));
                    authorizer.removeAcl(id);
                    metadataUpdates.addAndGet(2);
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                errors.add(e);
            } finally {
                done.countDown();
            }
        }).start();

        Thread.sleep(RUN_MILLIS);
        stop.set(true);
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish");

        assertEquals(List.of(), List.copyOf(errors), "exceptions in request or metadata threads");
        assertEquals(0, wrong.get(), "wrong decisions out of " + requests.get());
        assertTrue(requests.get() > 1000, "too few requests to be meaningful: " + requests.get());
        assertTrue(metadataUpdates.get() > 10, "too few metadata updates: " + metadataUpdates.get());
        assertEquals(1, authorizer.aclCount(), "shared ACL state must hold only the stored ACLs");
        authorizer.close();
    }
}
