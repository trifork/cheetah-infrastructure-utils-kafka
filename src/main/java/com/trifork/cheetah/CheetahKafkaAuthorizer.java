package com.trifork.cheetah;

import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import kafka.security.authorizer.AclAuthorizer;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CheetahKafkaAuthorizer extends AclAuthorizer
{
    static final Logger LOG = LoggerFactory.getLogger(CheetahKafkaAuthorizer.class.getName());

    @Override
    public List<AuthorizationResult> authorize ( AuthorizableRequestContext requestContext, List<Action> actions )
    {
        if (requestContext.listenerName().equals("PLAIN-9092")) {
            return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
        }
        List<AuthorizationResult> results = new ArrayList<>(actions.size());
        if (!(requestContext.principal() instanceof OAuthKafkaPrincipal)) {
            if (super.isSuperUser(requestContext.principal())) {
                LOG.info(String.format("Superuser: %s", requestContext.principal().getName()));
                return Collections.nCopies(actions.size(), AuthorizationResult.ALLOWED);
            } else {
                return Collections.nCopies(actions.size(), AuthorizationResult.DENIED);
            }
        }

        var principal = (OAuthKafkaPrincipal) requestContext.principal();

        var topicClaim = principal.getJwt().getJSON().get("topics").asText();

        List<TopicAccess> topicAccesses = extractAccesses(topicClaim);

        for (Action action : actions) {
            isClusterOrGroup(action);
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
                action.resourcePattern().name().startsWith(t.pattern.replace("*", "")) &&
                (t.operation.equals(AclOperation.ALL) ||
                    t.operation.equals(AclOperation.ANY) ||
                    action.operation().equals(t.operation))) {
                return true;
            }

        }
        return false;
    }

    private boolean isClusterOrGroup ( Action action )
    {

        return action.resourcePattern().resourceType().equals(ResourceType.CLUSTER) || action.resourcePattern().resourceType().equals(ResourceType.GROUP);
    }
}