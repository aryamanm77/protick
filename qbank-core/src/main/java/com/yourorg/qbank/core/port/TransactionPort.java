package com.yourorg.qbank.core.port;

import java.util.function.Supplier;

public interface TransactionPort {
    <T> T inTransaction(Supplier<T> work);

    default void runInTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
