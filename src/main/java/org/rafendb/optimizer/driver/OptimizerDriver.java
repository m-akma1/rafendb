package org.rafendb.optimizer.driver;

import java.util.Scanner;
import org.rafendb.engine.Version;

public final class OptimizerDriver {
    private OptimizerDriver() {}

    public static void main(String[] args) {
        System.out.println(Version.banner("Query Optimizer Driver"));
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("rafendb-qo> ");
            if (!scanner.hasNextLine()) break;
            String sql = scanner.nextLine().trim();
            if (sql.equalsIgnoreCase("exit") || sql.equalsIgnoreCase("quit")) break;
            if (sql.isEmpty()) continue;

            try {
                System.out.println("Processing: " + sql);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }
}
