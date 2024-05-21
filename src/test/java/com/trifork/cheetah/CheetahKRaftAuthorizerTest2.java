package com.trifork.cheetah;

import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizableRequestContext;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.JSONUtil;
import io.strimzi.kafka.oauth.server.OAuthKafkaPrincipal;

class CheetahKRaftAuthorizerTest2 {
	@TestFactory
	Stream<DynamicTest> tabletests() {
		record Input(String principalName, String allowedRole, AclOperation requestedOperation,
				ResourceType requestedResourceType, String requestedResourceName) {
		}

		record Expected(List<AuthorizationResult> result) {
		}

		record TestCase(String testName, Input in, Expected expected) {

			public void run() throws Exception {
				// Construct the JWT
				OAuthKafkaPrincipal principal = createPrincipal(in.principalName, List.of(in.allowedRole));
				// Construct the request context
				AuthorizableRequestContext requestContext = new MockAuthorizableRequestContext.Builder()
						.setPrincipal(principal).build();
				// Construct the actions to be authorized
				List<Action> requestedActions = List
						.of(newAction(in.requestedOperation, in.requestedResourceType, in.requestedResourceName));
				// Initialize the authorizer
				CheetahKRaftAuthorizer authorizer = createAndInitializeStandardAuthorizer();

				// Run the authorization
				List<AuthorizationResult> result = authorizer.authorize(requestContext, requestedActions);

				// Compare results
				assertEquals(expected.result, result);
			}
		}

		var testCases = Stream.of(
				new TestCase(
						"Allow topic with exact name",
						new Input("bob", "Kafka_mytopic_read", AclOperation.READ,
								ResourceType.TOPIC, "mytopic"),
						new Expected(List.of(AuthorizationResult.ALLOWED))),
				new TestCase(
						"Deny different topic",
						new Input("bob", "Kafka_no-permission-to-read-this_read", AclOperation.READ,
								ResourceType.TOPIC, "mytopic"),
						new Expected(List.of(AuthorizationResult.DENIED))),
				new TestCase(
						"Deny different resource type",
						new Input("bob", "Kafka_mytopic_read", AclOperation.READ,
								ResourceType.CLUSTER, "mytopic"),
						new Expected(List.of(AuthorizationResult.DENIED))),
				new TestCase(
						"Deny different operation",
						new Input("bob", "Kafka_mytopic_read", AclOperation.WRITE,
								ResourceType.TOPIC, "mytopic"),
						new Expected(List.of(AuthorizationResult.DENIED))),
				new TestCase(
						"Allow topic wildcard",
						new Input("bob", "Kafka_*_read", AclOperation.READ,
								ResourceType.TOPIC, "mytopic"),
						new Expected(List.of(AuthorizationResult.ALLOWED))),
				new TestCase(
						"Deny wildcard for a different operation",
						new Input("bob", "Kafka_*_read", AclOperation.WRITE,
								ResourceType.TOPIC, "mytopic"),
						new Expected(List.of(AuthorizationResult.DENIED))),
				new TestCase(
						"Allow cluster grant all",
						new Input("bob", "Kafka_Cluster_all", AclOperation.READ,
								ResourceType.CLUSTER, "doesntmatter"),
						new Expected(List.of(AuthorizationResult.ALLOWED))),
				new TestCase(
						"Allow cluster specific operation",
						new Input("bob", "Kafka_Cluster_alter", AclOperation.ALTER,
								ResourceType.CLUSTER, "doesntmatter"),
						new Expected(List.of(AuthorizationResult.ALLOWED))),
				new TestCase(
						"Cluster all doesn't allow topic operations",
						new Input("bob", "Kafka_Cluster_all", AclOperation.READ,
								ResourceType.TOPIC, "any-topic-name"),
						new Expected(List.of(AuthorizationResult.DENIED))),
				new TestCase(
						"Allow topic with prefix",
						new Input("bob", "Kafka_myprefix*_read", AclOperation.READ,
								ResourceType.TOPIC, "myprefix-and-whatever"),
						new Expected(List.of(AuthorizationResult.ALLOWED))));

		return DynamicTest.stream(testCases, TestCase::testName, TestCase::run);
	}

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
		authorizer.configure(Map.of(CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_NAME, "roles",
				CheetahConfig.CHEETAH_AUTHORIZATION_CLAIM_IS_LIST, "true",
				CheetahConfig.CHEETAH_AUTHORIZATION_PREFIX, "Kafka",
				CheetahConfig.CHEETAH_AUTHORIZATION_SUPER_USERS, "User:doesntmatter"));
		authorizer.start(new AuthorizerTestServerInfo(Collections.singletonList(PLAINTEXT)));
		authorizer.completeInitialLoad();
		return authorizer;
	}

	static OAuthKafkaPrincipal createPrincipal(String name, List<String> roles) {
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
}
