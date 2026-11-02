package com.trifork.cheetah;

import com.fasterxml.jackson.databind.JsonNode;
import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;
import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.metrics.Monitorable;
import org.apache.kafka.common.metrics.PluginMetrics;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka authorizer for Cheetah. Kafka's own {@link StandardAuthorizer} makes every access decision and writes
 * the audit log ({@code kafka.authorizer.logger}); this class only translates the JWT roles of OAuth users into
 * ACLs.
 *
 * <p>{@code delegate} is the broker's {@code StandardAuthorizer}. It holds the ACLs stored in KRaft and, as Kafka
 * intends, is only changed by Kafka's metadata thread. It decides for principals without roles: non-OAuth
 * principals such as {@code super.users}, and OAuth principals without a roles claim.
 *
 * <p>For an OAuth principal with roles, the decision is made by a private {@code StandardAuthorizer} holding the
 * stored ACLs plus the ACLs translated from that principal's roles. Private authorizers are cached per set of
 * role ACLs and discarded whenever the stored ACLs change. Request threads therefore never modify shared ACL
 * state: adding and removing role ACLs on the shared delegate per request, as an earlier design did, races with
 * other request threads and with the metadata thread, because Kafka updates its ACL index without locking.
 */
public class CheetahKRaftAuthorizer implements ClusterMetadataAuthorizer, Monitorable {
    static final Logger LOG = LoggerFactory.getLogger(CheetahKRaftAuthorizer.class.getName());
    static final int DEFAULT_CACHE_MAX_ENTRIES = 1000;

    /**
     * A counter used to generate an instance number for each instance of this class
     */
    private static final AtomicInteger INSTANCE_NUMBER_COUNTER = new AtomicInteger(1);

    /**
     * An instance number used in {@link #toString()} method, to easily track the number of instances of this class
     */
    private final int instanceNumber = INSTANCE_NUMBER_COUNTER.getAndIncrement();

    private String roleClaimName;
    private boolean isClaimList;
    private int cacheMaxEntries;
    private ClaimAclTranslator translator;
    private Map<String, Object> configs;
    private StandardAuthorizer delegate;

    private volatile SharedPluginMetrics pluginMetrics;
    private volatile StoredAcls storedAcls = StoredAcls.of(Map.of());
    private volatile boolean initialLoadComplete;

    @Override
    public void configure(Map<String, ?> configs) {
        CheetahConfig config = convertToCheetahConfig(configs);

        roleClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        translator = new ClaimAclTranslator(config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, ""));
        cacheMaxEntries = config.getValueAsInt(CheetahConfig.CHEETAH_AUTHORIZATION_CACHE_MAX_ENTRIES,
                DEFAULT_CACHE_MAX_ENTRIES);
        this.configs = Collections.unmodifiableMap(new HashMap<>(configs));

