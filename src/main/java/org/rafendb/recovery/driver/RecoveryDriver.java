package org.rafendb.recovery.driver;

import java.util.List;
import java.util.Scanner;
import org.rafendb.engine.Version;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;

/**
 * Interactive driver for the Failure Recovery Manager (FRM) component.
 *
 * <p>Menyediakan antarmuka Command Line Interface (CLI) interaktif dan eksekusi berbasis argumen
 * untuk verifikasi modul recovery, inspeksi WAL log viewer (FR-03), simulasi crash recovery (FR-07),
 * serta rangkaian automated integration test suite (FR-08 & FR-09).
 */
public final class RecoveryDriver {

    private RecoveryDriver() {}

    /**
     * Entry point utama untuk Failure Recovery Manager Driver.
     *
     * @param args argumen baris perintah (--test, --view, --tx, --crash, --help)
     */
    public static void main(String[] args) {
        System.out.println(Version.banner("Failure Recovery Manager Driver"));

        if (args != null && args.length > 0) {
            handleArgs(args);
            return;
        }

        // Mode interaktif jika input tersedia, atau jalankan test suite otomatis jika
        // non-interaktif
        runInteractiveOrTest();
    }

    /**
     * Menampilkan informasi bantuan penggunaan argumen CLI driver.
     */
    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  RecoveryDriver --test              Run all integration tests");
        System.out.println(
                "  RecoveryDriver --view [logFile]    Inspect WAL log records in LSN order");
        System.out.println(
                "  RecoveryDriver --tx <txId> [file]  Filter WAL log records by transaction ID");
        System.out.println(
                "  RecoveryDriver --crash             Simulate crash and restart recovery demo");
        System.out.println("  RecoveryDriver --help              Show this help message");
    }

    /**
     * Menangani argumen baris perintah yang diberikan pengguna.
     *
     * @param args argumen baris perintah
     */
    private static void handleArgs(String[] args) {
        String command = args[0].toLowerCase();
        switch (command) {
            case "--test", "-t" -> {
                FRManagerDriver driver = new FRManagerDriver();
                driver.runAllTests();
            }
            case "--view", "-v" -> {
                String logFile = args.length > 1 ? args[1] : "";
                viewLogs(logFile, null);
            }
            case "--tx" -> {
                if (args.length < 2) {
                    System.err.println(
                            "Error: Missing transaction ID. Usage: --tx <txId> [logFile]");
                    return;
                }
                try {
                    int txId = Integer.parseInt(args[1]);
                    String logFile = args.length > 2 ? args[2] : "";
                    viewLogs(logFile, txId);
                } catch (NumberFormatException e) {
                    System.err.println("Error: Invalid transaction ID: " + args[1]);
                }
            }
            case "--crash", "-c" -> {
                System.out.println("Simulating system crash and startup recovery...");
                FRManagerDriver driver = new FRManagerDriver();
                boolean passed = driver.testRecovery();
                System.out.println("Crash Recovery Simulation: " + (passed ? "PASSED" : "FAILED"));
            }
            case "--help", "-h", "help" -> printHelp();
            default -> {
                System.out.println("Unknown option: " + command);
                printHelp();
            }
        }
    }

    /**
     * Menampilkan record WAL dari file atau memori dengan opsi filter transaksi.
     *
     * @param logFile path file log WAL, atau kosong untuk memori
     * @param txFilter ID transaksi untuk filtering, atau null untuk seluruh log
     */
    private static void viewLogs(String logFile, Integer txFilter) {
        try (Logger logger =
                (logFile == null || logFile.isEmpty()) ? new Logger() : new Logger(logFile)) {
            List<WALRecord> logs =
                    txFilter == null
                            ? logger.getLogsSorted()
                            : logger.getLogsByTransaction(txFilter);

            System.out.println("=== WAL RECORD VIEWER ===");
            System.out.println(
                    "Log Source: "
                            + (logFile == null || logFile.isEmpty() ? "[in-memory]" : logFile));
            if (txFilter != null) {
                System.out.println("Filtered for Transaction: " + txFilter);
            }
            System.out.println("Total Records: " + logs.size());
            System.out.println("----------------------------------------------");
            for (WALRecord record : logs) {
                record.printWALRecord();
            }
            System.out.println("==============================================");
        } catch (Exception e) {
            System.err.println("Failed to read log records: " + e.getMessage());
        }
    }

    /**
     * Menjalankan antarmuka teks interaktif jika console tersedia, atau beralih ke uji otomatis.
     */
    private static void runInteractiveOrTest() {
        if (System.console() == null) {
            FRManagerDriver driver = new FRManagerDriver();
            driver.runAllTests();
            return;
        }

        try (Scanner scanner = new Scanner(System.in)) {
            FRManagerDriver driver = new FRManagerDriver();
            boolean running = true;
            while (running) {
                System.out.println();
                System.out.println("===== FAILURE RECOVERY MANAGER CLI =====");
                System.out.println("1. Run Automated Test Suite (FR-08 & FR-09)");
                System.out.println("2. View Current WAL Logs (LSN Order)");
                System.out.println("3. Filter WAL Logs by Transaction ID");
                System.out.println("4. Run Crash Recovery Simulation Demo (FR-07)");
                System.out.println("5. Exit");
                System.out.print("Select an option (1-5): ");

                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();
                switch (line) {
                    case "1" -> driver.runAllTests();
                    case "2" -> {
                        if (driver.getFRManager() != null) {
                            driver.getFRManager().getLogger().displayLogs();
                        } else {
                            System.out.println("No active logger available.");
                        }
                    }
                    case "3" -> {
                        System.out.print("Enter transaction ID: ");
                        if (scanner.hasNextLine()) {
                            String txInput = scanner.nextLine().trim();
                            try {
                                int txId = Integer.parseInt(txInput);
                                if (driver.getFRManager() != null) {
                                    driver.getFRManager()
                                            .getLogger()
                                            .displayLogsByTransaction(txId);
                                }
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid transaction ID format.");
                            }
                        }
                    }
                    case "4" -> {
                        boolean ok = driver.testRecovery();
                        System.out.println(
                                "Crash Recovery Demo Result: " + (ok ? "PASSED" : "FAILED"));
                    }
                    case "5", "exit", "quit" -> {
                        System.out.println("Exiting Recovery Driver. Goodbye!");
                        running = false;
                    }
                    default -> System.out.println("Invalid selection. Please choose 1-5.");
                }
            }
        }
    }
}
