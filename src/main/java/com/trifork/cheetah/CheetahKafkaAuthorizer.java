package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.ConfigException;
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

public class CheetahKafkaAuthorizer extends AclAuthorizer
{
    static final Logger LOG = LoggerFactory.getLogger(CheetahKafkaAuthorizer.class.getName());
    private String topicClaimName;

    @Override
    public void configure ( Map<String, ?> configs )
    {
        CheetahConfig config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");

        super.configure(configs);
    }

    private CheetahConfig convertToCheetahConfig ( Map<String, ?> configs )
    {
        Properties p = new Properties();
        String[] keys = {
            CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME
        };

        for (var key : keys) {
            ConfigUtil.putIfNotNull(p, key, configs.get(key));
        }

        return new CheetahConfig(p);
    }

    @Override
    public List<AuthorizationResult> authorize ( AuthorizableRequestContext requestContext, List<Action> actions )
    {
        List<AuthorizationResult> results = new ArrayList<>(actions.size());
        if (!(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            if (super.isSuperUser(requestContext.principal())) {
                LOG.debug(String.format("Superuser: %s", requestContext.principal().getName()));
                return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
            } else {
                return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
            }
        }

        var principal = (OAuthKafkaPrincipal) requestContext.principal();

        String topicClaim;
        try {
            topicClaim = principal.getJwt().getJSON().get(topicClaimName).asText();
        } catch (Exception e) {
            LOG.warn(String.format("JWT does not have \"%s\" claim", topicClaimName));
            return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
        }

        List<TopicAccess> topicAccesses = extractAccesses(topicClaim);

        for (Action action : actions) {
            if (isClusterOrGroup(action) || checkJwtClaims(topicAccesses, action)) {
                results.add(AuthorizationResult.ALLOWED);
                continue;
            }

            LOG.debug("Action was Denied");
            LOG.debug(action.toString());
            results.add(AuthorizationResult.DENIED);
        }

        return results;

    }

    public static List<TopicAccess> extractAccesses ( String topicClaim )
    {
        ArrayList<TopicAccess> result = new ArrayList<>();
        String[] accesses = topicClaim.split(",");

        for (String access : accesses) {
            try {
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
            if (action.resourcePattern().resourceType().equals(ResourceType.TOPIC) &&
                matchTopicPattern(action, t) &&
                (t.operation.equals(AclOperation.ALL) ||
                    t.operation.equals(AclOperation.ANY) ||
                    action.operation().equals(t.operation))) {
                return true;
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