package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import kafka.security.authorizer.AclAuthorizer;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.kafka.common.acl.AclOperation.*;

public class CheetahKafkaAuthorizer extends AclAuthorizer
{
    static final Logger LOG = LoggerFactory.getLogger(CheetahKafkaAuthorizer.class.getName());
    private String topicClaimName;
    private String prefix;
    private boolean isClaimList;

    @Override
    public void configure ( Map<String, ?> configs )
    {
        CheetahConfig config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        prefix = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        super.configure(configs);
    }

    private CheetahConfig convertToCheetahConfig ( Map<String, ?> configs )
    {
        Properties p = new Properties();
        String[] keys = {
            CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME,
            CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX,
            CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST
        };

        for (var key : keys) {
            ConfigUtil.putIfNotNull(p, key, configs.get(key));
        }

        return new CheetahConfig(p);
    }

    @Override
    public AuthorizationResult authorizeByResourceType ( AuthorizableRequestContext requestContext, AclOperation op, ResourceType resourceType )
    {
        LOG.warn("AuthorizeByResourceType called");
        var res = super.authorizeByResourceType(requestContext, op, resourceType);
        if (LOG.isWarnEnabled()) {
            LOG.warn(String.format("AuthorizationResult: %s", res.toString()));
        }
        return res;
    }

    @Override
    public List<AuthorizationResult> authorize ( AuthorizableRequestContext requestContext, List<Action> actions )
    {
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

        List<TopicAccess> topicAccesses = extractAccesses(accesses, prefix);

        for (Action action : actions) {
            if (isClusterOrGroup(action) || checkJwtClaims(topicAccesses, action)) {
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

    private List<String> extractAccessClaim ( OAuthKafkaPrincipal principal )
    {
        List<String> result = new ArrayList<>();
        if (isClaimList) {
            var topicClaim = principal.getJwt().getJSON().get(topicClaimName);
            var iterator = topicClaim.elements();
            while (iterator.hasNext()) {
                result.add(iterator.next().asText());
            }
        } else {
            result = Arrays.asList(principal.getJwt().getJSON().get(topicClaimName).asText().split(","));
        }
        return result;
    }

    private List<AuthorizationResult> handleSuperUsers ( AuthorizableRequestContext requestContext, List<Action> actions )
    {
        if (super.isSuperUser(requestContext.principal())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Superuser: %s", requestContext.principal().getName()));
            }
            return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
        } else {
            return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
        }
    }

    public static List<TopicAccess> extractAccesses ( List<String> accesses, String prefix )
    {
        ArrayList<TopicAccess> result = new ArrayList<>();


        for (String access : accesses) {
            try {
                access = access.startsWith(prefix) ? access.substring(prefix.length()) : access;

                String[] a = access.split("_");
                result.add(new TopicAccess(a[0], a[1]));
            } catch (Exception e) {
                LOG.warn(String.format("Error decoding topics claim: %s", access));
                LOG.debug(e.getMessage());
            }
        }
        return result;
    }

    public static boolean checkJwtClaims ( List<TopicAccess> topicAccesses, Action action )
    {
        for (TopicAccess t : topicAccesses) {
            // Action must be of type Topic and topic pattern must match
            if (!action.resourcePattern().resourceType().equals(ResourceType.TOPIC) ||
                !matchTopicPattern(action, t)) {
                continue;
            }

            // ALL and ANY grant access to everything
            if (List.of(ALL, ANY).contains(t.operation)) return true;

            switch (action.operation()) {
                case DESCRIBE:
                    // WRITE, READ, DELETE and ALTER implicitly allows DESCRIBE
                    if (List.of(WRITE, READ, DELETE, ALTER, DESCRIBE).contains(t.operation)) return true;
                default:
                    if (t.operation.equals(action.operation())) return true;

            }
        }
        return false;
    }

    private static boolean matchTopicPattern ( Action action, TopicAccess t )
    {
        if (t.pattern.endsWith("*")) {
            return action.resourcePattern().name().startsWith(t.pattern.replace("*", ""));
        } else if (t.pattern.startsWith("*")) {
            return action.resourcePattern().name().endsWith((t.pattern.replace("*", "")));
        } else {
            return action.resourcePattern().name().equals(t.pattern);
        }
    }

    private boolean isClusterOrGroup ( Action action )
    {
        return action.resourcePattern().resourceType().equals(ResourceType.CLUSTER) || action.resourcePattern().resourceType().equals(ResourceType.GROUP);
    }
}