        delegate = new StandardAuthorizer();
        delegate.configure(this.configs);
        // Kafka replaces these via withPluginMetrics, which it only calls after configure.
        pluginMetrics = SharedPluginMetrics.standalone();
        delegate.withPluginMetrics(pluginMetrics);
    }

    private CheetahConfig convertToCheetahConfig(Map<String, ?> configs) {
        Properties p = new Properties();
        String[] keys = {
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME,
                CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX,
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST,
                CheetahConfig.CHEETAH_AUTHORIZATION_CACHE_MAX_ENTRIES
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

    @Override
    public synchronized void withPluginMetrics(PluginMetrics metrics) {
        pluginMetrics = new SharedPluginMetrics(metrics);
        delegate.withPluginMetrics(pluginMetrics);
        storedAcls = storedAcls.withEmptyCache();
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

    @Override
    public synchronized void completeInitialLoad() {
        delegate.completeInitialLoad();
        initialLoadComplete = true;
    }

    @Override
    public void completeInitialLoad(Exception e) {
        delegate.completeInitialLoad(e);
    }

    @Override
    public synchronized void loadSnapshot(Map<Uuid, StandardAcl> acls) {
        delegate.loadSnapshot(acls);
        storedAcls = StoredAcls.of(acls);
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
    public Iterable<AclBinding> acls(AclBindingFilter filter) {
        return delegate.acls(filter);
    }

    @Override
    public List<? extends CompletionStage<AclCreateResult>> createAcls(AuthorizableRequestContext requestContext,
                                                                       List<AclBinding> aclBindings) {
        return delegate.createAcls(requestContext, aclBindings);
    }

    @Override
    public List<? extends CompletionStage<AclDeleteResult>> deleteAcls(AuthorizableRequestContext requestContext,
                                                                       List<AclBindingFilter> aclBindingFilters) {
        return delegate.deleteAcls(requestContext, aclBindingFilters);
    }

    @Override
    public int aclCount() {
        return delegate.aclCount();
    }

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        return authorizerFor(requestContext).authorize(requestContext, actions);
    }

    @Override
    public AuthorizationResult authorizeByResourceType(AuthorizableRequestContext requestContext, AclOperation op,
                                                       ResourceType resourceType) {
        return authorizerFor(requestContext).authorizeByResourceType(requestContext, op, resourceType);
    }

    @Override
    public void close() throws IOException {
        storedAcls = storedAcls.withEmptyCache();
        if (delegate != null) {
            delegate.close();
        }
    }

    @Override
    public String toString() {
        return CheetahKRaftAuthorizer.class.getSimpleName() + "@" + instanceNumber;
    }

    private StandardAuthorizer authorizerFor(AuthorizableRequestContext requestContext) {
        // Before the initial load, the delegate answers (super users allowed, others not ready), as in Kafka.
        if (!initialLoadComplete || !(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            return delegate;
        }
        Set<StandardAcl> roleAcls = roleAcls((OAuthKafkaPrincipal) requestContext.principal());
        if (roleAcls.isEmpty()) {
            return delegate;
        }

        StoredAcls stored = storedAcls;
        StandardAuthorizer cached = stored.privateAuthorizers().get(roleAcls);
        if (cached != null) {
            return cached;
        }
        if (stored.privateAuthorizers().size() >= cacheMaxEntries) {
            stored.privateAuthorizers().clear();
        }
        return stored.privateAuthorizers().computeIfAbsent(roleAcls, acls -> newPrivateAuthorizer(stored, acls));
    }

    private Set<StandardAcl> roleAcls(OAuthKafkaPrincipal principal) {
        List<String> roles;
        try {
            roles = extractRoles(principal);
        } catch (Exception e) {
            LOG.warn("JWT does not have \"{}\" claim", roleClaimName);
            return Set.of();
        }
        return translator.toAcls(principal, roles);
    }

    private List<String> extractRoles(OAuthKafkaPrincipal principal) {
        BearerTokenWithPayload jwt = principal.getJwt();
        JsonNode claim = jwt.getClaimsJSON().get(roleClaimName);
        if (!isClaimList) {
            return Arrays.asList(claim.asText().split(","));
        }
        List<String> result = new ArrayList<>();
        Iterator<JsonNode> iterator = claim.elements();
        while (iterator.hasNext()) {
            result.add(iterator.next().asText());
        }
        return result;
    }

    private StandardAuthorizer newPrivateAuthorizer(StoredAcls stored, Set<StandardAcl> roleAcls) {
        Map<Uuid, StandardAcl> acls = new HashMap<>(stored.byId());
        for (StandardAcl acl : roleAcls) {
            // Kafka rejects an ACL whose content already exists.
            if (stored.values().contains(acl)) {
                continue;
            }
            Uuid id = Uuid.randomUuid();
            while (acls.containsKey(id)) {
                id = Uuid.randomUuid();
            }
            acls.put(id, acl);
        }

        StandardAuthorizer authorizer = new StandardAuthorizer();
        authorizer.configure(configs);
        authorizer.withPluginMetrics(pluginMetrics);
        authorizer.loadSnapshot(acls);
        authorizer.completeInitialLoad();
        return authorizer;
    }

    /**
     * Immutable copy of the stored KRaft ACLs, plus the private authorizers built from exactly this copy.
     * Replaced as a whole on every ACL change, so a cached private authorizer never holds outdated stored ACLs.
     */
    private record StoredAcls(Map<Uuid, StandardAcl> byId, Set<StandardAcl> values,
                              ConcurrentMap<Set<StandardAcl>, StandardAuthorizer> privateAuthorizers) {

        static StoredAcls of(Map<Uuid, StandardAcl> acls) {
            return new StoredAcls(Map.copyOf(acls), Set.copyOf(acls.values()), new ConcurrentHashMap<>());
        }

        StoredAcls with(Uuid id, StandardAcl acl) {
            Map<Uuid, StandardAcl> acls = new HashMap<>(byId);
            acls.put(id, acl);
            return of(acls);
        }

        StoredAcls without(Uuid id) {
            Map<Uuid, StandardAcl> acls = new HashMap<>(byId);
            acls.remove(id);
            return of(acls);
        }

        StoredAcls withEmptyCache() {
            return new StoredAcls(byId, values, new ConcurrentHashMap<>());
        }
    }
}
