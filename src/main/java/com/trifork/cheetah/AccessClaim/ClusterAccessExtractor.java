package com.trifork.cheetah.AccessClaim;

import java.util.ArrayList;
import java.util.List;

import com.trifork.cheetah.AccessClaim.AccessUtil.AccessClaim;
import com.trifork.cheetah.ClusterAuthorization.ClusterAccess;

public class ClusterAccessExtractor implements AccessExtractor<ClusterAccess> {
    @Override
    public List<ClusterAccess> extractAccesses(List<String> accesses, String prefix) {
        List<ClusterAccess> result = new ArrayList<>();
        for (AccessClaim extracted : AccessUtil.extractAccesses(accesses, prefix)) {
            String operation = extracted.operation();
            result.add(new ClusterAccess(operation));
        }
        return result;
    }
}