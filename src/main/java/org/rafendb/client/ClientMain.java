package org.rafendb.client;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;
import org.rafendb.engine.Version;
import org.rafendb.net.Protocol;

/**
 * Placeholder for client entry point.
 *
 * <p>TODO: implementasi
 */
public final class ClientMain {
    private ClientMain() {}

    private static boolean quit(String msg) {
        return msg.equals("q") || msg.equals("quit") || msg.equals("exit");
    }

    public static void main(String[] args) {
        try {
            System.out.println(Version.banner("Client"));

            System.out.println("Starting a new client...");
            Socket publisher = new Socket("127.0.0.1", 3140);
            System.out.println("Connection established, welcome to RafenDB!");

            DataOutputStream output = new DataOutputStream(publisher.getOutputStream());

            System.out.print("RafenDB # ");

            Scanner stdin = new Scanner(System.in);
            String input = stdin.nextLine();

            while (input != null && !quit(input)) {
                Protocol.writeFrame(output, input);
                System.out.print("RafenDB # ");
                input = stdin.nextLine();
            }

            stdin.close();
            publisher.close();

            System.out.println("Connection closed. ");
        } catch (IOException e) {
            System.err.println("Error encountered: " + e.getMessage());
        }
    }
}
