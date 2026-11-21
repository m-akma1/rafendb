/**
 * The Query Processor driver (QP-14). {@link org.rafendb.processor.driver.ProcessorDriver} runs SQL
 * end to end on the engine, with the Storage Manager, Concurrency Control Manager or Failure
 * Recovery Manager mocked on request, and checks the results without the client.
 *
 * <p>Checks are written as scenario files ({@code .test}, read by {@link
 * org.rafendb.processor.driver.ScenarioFile}) in the sqllogictest format: each statement or query
 * is followed by what it must give, and every record prints PASS or FAIL. The scenarios live in
 * {@code test-scenarios/processor/}; {@code all.txt} runs them all.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.processor.driver;
