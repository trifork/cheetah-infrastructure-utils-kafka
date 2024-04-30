package com.trifork.cheetah;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.kafka.oauth.common.BearerTokenWithPayload;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MockBearerTokenWithPayload implements BearerTokenWithPayload {


    private final String principalName;
    private final Set<String> groups;
    private final long createTime;
    private final long lifetime;
    private final Set<String> scopes;
    private final String token;
    private final ObjectNode claims;
    private JsonNode payload;

    MockBearerTokenWithPayload(String principalName, Set<String> groups, long createTime, long lifetime, String scope, String token, JsonNode payload, ObjectNode claims) {
        this.principalName = principalName;
        this.groups = groups;
        this.createTime = createTime;
        this.lifetime = lifetime;

        Set<String> scopesSet = new HashSet<>();
        String[] parsedScopes = scope != null ? scope.split(" ") : new String[0];
        Collections.addAll(scopesSet, parsedScopes);
        scopes = Collections.unmodifiableSet(scopesSet);

        this.token = token;
        this.payload = payload;
        this.claims = claims;
    }

    @Override
    public JsonNode getPayload() {
        return payload;
    }

    @Override
    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    @Override
    public Set<String> getGroups() {
        return groups;
    }

    @Override
    public ObjectNode getClaimsJSON() {
        return claims;
    }

    @Override
    public String value() {
        return token;
    }

    @Override
    public Set<String> scope() {
        return scopes;
    }

    @Override
    public long lifetimeMs() {
        return lifetime;
    }

    @Override
    public String principalName() {
        return principalName;
    }

    @Override
    public Long startTimeMs() {
        return createTime;
    }
} 
