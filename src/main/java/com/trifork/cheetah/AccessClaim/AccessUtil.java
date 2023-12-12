package com.trifork.cheetah.AccessClaim;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccessUtil {
    private static final Logger LOG = LoggerFactory.getLogger(AccessUtil.class);

    public static List<AccessClaim> extractAccesses(List<String> accesses, String prefix) {
        List<AccessClaim> result = new ArrayList<>();
        for (String access : accesses) {
            try {
                if (!access.startsWith(prefix)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s does not have the correct prefix. Skipping...", access));
                    }
                    continue;
                }
                access = access.substring(prefix.length());

                int splitIndex = access.lastIndexOf('_');

                if (splitIndex == -1) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info(String.format(
                                "%s does not follow the correct pattern for access",
                                access));
                    }
                    continue;
                }

                String pattern = access.substring(0, splitIndex);
                String operation = access.substring(splitIndex + 1);
                result.add(new AccessClaim(pattern, operation));
            } catch (Exception e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn(String.format("Error processing access: %s %n %s", access, e));
                }
            }
        }
        return result;
    }

    public record AccessClaim(String pattern, String operation) {
    }
}