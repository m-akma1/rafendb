/**
 * Base class for component drivers. Each component's driver extends {@link
 * org.rafendb.driver.Driver}, registers its commands in {@code setup()}, and inherits the common
 * parts: {@code --mock}, {@code --run} and {@code --help} flags, a prompt, running files of
 * commands, PASS/FAIL checks with expected and actual values, error printing and the exit code.
 * {@link org.rafendb.driver.DriverException} reports a driver user's own mistakes.
 *
 * <p>Owner: QP1, QO1, SM1, CCM5, FRM1.
 */
package org.rafendb.driver;
