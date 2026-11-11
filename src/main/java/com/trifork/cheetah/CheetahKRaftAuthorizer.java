package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.ConfigException;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.utils.SecurityUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;
import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.stream.Collectors;

import static org.apache.kafka.common.acl.AclOperation.*;

public class CheetahKRaftAuthorizer implements ClusterMetadataAuthorizer {
    static final Logger LOG = LoggerFactory.getLogger(CheetahKRaftAuthorizer.class.getName());
    /** Kafka's audit logger, the same name StandardAuthorizer uses. Configured by Kafka's log4j settings. */
    static final Logger AUDIT_LOG = LoggerFactory.getLogger("kafka.authorizer.logger");
    static final String RULE_SUPER_USER = "SuperUser";
    static final String RULE_JWT_CLAIMS = "JwtClaims";
    static final String RULE_DEFAULT_DENY = "DefaultDeny";
    static final String RULE_NO_CLAIM = "NoClaim";
    private String topicClaimName;
    private String prefix;
    private boolean isClaimList;
    private Set<UserSpec> superUsers;

    /**
     * A counter used to generate an instance number for each instance of this class
     */
    private static final AtomicInteger INSTANCE_NUMBER_COUNTER = new AtomicInteger(1);

    /**
     * An instance number used in {@link #toString()} method, to easily track the number of instances of this class
     */
    private final int instanceNumber = INSTANCE_NUMBER_COUNTER.getAndIncrement();

    private StandardAuthorizer delegate;

