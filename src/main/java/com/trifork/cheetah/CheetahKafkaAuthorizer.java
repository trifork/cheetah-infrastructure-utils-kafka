package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import kafka.security.authorizer.AclAuthorizer;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.apache.kafka.common.acl.AclOperation.*;

public class CheetahKafkaAuthorizer extends AclAuthorizer {
    static final Logger LOG = LoggerFactory.getLogger(CheetahKafkaAuthorizer.class.getName());
    private String topicClaimName;
    private String prefix;
    private boolean isClaimList;

    @Override
    public void configure(Map<String, ?> configs) {
        CheetahConfig config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        prefix = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        super.configure(configs);
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
        for (var key : keys) {
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
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        List<AuthorizationResult> results = new ArrayList<>(actions.size());
        if (!(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            return handleSuperUsers(requestContext, actions);
        }

        var principal = (OAuthKafkaPrincipal) requestContext.principal();

        List<String> accesses;
        try {
            accesses = extractAccessClaim(principal);
        } catch (Exception e) {
            LOG.warn(String.format("JWT does not have \"%s\" claim", topicClaimName));
            return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
        }

        List<String> topicAccessesRaw = accesses.stream()
                .filter(access -> !access.startsWith(prefix + "_Cluster"))
                .collect(Collectors.toList());

        List<String> clusterAccessesRaw = accesses.stream()
                .filter(access -> access.startsWith(prefix + "_Cluster"))
                .collect(Collectors.toList());

        List<TopicAccess> topicAccesses = extractTopicAccesses(topicAccessesRaw, prefix);
        List<ClusterAccess> clusterAccesses = extractClusterAccesses(clusterAccessesRaw, prefix);

        for (Action action : actions) {
            if (checkTopicJwtClaims(topicAccesses, action)) {
                results.add(AuthorizationResult.ALLOWED);
                continue;
            }

            if (checkClusterJwtClaims(clusterAccesses, action)) {
                results.add(AuthorizationResult.ALLOWED);
                continue;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Action was Denied");
                LOG.debug(action.toString());
            }
            results.add(AuthorizationResult.DENIED);
        }

        return results;

    }

    private List<String> extractAccessClaim(OAuthKafkaPrincipal principal) {
        List<String> result = new ArrayList<>();
        if (isClaimList) {
            var jwt = principal.getJwt();
            var topicClaim = jwt.getClaimsJSON().get(topicClaimName);
            var iterator = topicClaim.elements();
            while (iterator.hasNext()) {
                result.add(iterator.next().asText());
            }
        } else {
            result = Arrays.asList(principal.getJwt().getClaimsJSON().get(topicClaimName).asText().split(","));
        }
        return result;
    }

    private List<AuthorizationResult> handleSuperUsers(AuthorizableRequestContext requestContext,
            List<Action> actions) {
        if (super.isSuperUser(requestContext.principal())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Superuser: %s", requestContext.principal().getName()));
            }
            return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
        } else {
            return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
        }
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
        return result;
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
        return result;
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
                return List.of(ANY, ALL, READ, WRITE, DESCRIBE).contains(claimedOperation);
            default:
                return false;
        }
    }

    private static boolean checkClusterAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case IDEMPOTENT_WRITE:
                return List.of(ANY, ALL, WRITE).contains(claimedOperation);
            default:
                return false;
        }
    }

    private static boolean checkTopicAccess(AclOperation claimedOperation, Action requestedAction) {
        switch (requestedAction.operation()) {
            case DESCRIBE:
                // WRITE, READ, DELETE and ALTER implicitly allows DESCRIBE
                return List.of(ANY, ALL, WRITE, READ, DELETE, ALTER, DESCRIBE).contains(claimedOperation);
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
            return action.resourcePattern().name().endsWith((t.pattern.replace("*", "")));
        } else {
            return action.resourcePattern().name().equals(t.pattern);
        }
    }

}