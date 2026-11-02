package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.ConfigException;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.metrics.MetricValueProvider;
import org.apache.kafka.common.metrics.Monitorable;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.metadata.authorizer.AclMutator;
import org.apache.kafka.metadata.authorizer.ClusterMetadataAuthorizer;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.metadata.authorizer.StandardAuthorizer;
import org.apache.kafka.server.authorizer.AclCreateResult;
import org.apache.kafka.server.authorizer.AclDeleteResult;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.apache.kafka.server.authorizer.AuthorizerServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;
import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Kafka authorizer for Cheetah. Kafka's own {@link StandardAuthorizer} makes every access decision and writes the
 * audit log ({@code kafka.authorizer.logger}); this class only translates the JWT roles of OAuth users into ACLs
 * (see {@link ClaimAclTranslator}).
 *
 * <p>{@code delegate} is the broker's {@code StandardAuthorizer}. It holds the ACLs stored in Kafka, and only Kafka's
 * metadata thread changes it. It decides for users without roles, for example {@code super.users}.
 *
 * <p>For an OAuth user with roles, a private {@code StandardAuthorizer} decides. It holds the stored ACLs plus the
 * user's role ACLs, and is cached per set of role ACLs until the stored ACLs change. Request threads therefore never
 * change shared ACLs: Kafka updates its ACL list without locking and expects only one thread to write to it.
 */
public class CheetahKRaftAuthorizer implements ClusterMetadataAuthorizer, Monitorable {
    static final Logger LOG = LoggerFactory.getLogger(CheetahKRaftAuthorizer.class.getName());
    private static final int MAX_CACHED_AUTHORIZERS = 1000;

    private String topicClaimName;
    private boolean isClaimList;
    private ClaimAclTranslator translator;
    private Map<String, ?> configs;

    /**
     * A counter used to generate an instance number for each instance of this class
     */
    private static final AtomicInteger INSTANCE_NUMBER_COUNTER = new AtomicInteger(1);

    /**
     * An instance number used in {@link #toString()} method, to easily track the number of instances of this class
     */
    private final int instanceNumber = INSTANCE_NUMBER_COUNTER.getAndIncrement();

    private StandardAuthorizer delegate;
    private volatile PluginMetrics metrics;
    private volatile boolean initialLoadComplete;
    private volatile StoredAcls storedAcls = new StoredAcls(Map.of());

    @Override
    public void configure(Map<String, ?> configs) {
        CheetahConfig config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        translator = new ClaimAclTranslator(config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, ""));
        this.configs = configs;

