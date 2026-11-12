package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.StudentResponseRecord;

/**
 * Port receiving student answer selections during an active exam session.
 * Host applications may supply a custom sink or rely on the default JDBC response sink.
 */
@FunctionalInterface
public interface ResponseSink {
    void accept(StudentResponseRecord response);
}
