package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.network.ClientInformation;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.RequestContext;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

class KRaftAuthorizerTest {

        @Test
        void CheckAuthorizeFlowSuperUser() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
                                "super.users", "User:ANONYMOUS"));

                AuthorizableRequestContext context = new RequestContext(
                                new RequestHeader(ApiKeys.CONTROLLER_REGISTRATION, (short) 1, "", 1),
                                "",
                                null,
                                new KafkaPrincipal("User", "ANONYMOUS"),
                                new ListenerName(""),
                                SecurityProtocol.PLAINTEXT,
                                new ClientInformation("", ""),
                                false);
                List<Action> actions = List.of(new Action(AclOperation.CLUSTER_ACTION,
                                new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                                1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                Assertions.assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void CheckAuthorizeFlowPerformanceTesterAllowTopic() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
                                "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_*_all");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                                new MockBearerTokenWithPayload("User:doesntmatter",
                                                new HashSet<>(Arrays.asList("")),
                                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                                "", JSONUtil.asJson("{}"),
                                                claims));

                AuthorizableRequestContext context = new RequestContext(
                                new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8,
                                                "", 1),
                                "",
                                null,
                                principal,
                                new ListenerName(""),
                                SecurityProtocol.PLAINTEXT,
                                new ClientInformation("", ""),
                                false);
                List<Action> actions = List.of(new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                                1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                Assertions.assertEquals(result, List.of(AuthorizationResult.ALLOWED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }

        @Test
        void CheckAuthorizeFlowPerformanceTesterDenyTopic() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka_",
                                "super.users", "User:doesntmatter"));

                ObjectNode claims = JSONUtil.newObjectNode();
                claims.putArray("roles").add("Kafka_no_permission_to_read_from_this_topic");

                OAuthKafkaPrincipal principal = new OAuthKafkaPrincipal("", "",
                                new MockBearerTokenWithPayload("User:doesntmatter",
                                                new HashSet<>(Arrays.asList("")),
                                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                                "", JSONUtil.asJson("{}"),
                                                claims));

                AuthorizableRequestContext context = new RequestContext(
                                new RequestHeader(ApiKeys.OFFSET_COMMIT, (short) 8,
                                                "", 1),
                                "",
                                null,
                                principal,
                                new ListenerName(""),
                                SecurityProtocol.PLAINTEXT,
                                new ClientInformation("", ""),
                                false);

                List<Action> actions = List.of(new Action(AclOperation.READ,
                                new ResourcePattern(ResourceType.GROUP, "", PatternType.LITERAL),
                                1, true, true));

                List<AuthorizationResult> result = authorizer.authorize(context, actions);
                Assertions.assertEquals(result, List.of(AuthorizationResult.DENIED));

                try {
                        authorizer.close();
                } catch (Exception ignore) { }
        }


}
