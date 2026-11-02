package com.trifork.cheetah;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class AuthorizerTestSupport {

    private AuthorizerTestSupport() {
    }

    static Map<String, Object> configs(String superUsers) {
        Map<String, Object> configs = new HashMap<>();
        configs.put("node.id", "1");
        configs.put("cheetah.authorization.claim.name", "roles");
        configs.put("cheetah.authorization.claim.is-list", "true");
        configs.put("cheetah.authorization.prefix", "Kafka_");
        configs.put("super.users", superUsers);
        return configs;
    }

    /** Configured and loaded, as after broker startup. */
    static CheetahKRaftAuthorizer readyAuthorizer(String superUsers) {
        CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
        authorizer.configure(configs(superUsers));
        authorizer.completeInitialLoad();
        return authorizer;
    }

    static AuthorizableRequestContext oauth(String name, String... roles) {
        ObjectNode claims = JSONUtil.newObjectNode();
        ArrayNode roleArray = claims.putArray("roles");
        for (String role : roles) {
            roleArray.add(role);
        }
        return oauthWithClaims(name, claims);
    }

    static AuthorizableRequestContext oauthWithClaims(String name, ObjectNode claims) {
        long now = System.currentTimeMillis();
        MockBearerTokenWithPayload token = new MockBearerTokenWithPayload(name, new HashSet<>(), now, now + 60_000,
                null, "", JSONUtil.asJson("{}"), claims);
        return context(new OAuthKafkaPrincipal(KafkaPrincipal.USER_TYPE, name, token));
    }

    static AuthorizableRequestContext plain(String name) {
        return context(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, name));
    }

    static AuthorizableRequestContext context(KafkaPrincipal principal) {
        return new AuthorizableRequestContext() {
            @Override
            public String listenerName() {
                return "OAUTH";
            }

            @Override
            public SecurityProtocol securityProtocol() {
                return SecurityProtocol.SASL_SSL;
            }

            @Override
            public KafkaPrincipal principal() {
                return principal;
            }

            @Override
            public InetAddress clientAddress() {
                return InetAddress.getLoopbackAddress();
            }

            @Override
            public int requestType() {
                return ApiKeys.FETCH.id;
            }

            @Override
            public int requestVersion() {
                return 0;
            }

            @Override
            public String clientId() {
                return "test-client";
            }

            @Override
            public int correlationId() {
                return 0;
            }
        };
    }

    static List<Action> action(AclOperation operation, ResourceType type, String name) {
        return List.of(new Action(operation, new ResourcePattern(type, name, PatternType.LITERAL), 1, true, true));
    }

    static StandardAcl storedAcl(String user, ResourceType type, String name, AclOperation operation,
                                 AclPermissionType permission) {
        return new StandardAcl(type, name, PatternType.LITERAL, "User:" + user, "*", operation, permission);
    }
}
