package org.rafendb.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.rafendb.engine.Version;

/**
 * Placeholder for client entry point.
 *
 * <p>TODO: implementasi
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        try {
            System.out.println("Starting a new client!");
            Socket publisher = new Socket("127.0.0.1", 3140);
            System.out.println("Connection established, write messages below!");

            OutputStream output = publisher.getOutputStream();

            Scanner stdin = new Scanner(System.in);
            String input = "A message from client.";

            do {
                byte[] buffer = input.getBytes(StandardCharsets.UTF_8);
                output.write(buffer);

                input = stdin.nextLine();
            } while (input != null && !input.equals("q"));

            stdin.close();
            publisher.close();

            System.out.println("Connection closed. ");
        } catch (IOException e) {
            System.err.println("Error encountered: " + e.getMessage());
        }

        System.out.println(Version.banner("Client"));
        System.out.println("Not implemented yet.");
    }
}
