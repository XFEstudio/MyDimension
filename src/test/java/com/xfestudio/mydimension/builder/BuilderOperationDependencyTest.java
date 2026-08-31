package com.xfestudio.mydimension.builder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderOperationDependencyTest {
    @Test
    void supportPlacedLaterInTheBatchUnblocksDependentOnSecondPass() {
        AtomicBoolean supportPlaced = new AtomicBoolean();
        AtomicInteger dependentAttempts = new AtomicInteger();

        List<String> unresolved = BuilderOperationManager.processWithSingleRetry(
                List.of("dependent", "support"), value -> {
                    if (value.equals("support")) {
                        supportPlaced.set(true);
                        return true;
                    }
                    dependentAttempts.incrementAndGet();
                    return supportPlaced.get();
                });

        assertTrue(unresolved.isEmpty());
        assertTrue(supportPlaced.get());
        assertEquals(2, dependentAttempts.get());
    }

    @Test
    void unresolvedDependencyIsRetriedOnlyOnce() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> unresolved = BuilderOperationManager.processWithSingleRetry(
                List.of("unsupported"), ignored -> {
                    attempts.incrementAndGet();
                    return false;
                });

        assertEquals(List.of("unsupported"), unresolved);
        assertEquals(2, attempts.get());
    }
}
