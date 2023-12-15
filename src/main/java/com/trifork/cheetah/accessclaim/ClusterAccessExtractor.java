package com.trifork.cheetah.accessclaim;

import java.util.ArrayList;
import java.util.List;

import com.trifork.cheetah.accessclaim.AccessUtil.AccessClaim;
import com.trifork.cheetah.clusterauthorization.ClusterAccess;

public class ClusterAccessExtractor implements AccessExtractor<ClusterAccess> {
    @Override
    public List<ClusterAccess> extractAccesses(List<String> accesses, String prefix) {
        final List<ClusterAccess> result = new ArrayList<>();
        for (AccessClaim extracted : AccessUtil.extractAccesses(accesses, prefix)) {
            String operation = extracted.operation();
            result.add(new ClusterAccess(operation));
        }
        return result;
    }
}