package org.rafendb.recovery.pitr;

import java.time.Duration;

/** Temporary server/QP admission coordination required by PITR. */
public interface PitrAdmission {
    void pauseAdmission();

    boolean awaitNoActiveSessions(Duration timeout);

    void resumeAdmission();
}
