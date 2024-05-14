package com.trifork.cheetah;

import org.apache.kafka.common.Endpoint;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

class CheetahKRaftAuthorizerTest {
        public static final Endpoint PLAINTEXT = new Endpoint("PLAINTEXT",
                        SecurityProtocol.PLAINTEXT,
                        "127.0.0.1",
                        9020);

        static Action newAction(AclOperation aclOperation,
                        ResourceType resourceType,
                        String resourceName) {
                return new Action(aclOperation,
                                new ResourcePattern(resourceType, resourceName, PatternType.LITERAL), 1, false, false);
        }

        static CheetahKRaftAuthorizer createAndInitializeStandardAuthorizer() {
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka",
                                "super.users", "User:doesntmatter"));
                authorizer.start(new AuthorizerTestServerInfo(Collections.singletonList(PLAINTEXT)));
                authorizer.completeInitialLoad();
                return authorizer;
        }

        OAuthKafkaPrincipal createPrincipal(String name, String... roles) {
                ObjectNode claims = JSONUtil.newObjectNode();
                ArrayNode rolesNode = claims.putArray("roles");
                for (String role : roles) {
                        rolesNode.add(role);
                }
                return new OAuthKafkaPrincipal("User", name,
                                new MockBearerTokenWithPayload(name,
                                                new HashSet<>(Arrays.asList("")),
                                                System.currentTimeMillis(), System.currentTimeMillis() + 60000, null,
                                                "", JSONUtil.asJson("{}"),
                                                claims));
        }

        @Test
        void CheckAuthorizeFlowSuperUser() {
                // TODO rework
                CheetahKRaftAuthorizer authorizer = new CheetahKRaftAuthorizer();
                authorizer.configure(Map.of("cheetah.authorization.claim.name", "roles",
                                "cheetah.authorization.claim.is-list", "true",
                                "cheetah.authorization.prefix", "Kafka",
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

        }

        @Test
        void AllowTopicExact() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_mytopic_read");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "mytopic")));

                assertEquals(List.of(AuthorizationResult.ALLOWED), result);
        }

        @Test
        void AllowTopicPrefix() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_myprefix*_read");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "myprefix-and-whatever")));

                assertEquals(List.of(AuthorizationResult.ALLOWED), result);
        }

        @Test
        void DenyDifferentTopic() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_no-permission-to-read-this_read");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.CLUSTER, "mytopic")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }

        @Test
        void DenyDifferentOperation() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_mytopic_write");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "mytopic")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }

        @Test
        void DenyDifferentResourceType() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_mytopic_read");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.CLUSTER, "mytopic")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }

        @Test
        void AllowTopicWildcard() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_*_read");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "mytopic")));

                assertEquals(List.of(AuthorizationResult.ALLOWED), result);
        }

        @Test
        void DenyWildcardDifferentOperation() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_*_write");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "mytopic")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }

        @Test
        void ClusterGrantAll() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_Cluster_all");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.CLUSTER, "doesntmatter")));

                assertEquals(List.of(AuthorizationResult.ALLOWED), result);
        }

        @Test
        void ClusterSpecificOperation() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_Cluster_create");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.CREATE, ResourceType.CLUSTER, "doesntmatter")));

                assertEquals(List.of(AuthorizationResult.ALLOWED), result);
        }

        @Test
        void ClusterDenyDifferentOperation() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_Cluster_alter");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.CREATE, ResourceType.CLUSTER, "doesntmatter")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }

        @Test
        void ClusterAllGrantsTopicPermissions() throws Exception {
                CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();
                OAuthKafkaPrincipal principal = createPrincipal("bob", "Kafka_Cluster_all");
                List<AuthorizationResult> result = authorizer.authorize(
                                new MockAuthorizableRequestContext.Builder().setPrincipal(principal).build(),
                                List.of(newAction(AclOperation.READ, ResourceType.TOPIC, "any-topic-name")));

                assertEquals(List.of(AuthorizationResult.DENIED), result);
        }
}
