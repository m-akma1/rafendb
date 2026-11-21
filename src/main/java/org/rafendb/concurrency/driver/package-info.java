/**
 * The Concurrency Control Manager driver (CC-10). {@link ConcurrencyDriver} runs interactive or scripted schedules against CCM in a temporary database, over the real FRM and SM ({@code ScenarioStorage}) or over in-memory mocks of them ({@code MockScenarioStorage}). {@code Scenario} records the accesses in the order they actually run.
 *
 * <p>Owner: G2 Concurrency Control Manager (CC5).
 */
package org.rafendb.concurrency.driver;