    @Override
    public void configure(Map<String, ?> configs) {
        CheetahConfig config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        prefix = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        configureSuperUsers(configs);

        delegate = instantiateStandardAuthorizer();
        delegate.configure(configs);
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

    private void configureSuperUsers(Map<String, ?> configs) {
        String supers = (String) configs.get(CheetahConfig.CHEETAH_AUTHORIZATION_SUPER_USERS);
        if (supers != null) {
            superUsers = Arrays.stream(supers.split(";"))
                    .map(UserSpec::of)
                    .collect(Collectors.toSet());
        }
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

    @Override
    public void completeInitialLoad() {
        delegate.completeInitialLoad();
    }

    @Override
    public void completeInitialLoad(Exception e) {
        if (e != null) {
            e.printStackTrace();
        }
        delegate.completeInitialLoad(e);
    }

    @Override
    public void loadSnapshot(Map<Uuid, StandardAcl> acls) {
        delegate.loadSnapshot(acls);
    }

    @Override
    public void addAcl(Uuid id, StandardAcl acl) {
        delegate.addAcl(id, acl);
    }

    @Override
    public void removeAcl(Uuid id) {
        delegate.removeAcl(id);
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

    /**
     * Allowed if the principal may perform the operation on at least one resource of the given type.
     * Kafka asks this for (WRITE, TOPIC) on InitProducerId when IDEMPOTENT_WRITE is denied.
     * Access comes from the JWT roles, not from ACLs, so this is answered from the roles
     * (a role on any topic counts) rather than by the delegate.
     */
    @Override
    public AuthorizationResult authorizeByResourceType(AuthorizableRequestContext requestContext, AclOperation op, ResourceType resourceType) {
        SecurityUtils.authorizeByResourceTypeCheckArgs(op, resourceType);
        List<Action> actions = List.of(new Action(op,
                new ResourcePattern(resourceType, ResourcePattern.WILDCARD_RESOURCE, PatternType.LITERAL),
                1, false, false));
        if (!(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            return handleSuperUsers(requestContext, actions).get(0);
        }
        return authorizeFromJwt(requestContext, (OAuthKafkaPrincipal) requestContext.principal(), actions, true).get(0);
    }

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        if (!(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            return handleSuperUsers(requestContext, actions);
        }
        return authorizeFromJwt(requestContext, (OAuthKafkaPrincipal) requestContext.principal(), actions, false);
    }

    private List<AuthorizationResult> authorizeFromJwt(AuthorizableRequestContext requestContext, OAuthKafkaPrincipal principal,
                                                       List<Action> actions, boolean anyTopic) {
        List<AuthorizationResult> results = new ArrayList<>(actions.size());

        List<String> accesses;
        try {
            accesses = extractAccessClaim(principal);
        } catch (Exception e) {
            LOG.warn(String.format("JWT does not have \"%s\" claim", topicClaimName));
            for (Action action : actions) {
                audit(requestContext, action, AuthorizationResult.DENIED, RULE_NO_CLAIM);
            }
            return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
        }

        List<String> topicAccessesRaw = accesses.stream()
                .filter(access -> !access.startsWith(prefix + "_Cluster"))
                .collect(Collectors.toList());

        List<String> clusterAccessesRaw = accesses.stream()
                .filter(access -> access.startsWith(prefix + "_Cluster"))
                .collect(Collectors.toList());

        List<TopicAccess> topicAccesses = extractTopicAccesses(topicAccessesRaw, prefix);
        if (anyTopic) {
            topicAccesses = topicAccesses.stream()
                    .map(t -> new TopicAccess("*", t.operation.name()))
                    .collect(Collectors.toList());
        }
        List<ClusterAccess> clusterAccesses = extractClusterAccesses(clusterAccessesRaw, prefix);

        for (Action action : actions) {
            if (checkTopicJwtClaims(topicAccesses, action) || checkClusterJwtClaims(clusterAccesses, action)) {
                audit(requestContext, action, AuthorizationResult.ALLOWED, RULE_JWT_CLAIMS);
                results.add(AuthorizationResult.ALLOWED);
                continue;
            }

            audit(requestContext, action, AuthorizationResult.DENIED, RULE_DEFAULT_DENY);
            results.add(AuthorizationResult.DENIED);
        }

        return results;
    }

    /**
     * Writes one audit line per decision to Kafka's own audit logger, so it lands in the same appender, file and
     * level configuration as the lines StandardAuthorizer writes. The format and level rules mirror the private
     * StandardAuthorizerData.logAuditMessage; AuditLogFormatTest fails if they drift apart.
     */
    static void audit(AuthorizableRequestContext requestContext, Action action, AuthorizationResult result, String rule) {
        boolean allowed = result == AuthorizationResult.ALLOWED;
        boolean explicit = allowed ? action.logIfAllowed() : action.logIfDenied();
        // Kafka logs an explicitly requested access check at DEBUG (allowed) or INFO (denied), anything else at TRACE.
        // Kafka runs slf4j 1.7, so this must not use the 2.x API (isEnabledForLevel, atLevel): that fails at runtime.
        if (!explicit) {
            if (AUDIT_LOG.isTraceEnabled()) {
                AUDIT_LOG.trace(buildAuditMessage(requestContext, action, allowed, rule));
            }
        } else if (allowed) {
            if (AUDIT_LOG.isDebugEnabled()) {
                AUDIT_LOG.debug(buildAuditMessage(requestContext, action, true, rule));
            }
        } else if (AUDIT_LOG.isInfoEnabled()) {
            AUDIT_LOG.info(buildAuditMessage(requestContext, action, false, rule));
        }
    }

    static String buildAuditMessage(AuthorizableRequestContext requestContext, Action action, boolean allowed, String rule) {
        KafkaPrincipal principal = requestContext.principal();
        ResourcePattern resource = action.resourcePattern();
        // Type and name only: OAuthKafkaPrincipal.toString() would add groups and part of the token.
        return "Principal = " + principal.getPrincipalType() + ":" + principal.getName()
                + " is " + (allowed ? "Allowed" : "Denied")
                + " operation = " + action.operation()
                + " from host = " + requestContext.clientAddress().getHostAddress()
                + " on resource = " + SecurityUtils.resourceTypeName(resource.resourceType())
                + ":" + resource.patternType() + ":" + resource.name()
                + " for request = " + ApiKeys.forId(requestContext.requestType()).name
                + " with resourceRefCount = " + action.resourceReferenceCount()
                + " based on rule " + rule;
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

    private List<AuthorizationResult> handleSuperUsers(AuthorizableRequestContext requestContext,
                                                       List<Action> actions) {
        UserSpec user = new UserSpec(requestContext.principal().getPrincipalType(), requestContext.principal().getName());
        boolean superUser = superUsers.contains(user);
        AuthorizationResult result = superUser ? AuthorizationResult.ALLOWED : AuthorizationResult.DENIED;
        for (Action action : actions) {
            audit(requestContext, action, result, superUser ? RULE_SUPER_USER : RULE_DEFAULT_DENY);
        }
        return Collections.nCopies(actions.size(), result);
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

    public static List<ClusterAccess> extractClusterAccesses(List<String> accesses, String prefix) {
        ArrayList<ClusterAccess> result = new ArrayList<>();

        for (String access : accesses) {
            try {
                if (!access.startsWith(prefix)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s does not have the correct prefix. Skipping...", access));
                    }
                    continue;
                }
                access = access.substring(prefix.length());

                int splitIndex = access.lastIndexOf('_');

                if (splitIndex == -1) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info(String.format(
                                "%s does not follow correct pattern for cluster access (<prefix>_Cluster_<operation>)",
                                access));
                    }
                    continue;
                }

                String operation = access.substring(splitIndex + 1);
                result.add(new ClusterAccess(operation));
            } catch (Exception e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn(String.format("Error decoding cluster claim: %s %n %s", access, e));
                }
            }
        }

        // Allow only valid operations for cluster
        Set<AclOperation> validOps = EnumSet.of(
                AclOperation.CREATE,
                AclOperation.ALTER,
                AclOperation.DESCRIBE,
                AclOperation.CLUSTER_ACTION,
                AclOperation.DESCRIBE_CONFIGS,
                AclOperation.ALTER_CONFIGS,
                AclOperation.IDEMPOTENT_WRITE,
                AclOperation.ALL,
                AclOperation.ANY
        );
        validOps.add(AclOperation.ALL);
        validOps.add(AclOperation.ANY);
        ArrayList<ClusterAccess> validAccesses = new ArrayList<>();
        for (ClusterAccess c : result) {
            if (!validOps.contains(c.operation)) {
                if (LOG.isInfoEnabled()) {
                    LOG.info(String.format("Invalid operation %s for cluster", c.operation));
                }
            } else {
                validAccesses.add(c);
            }
        }

        return validAccesses;
    }

    public static List<TopicAccess> extractTopicAccesses(List<String> accesses, String prefix) {
        ArrayList<TopicAccess> result = new ArrayList<>();

        for (String access : accesses) {
            try {
                if (!access.startsWith(prefix)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s does not have the correct prefix. Skipping...", access));
                    }
                    continue;
                }
                access = access.substring(prefix.length());

                int splitIndex = access.lastIndexOf('_');

                if (splitIndex == -1) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info(String.format(
                                "%s does not follow correct pattern for topic access (<prefix>_<topic-name>_<operation>)",
                                access));
                    }
                    continue;
                }

                String pattern = access.substring(0, splitIndex);
                String operation = access.substring(splitIndex + 1);
                result.add(new TopicAccess(pattern, operation));
            } catch (Exception e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn(String.format("Error decoding topics claim: %s %n %s", access, e));
                }
            }
        }

        // Allow only valid operations for topics
        Set<AclOperation> validOps = EnumSet.of(
                AclOperation.READ,
                AclOperation.WRITE,
                AclOperation.CREATE,
                AclOperation.DELETE,
                AclOperation.ALTER,
                AclOperation.DESCRIBE,
                AclOperation.CLUSTER_ACTION,
                AclOperation.DESCRIBE_CONFIGS,
                AclOperation.ALTER_CONFIGS,
                AclOperation.ALL,
                AclOperation.ANY
        );
        ArrayList<TopicAccess> validAccesses = new ArrayList<>();
        for (TopicAccess t : result) {
            if (!validOps.contains(t.operation)) {
                if (LOG.isInfoEnabled()) {
                    LOG.info(String.format("Invalid operation %s for topic %s", t.operation, t.pattern));
                }
            } else {
                validAccesses.add(t);
            }
        }

        return validAccesses;
    }

    public static boolean checkTopicJwtClaims(List<TopicAccess> topicAccesses, Action requestedAction) {
        for (TopicAccess t : topicAccesses) {
            switch (requestedAction.resourcePattern().resourceType()) {
                case TOPIC:
                    if (matchTopicPattern(requestedAction, t) && checkTopicAccess(t.operation, requestedAction))
                        return true;
                    break;
                case CLUSTER: // check for some default cluster actions based on topic claim
                    if (checkClusterAccess(t.operation, requestedAction))
                        return true;
                    break;
                case GROUP: // check for some default (consumer)group actions based on topic claim
                    if (checkGroupAccess(t.operation, requestedAction))
                        return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    public static boolean checkClusterJwtClaims(List<ClusterAccess> clusterAccesses, Action requestedAction) {
        for (ClusterAccess c : clusterAccesses) {
            switch (requestedAction.resourcePattern().resourceType()) {
                case CLUSTER:
                    return claimSupportsRequestedAction(c.operation, requestedAction);
                default:
                    break;
            }
        }
        return false;
    }

    private static boolean checkGroupAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case READ:
                return List.of(ANY, ALL, READ).contains(claimedOperation);
            case DESCRIBE:
                return List.of(ANY, ALL, READ, DESCRIBE).contains(claimedOperation);
            case DELETE:
                return List.of(ANY, ALL, DELETE).contains(claimedOperation);
            default:
                return false;
        }
    }

    private static boolean checkClusterAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case CREATE:
                return List.of(ANY, ALL, CREATE).contains(claimedOperation);
            case CLUSTER_ACTION:
                return List.of(ANY, ALL, CLUSTER_ACTION).contains(claimedOperation);
            case DESCRIBE_CONFIGS:
                return List.of(ANY, ALL, ALTER_CONFIGS, DESCRIBE_CONFIGS).contains(claimedOperation);
            case ALTER_CONFIGS:
                return List.of(ANY, ALL, ALTER_CONFIGS).contains(claimedOperation);
            case IDEMPOTENT_WRITE:
                return List.of(ANY, ALL, WRITE).contains(claimedOperation);
            case ALTER:
                return List.of(ANY, ALL, ALTER).contains(claimedOperation);
            case DESCRIBE: // Should this add CREATE?
                return List.of(ANY, ALL, ALTER, DESCRIBE).contains(claimedOperation);
            default:
                return false;
        }
    }

    private static boolean checkTopicAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case READ:
                return List.of(ANY, ALL, READ).contains(claimedOperation);
            case WRITE:
                return List.of(ANY, ALL, WRITE).contains(claimedOperation);
            case CREATE:
                return List.of(ANY, ALL, CREATE).contains(claimedOperation);
            case DELETE:
                return List.of(ANY, ALL, DELETE).contains(claimedOperation);
            case ALTER:
                return List.of(ANY, ALL, ALTER).contains(claimedOperation);
            case DESCRIBE:
                // READ, WRITE, DELETE, and ALTER implicitly allow DESCRIBE
                return List.of(ANY, ALL, READ, WRITE, DELETE, ALTER, DESCRIBE).contains(claimedOperation);
            case DESCRIBE_CONFIGS:
                // ALTER_CONFIGS implicitly allows DESCRIBE_CONFIGS
                return List.of(ANY, ALL, ALTER_CONFIGS, DESCRIBE_CONFIGS).contains(claimedOperation);
            case ALTER_CONFIGS:
                return List.of(ANY, ALL, ALTER_CONFIGS).contains(claimedOperation);
            default:
                return claimSupportsRequestedAction(claimedOperation, requestedAction);

        }
    }

    private static boolean claimSupportsRequestedAction(AclOperation claimedOperation, Action requestedAction) {
        return List.of(ANY, ALL).contains(claimedOperation)
                || requestedAction.operation().equals(claimedOperation);
    }

    private static boolean matchTopicPattern(Action action, TopicAccess t) {
        if (t.pattern.endsWith("*")) {
            return action.resourcePattern().name().startsWith(t.pattern.replace("*", ""));
        } else if (t.pattern.startsWith("*")) {
            // This is not part of the default patterns
            // https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/resource/PatternType.java#L70
            return action.resourcePattern().name().endsWith((t.pattern.replace("*", "")));
        } else {
            return action.resourcePattern().name().equals(t.pattern);
        }
    }
}
