package com.trifork.cheetah;

import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimAclTranslatorTest {

    private static final KafkaPrincipal PRINCIPAL = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, "svc");

    /** ACLs as "TYPE:PATTERN_TYPE:name:OPERATION"; principal User:svc, host * and ALLOW are asserted separately. */
    private static Set<String> translate(String prefix, List<String> roles) {
        Set<StandardAcl> acls = new ClaimAclTranslator(prefix).toAcls(PRINCIPAL, roles);
        acls.forEach(acl -> {
            assertEquals("User:svc", acl.principal());
            assertEquals("*", acl.host());
            assertEquals("ALLOW", acl.permissionType().name());
        });
        return acls.stream()
                .map(a -> a.resourceType() + ":" + a.patternType() + ":" + a.resourceName() + ":" + a.operation())
                .collect(Collectors.toSet());
    }

    static Stream<Arguments> roles() {
        return Stream.of(
                Arguments.of(List.of("Kafka_orders_write"), Set.of("TOPIC:LITERAL:orders:WRITE")),
                Arguments.of(List.of("Kafka_orders_read"),
                        Set.of("TOPIC:LITERAL:orders:READ", "GROUP:LITERAL:*:READ")),
                Arguments.of(List.of("Kafka_orders_describe"),
                        Set.of("TOPIC:LITERAL:orders:DESCRIBE", "GROUP:LITERAL:*:DESCRIBE")),
                Arguments.of(List.of("Kafka_orders_delete"),
                        Set.of("TOPIC:LITERAL:orders:DELETE", "GROUP:LITERAL:*:DELETE")),
                Arguments.of(List.of("Kafka_orders_all"),
                        Set.of("TOPIC:LITERAL:orders:ALL", "GROUP:LITERAL:*:READ", "GROUP:LITERAL:*:DELETE")),
                Arguments.of(List.of("Kafka_orders_any"),
                        Set.of("TOPIC:LITERAL:orders:ALL", "GROUP:LITERAL:*:READ", "GROUP:LITERAL:*:DELETE")),
                Arguments.of(List.of("Kafka_orders*_Write"), Set.of("TOPIC:PREFIXED:orders:WRITE")),
                Arguments.of(List.of("Kafka_*_write"), Set.of("TOPIC:LITERAL:*:WRITE")),
                Arguments.of(List.of("Kafka_Demo1_Topic_describe-configs"),
                        Set.of("TOPIC:LITERAL:Demo1_Topic:DESCRIBE_CONFIGS")),
                Arguments.of(List.of("Kafka_Cluster_create"), Set.of("CLUSTER:LITERAL:kafka-cluster:CREATE")),
                Arguments.of(List.of("Kafka_Cluster_describe-configs"),
                        Set.of("CLUSTER:LITERAL:kafka-cluster:DESCRIBE_CONFIGS")),
                Arguments.of(List.of("Kafka_Cluster_IDEMPOTENT_WRITE"),
                        Set.of("CLUSTER:LITERAL:kafka-cluster:IDEMPOTENT_WRITE")),
                Arguments.of(List.of("Kafka_cluster_alter"), Set.of("CLUSTER:LITERAL:kafka-cluster:ALTER")),
                // Skipped: no prefix, malformed, unknown or invalid operation, unsupported wildcard position.
                Arguments.of(List.of("orders_write", "offline_access", "default-roles-x"), Set.of()),
                Arguments.of(List.of("Kafka_orders", "Kafka__write"), Set.of()),
                Arguments.of(List.of("Kafka_orders_publish", "Kafka_orders_idempotent-write"), Set.of()),
                Arguments.of(List.of("Kafka_Cluster_read"), Set.of()),
                Arguments.of(List.of("Kafka_*orders_read", "Kafka_or*ders_read"), Set.of()),
                // Duplicates collapse.
                Arguments.of(List.of("Kafka_orders_write", "Kafka_orders_write"), Set.of("TOPIC:LITERAL:orders:WRITE"))
        );
    }

    @ParameterizedTest
    @MethodSource("roles")
    void translatesRoles(List<String> roles, Set<String> expected) {
        assertEquals(expected, translate("Kafka_", roles));
    }

    @Test
    void prefixWithoutTrailingUnderscoreIsEquivalent() {
        List<String> roles = List.of("Kafka_orders_write", "Kafka_Cluster_create");
        assertEquals(translate("Kafka_", roles), translate("Kafka", roles));
    }

    @Test
    void emptyPrefixConsidersEveryRole() {
        assertEquals(Set.of("TOPIC:LITERAL:*:ALL", "GROUP:LITERAL:*:READ", "GROUP:LITERAL:*:DELETE"),
                translate("", List.of("*_all")));
    }

    @Test
    void clusterRolesAreRecognisedWithTheConfiguredPrefix() {
        // The earlier implementation looked for prefix + "_Cluster" ("Kafka__Cluster"), so this became a topic role.
        assertEquals(Set.of("CLUSTER:LITERAL:kafka-cluster:CREATE"), translate("Kafka_", List.of("Kafka_Cluster_create")));
    }
}
