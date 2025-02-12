package com.trifork.cheetah;

import org.apache.kafka.common.ClusterResource;
import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.server.authorizer.AuthorizerServerInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class AuthorizerTestServerInfo implements AuthorizerServerInfo {
    private final Collection<Endpoint> endpoints;

    public AuthorizerTestServerInfo(Collection<Endpoint> endpoints) {
        assertFalse(endpoints.isEmpty());
        this.endpoints = endpoints;
    }

    @Override
    public ClusterResource clusterResource() {
        return new ClusterResource(Uuid.fromString("r7mqHQrxTNmzbKvCvWZzLQ").toString());
    }

    @Override
    public int brokerId() {
        return 0;
    }

    @Override
    public Collection<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public Endpoint interBrokerEndpoint() {
        return endpoints.iterator().next();
    }

    @Override
    public Collection<String> earlyStartListeners() {
        List<String> result = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (endpoint.listenerName().get().equals("CONTROLLER")) {
                result.add(endpoint.listenerName().get());
            }
        }
        return result;
    }
}
