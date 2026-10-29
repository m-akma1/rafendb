package org.rafendb.engine;

/**
 *
 * Engine. Main entry setiap execution.
 * TODO: Deskripsi
 *
 */
public final class Engine {
    private static Engine instance = null;

    private Engine() {}

    public static synchronized Engine getEngine() {
        if (instance == null) instance = new Engine();
        return instance;
    }

    public synchronized void execute(String sql) {
        System.out.print("Executing \"" + sql + "\" ");

        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(500);
                System.out.print(".");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println(" Line executed!");
    }
}
