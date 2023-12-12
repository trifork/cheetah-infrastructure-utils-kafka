package com.trifork.cheetah.TopicAuthorization;

import org.apache.kafka.common.acl.AclOperation;

public record TopicAccess(String pattern, AclOperation operation) {
    public TopicAccess(String pattern, String operation) {
        this(pattern, AclOperation.valueOf(operation.replace("-", "_").toUpperCase()));
    }
}