        delegate = instantiateStandardAuthorizer();
        delegate.configure(configs);
    }

    // Kafka calls this right after configure(). Kafka's authorizer needs the metrics to record its decisions.
    @Override
    public void withPluginMetrics(PluginMetrics metrics) {
        this.metrics = new SharedPluginMetrics(metrics);
        delegate.withPluginMetrics(this.metrics);
    }

    private CheetahConfig convertToCheetahConfig(Map<String, ?> configs) {
        Properties p = new Properties();
        String[] keys = {
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME,
                CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX,
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST
        };

        StringBuilder logString = new StringBuilder();
        logString.append("CheetahKafkaAuthorizer values:\n");
        for (String key : keys) {
            ConfigUtil.putIfNotNull(p, key, configs.get(key));

            if (LOG.isInfoEnabled()) {
                logString.append("\t").append(key).append(" = ").append(configs.get(key)).append("\n");
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info(logString.toString());
        }

        return new CheetahConfig(p);
    }

    private StandardAuthorizer instantiateStandardAuthorizer() {
        try {
            LOG.debug("Using StandardAuthorizer (KRaft based) as a delegate");
            return new StandardAuthorizer();
        } catch (Exception e) {
            throw new ConfigException("KRaft mode detected ('process.roles' configured), but failed to instantiate org.apache.kafka.metadata.authorizer.StandardAuthorizer", e);
        }
    }

    @Override
    public Map<Endpoint, ? extends CompletionStage<Void>> start(AuthorizerServerInfo serverInfo) {
        return delegate.start(serverInfo);
    }

    @Override
    public void setAclMutator(AclMutator aclMutator) {
        delegate.setAclMutator(aclMutator);
    }

    @Override
    public AclMutator aclMutatorOrException() {
        return delegate.aclMutatorOrException();
    }

    // The next four methods are called by Kafka's metadata thread only. Each change to the stored ACLs replaces
    // storedAcls, which also drops all cached private authorizers.

    @Override
    public synchronized void completeInitialLoad() {
        delegate.completeInitialLoad();
        initialLoadComplete = true;
    }

    @Override
    public synchronized void loadSnapshot(Map<Uuid, StandardAcl> acls) {
        delegate.loadSnapshot(acls);
        storedAcls = new StoredAcls(acls);
    }

    @Override
    public synchronized void addAcl(Uuid id, StandardAcl acl) {
        delegate.addAcl(id, acl);
        storedAcls = storedAcls.with(id, acl);
    }

    @Override
    public synchronized void removeAcl(Uuid id) {
        delegate.removeAcl(id);
        storedAcls = storedAcls.without(id);
    }

    @Override
    public void completeInitialLoad(Exception e) {
        if (e != null) {
            e.printStackTrace();
        }
        delegate.completeInitialLoad(e);
    }

    @Override
    public Iterable<AclBinding> acls(AclBindingFilter filter) {
        if (delegate != null) {
            return delegate.acls(filter);
        } else {
            throw new UnsupportedOperationException("ACL delegation not enabled");
        }
    }

    @Override
    public List<? extends CompletionStage<AclCreateResult>> createAcls(AuthorizableRequestContext requestContext, List<AclBinding> aclBindings) {
        if (delegate != null) {
            return delegate.createAcls(requestContext, aclBindings);
        } else {
            throw new UnsupportedOperationException("ACL delegation not enabled");
        }
    }


    @Override
    public List<? extends CompletionStage<AclDeleteResult>> deleteAcls(AuthorizableRequestContext requestContext, List<AclBindingFilter> aclBindingFilters) {
        if (delegate != null) {
            return delegate.deleteAcls(requestContext, aclBindingFilters);
        } else {
            throw new UnsupportedOperationException("ACL delegation not enabled");
        }
    }

    @Override
    public int aclCount() {
        if (delegate != null) {
            return delegate.aclCount();
        } else {
            throw new UnsupportedOperationException("ACL delegation not enabled");
        }
    }

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        return authorizerFor(requestContext).authorize(requestContext, actions);
    }

    @Override
    public AuthorizationResult authorizeByResourceType(AuthorizableRequestContext requestContext, AclOperation op, ResourceType resourceType) {
        return authorizerFor(requestContext).authorizeByResourceType(requestContext, op, resourceType);
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
        }
    }

    @Override
    public String toString() {
        return CheetahKRaftAuthorizer.class.getSimpleName() + "@" + instanceNumber;
    }

    /**
     * Picks the Kafka authorizer that decides for this request: the private one for the user's roles, or the delegate
     * when the user has no roles or Kafka has not finished loading the stored ACLs yet.
     */
    private StandardAuthorizer authorizerFor(AuthorizableRequestContext requestContext) {
        if (!initialLoadComplete || !(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            return delegate;
        }
        Set<StandardAcl> roleAcls = roleAcls((OAuthKafkaPrincipal) requestContext.principal());
        if (roleAcls.isEmpty()) {
            return delegate;
        }

        StoredAcls stored = storedAcls;
        StandardAuthorizer cached = stored.privateAuthorizers.get(roleAcls);
        if (cached != null) {
            return cached;
        }
        if (stored.privateAuthorizers.size() >= MAX_CACHED_AUTHORIZERS) {
            stored.privateAuthorizers.clear();
        }
        return stored.privateAuthorizers.computeIfAbsent(roleAcls, acls -> newPrivateAuthorizer(stored, acls));
    }

    private Set<StandardAcl> roleAcls(OAuthKafkaPrincipal principal) {
        try {
            return translator.toAcls(principal, extractAccessClaim(principal));
        } catch (Exception e) {
            LOG.warn(String.format("JWT does not have \"%s\" claim", topicClaimName));
            return Set.of();
        }
    }

    private StandardAuthorizer newPrivateAuthorizer(StoredAcls stored, Set<StandardAcl> roleAcls) {
        Map<Uuid, StandardAcl> acls = new HashMap<>(stored.byId);
        for (StandardAcl acl : roleAcls) {
            // Kafka rejects an ACL that is already stored.
            if (!stored.byId.containsValue(acl)) {
                acls.put(Uuid.randomUuid(), acl);
            }
        }
        StandardAuthorizer authorizer = new StandardAuthorizer();
        authorizer.configure(configs);
        authorizer.withPluginMetrics(metrics);
        authorizer.loadSnapshot(acls);
        authorizer.completeInitialLoad();
        return authorizer;
    }

    private List<String> extractAccessClaim(OAuthKafkaPrincipal principal) {
        List<String> result = new ArrayList<>();
        if (isClaimList) {
            BearerTokenWithPayload jwt = principal.getJwt();
            JsonNode topicClaim = jwt.getClaimsJSON().get(topicClaimName);
            Iterator<JsonNode> iterator = topicClaim.elements();
            while (iterator.hasNext()) {
                result.add(iterator.next().asText());
            }
        } else {
            result = Arrays.asList(principal.getJwt().getClaimsJSON().get(topicClaimName).asText().split(","));
        }
        return result;
    }

    /**
     * The ACLs stored in Kafka, and the private authorizers built from them. Replaced, never changed, so a private
     * authorizer can never hold outdated stored ACLs.
     */
    private static final class StoredAcls {
        final Map<Uuid, StandardAcl> byId;
        final ConcurrentMap<Set<StandardAcl>, StandardAuthorizer> privateAuthorizers = new ConcurrentHashMap<>();

        StoredAcls(Map<Uuid, StandardAcl> byId) {
            this.byId = Map.copyOf(byId);
        }

        StoredAcls with(Uuid id, StandardAcl acl) {
            Map<Uuid, StandardAcl> acls = new HashMap<>(byId);
            acls.put(id, acl);
            return new StoredAcls(acls);
        }

        StoredAcls without(Uuid id) {
            Map<Uuid, StandardAcl> acls = new HashMap<>(byId);
            acls.remove(id);
            return new StoredAcls(acls);
        }
    }

    /**
     * Lets the delegate and the private authorizers count into the same metrics. Kafka's metrics object refuses a
     * second registration of the same name, which every extra {@code StandardAuthorizer} would do, so the first
     * registration (the delegate's) is kept and reused.
     */
    private static final class SharedPluginMetrics implements PluginMetrics {
        private final PluginMetrics kafkaMetrics;
        private final Map<String, Sensor> sensors = new ConcurrentHashMap<>();
        private final Set<MetricName> metricNames = ConcurrentHashMap.newKeySet();

        SharedPluginMetrics(PluginMetrics kafkaMetrics) {
            this.kafkaMetrics = kafkaMetrics;
        }

        @Override
        public MetricName metricName(String name, String description, LinkedHashMap<String, String> tags) {
            return kafkaMetrics.metricName(name, description, tags);
        }

        @Override
        public void addMetric(MetricName metricName, MetricValueProvider<?> metricValueProvider) {
            if (metricNames.add(metricName)) {
                kafkaMetrics.addMetric(metricName, metricValueProvider);
            }
        }

        @Override
        public void removeMetric(MetricName metricName) {
            if (metricNames.remove(metricName)) {
                kafkaMetrics.removeMetric(metricName);
            }
        }

        @Override
        public Sensor addSensor(String name) {
            return sensors.computeIfAbsent(name, kafkaMetrics::addSensor);
        }

        @Override
        public void removeSensor(String name) {
            if (sensors.remove(name) != null) {
                kafkaMetrics.removeSensor(name);
            }
        }
    }
}
