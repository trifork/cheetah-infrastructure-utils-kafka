package com.trifork.cheetah.AccessClaim;

import java.util.*;

public interface AccessExtractor<T> {
    List<T> extractAccesses(List<String> accesses, String prefix);
}