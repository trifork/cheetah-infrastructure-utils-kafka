package com.trifork.cheetah;

import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.metadata.authorizer.StandardAcl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Translates JWT roles into Kafka ACLs for the role's own principal.
 *
 * <p>Role format, after the configured prefix:
 * <ul>
 *   <li>{@code <topic>_<operation>}: topic ACL. {@code *} alone is Kafka's wildcard, a trailing {@code *} is a
 *   prefix pattern. Topic names may contain underscores; the operation is the part after the last one.</li>
 *   <li>{@code Cluster_<operation>}: cluster ACL. {@code Cluster} is case-insensitive, so a topic whose name
 *   starts with {@code Cluster_} cannot be addressed.</li>
 * </ul>
 * Operations are case-insensitive and may use {@code -} instead of {@code _}, e.g. {@code describe-configs}.
 *
 * <p>To keep the access consumers have today, topic roles also grant consumer-group access on every group:
 * {@code read} and {@code describe} grant the same on groups, {@code delete} grants group DELETE, and
 * {@code all} grants group READ and DELETE.
 */
final class ClaimAclTranslator {
    static final String CLUSTER_RESOURCE_NAME = "kafka-cluster";
    private static final String CLUSTER_ROLE = "Cluster_";
    private static final String ANY_HOST = "*";
    private static final Logger LOG = LoggerFactory.getLogger(ClaimAclTranslator.class);

    private static final Set<AclOperation> TOPIC_OPERATIONS = EnumSet.of(
            AclOperation.READ, AclOperation.WRITE, AclOperation.CREATE, AclOperation.DELETE, AclOperation.ALTER,
            AclOperation.DESCRIBE, AclOperation.DESCRIBE_CONFIGS, AclOperation.ALTER_CONFIGS, AclOperation.ALL);
    private static final Set<AclOperation> CLUSTER_OPERATIONS = EnumSet.of(
            AclOperation.CREATE, AclOperation.ALTER, AclOperation.DESCRIBE, AclOperation.CLUSTER_ACTION,
            AclOperation.DESCRIBE_CONFIGS, AclOperation.ALTER_CONFIGS, AclOperation.IDEMPOTENT_WRITE, AclOperation.ALL);

    private final String prefix;

    /**
     * @param configuredPrefix role prefix, with or without the trailing underscore ({@code Kafka} and
     *                         {@code Kafka_} are equivalent). Empty means every role is considered.
     */
    ClaimAclTranslator(String configuredPrefix) {
        String p = configuredPrefix == null ? "" : configuredPrefix;
        this.prefix = p.isEmpty() || p.endsWith("_") ? p : p + "_";
    }

    Set<StandardAcl> toAcls(KafkaPrincipal principal, Collection<String> roles) {
        String aclPrincipal = principal.getPrincipalType() + ":" + principal.getName();
        Set<StandardAcl> acls = new HashSet<>();
        for (String role : roles) {
            if (role == null || !role.startsWith(prefix)) {
                LOG.debug("Role {} does not have the prefix {}. Skipping", role, prefix);
                continue;
            }
            String rest = role.substring(prefix.length());
            if (rest.regionMatches(true, 0, CLUSTER_ROLE, 0, CLUSTER_ROLE.length())) {
                addClusterAcl(acls, aclPrincipal, role, rest.substring(CLUSTER_ROLE.length()));
            } else {
                addTopicAcls(acls, aclPrincipal, role, rest);
            }
        }
        return acls;
    }

    private static void addClusterAcl(Set<StandardAcl> acls, String principal, String role, String operationText) {
        AclOperation operation = parseOperation(operationText, CLUSTER_OPERATIONS, role);
        if (operation != null) {
            acls.add(allow(principal, ResourceType.CLUSTER, CLUSTER_RESOURCE_NAME, PatternType.LITERAL, operation));
        }
    }

    private static void addTopicAcls(Set<StandardAcl> acls, String principal, String role, String rest) {
        int split = rest.lastIndexOf('_');
        if (split <= 0) {
            LOG.info("Role {} does not follow the pattern <prefix><topic>_<operation>. Skipping", role);
            return;
        }
        String pattern = rest.substring(0, split);
        AclOperation operation = parseOperation(rest.substring(split + 1), TOPIC_OPERATIONS, role);
        if (operation == null) {
            return;
        }

        int wildcard = pattern.indexOf('*');
        if (pattern.equals(ResourcePattern.WILDCARD_RESOURCE) || wildcard < 0) {
            acls.add(allow(principal, ResourceType.TOPIC, pattern, PatternType.LITERAL, operation));
        } else if (wildcard == pattern.length() - 1) {
            acls.add(allow(principal, ResourceType.TOPIC, pattern.substring(0, wildcard), PatternType.PREFIXED, operation));
        } else {
            LOG.info("Role {}: Kafka only supports '*' alone or at the end of a topic pattern. Skipping", role);
            return;
        }

        for (AclOperation groupOperation : groupOperationsFor(operation)) {
            acls.add(allow(principal, ResourceType.GROUP, ResourcePattern.WILDCARD_RESOURCE, PatternType.LITERAL, groupOperation));
        }
    }

    private static List<AclOperation> groupOperationsFor(AclOperation topicOperation) {
        switch (topicOperation) {
            case READ:
                return List.of(AclOperation.READ);
            case DESCRIBE:
                return List.of(AclOperation.DESCRIBE);
            case DELETE:
                return List.of(AclOperation.DELETE);
            case ALL:
                return List.of(AclOperation.READ, AclOperation.DELETE);
            default:
                return List.of();
        }
    }

    private static AclOperation parseOperation(String text, Set<AclOperation> valid, String role) {
        AclOperation operation;
        try {
            operation = AclOperation.valueOf(text.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.info("Role {} has an unknown operation {}. Skipping", role, text);
            return null;
        }
        // Earlier versions treated ANY like ALL. In an ACL, ANY is only a filter value and would never match.
        if (operation == AclOperation.ANY) {
            operation = AclOperation.ALL;
        }
        if (!valid.contains(operation)) {
            LOG.info("Role {}: operation {} is not valid for this resource type. Skipping", role, operation);
            return null;
        }
        return operation;
    }

    private static StandardAcl allow(String principal, ResourceType type, String name, PatternType patternType,
                                     AclOperation operation) {
        return new StandardAcl(type, name, patternType, principal, ANY_HOST, operation, AclPermissionType.ALLOW);
    }
}
