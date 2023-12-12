package com.trifork.cheetah;

import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;
import io.strimzi.kafka.oauth.common.ConfigUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import kafka.security.authorizer.AclAuthorizer;

import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.trifork.cheetah.AccessClaim.*;
import com.trifork.cheetah.ClusterAuthorization.*;
import com.trifork.cheetah.TopicAuthorization.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CheetahKafkaAuthorizer extends AclAuthorizer {
    static final Logger LOG = LoggerFactory.getLogger(CheetahKafkaAuthorizer.class.getName());
    private String topicClaimName;
    private String prefix;
    private boolean isClaimList;

    @Override
    public void configure(Map<String, ?> configs) {
        var config = convertToCheetahConfig(configs);

        topicClaimName = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "topics");
        prefix = config.getValue(CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "");
        isClaimList = config.getValueAsBoolean(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, false);
        super.configure(configs);
    }

    private CheetahConfig convertToCheetahConfig(Map<String, ?> configs) {
        var p = new Properties();
        String[] keys = {
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME,
                CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX,
                CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST
        };

        var logString = new StringBuilder("CheetahKafkaAuthorizer values:\n");
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

        // Separating topic and cluster accesses
        List<String> topicAccessList = new ArrayList<>();
        List<String> clusterAccessList = new ArrayList<>();
        for (String access : accesses) {
            if (access.startsWith(prefix + "_Cluster")) {
                clusterAccessList.add(access);
            } else {
                topicAccessList.add(access);
            }
        }

        // Extracting Topic and Cluster Accesses
        TopicAccessExtractor topicAccessExtractor = new TopicAccessExtractor();
        ClusterAccessExtractor clusterAccessExtractor = new ClusterAccessExtractor();
        List<TopicAccess> topicAccesses = topicAccessExtractor.extractAccesses(topicAccessList, prefix);
        List<ClusterAccess> clusterAccesses = clusterAccessExtractor.extractAccesses(clusterAccessList, prefix);

        return actions.stream().map(
                action -> (checkTopicJwtClaims(topicAccesses, action) || checkClusterJwtClaims(clusterAccesses, action))
                        ? AuthorizationResult.ALLOWED
                        : AuthorizationResult.DENIED)
                .collect(Collectors.toList());
    }

    private List<String> extractAccessClaim(OAuthKafkaPrincipal principal) {
        BearerTokenWithPayload jwt = principal.getJwt();
        JsonNode topicClaim = jwt.getClaimsJSON().get(topicClaimName);

        return isClaimList
                ? StreamSupport.stream(topicClaim.spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList())
                : Arrays.asList(topicClaim.asText().split(","));
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

    public static boolean checkTopicJwtClaims(List<TopicAccess> topicAccesses, Action requestedAction) {
        Map<ResourceType, TopicAuthorizationStrategy> strategies = Map.of(
                ResourceType.TOPIC, new TopicResourceTopicAuthorizationStrategy(),
                ResourceType.CLUSTER, new ClusterResourceTopicAuthorizationStrategy(),
                ResourceType.GROUP, new GroupResourceTopicAuthorizationStrategy());

        return Optional.ofNullable(strategies.get(requestedAction.resourcePattern().resourceType()))
                .map(strategy -> strategy.authorize(requestedAction, topicAccesses))
                .orElse(false);
    }

    public static boolean checkClusterJwtClaims(List<ClusterAccess> clusterAccesses, Action requestedAction) {
        Map<ResourceType, ClusterAuthorizationStrategy> strategies = Map.of(
                ResourceType.CLUSTER, new ClusterResourceAuthorizationStrategy());

        return Optional.ofNullable(strategies.get(requestedAction.resourcePattern().resourceType()))
                .map(strategy -> strategy.authorize(requestedAction, clusterAccesses))
                .orElse(false);
    }

}