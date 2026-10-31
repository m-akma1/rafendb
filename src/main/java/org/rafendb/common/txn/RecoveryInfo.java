package org.rafendb.common.txn;

/**
 * Successful recovery high-water marks, including relevant discarded/aborted history.
 *
 * @param maxTransactionId largest recorded transaction ID, nonnegative; zero for empty history
 * @param maxTimestamp largest recorded logical begin timestamp, not wall-clock time; nonnegative,
 *     zero for empty history
 */
public record RecoveryInfo(long maxTransactionId, long maxTimestamp) {}
