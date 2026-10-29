package org.rafendb.server;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.rafendb.engine.Engine;
import org.rafendb.engine.Version;
import org.rafendb.net.Protocol;

/**
 * Placeholder for server entry point.
 *
 * <p>TODO: implementasi
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        try (ServerSocket mainPort = new ServerSocket(3140)) {
            System.out.println(Version.banner("Server"));
            System.out.println("Starting server port at 3140. Waiting for clients...");
            while (true) {
                Socket listener = mainPort.accept();
                Thread.startVirtualThread(() -> handleClient(listener));
            }
        } catch (IOException e) {
            System.err.println("Error encountered: " + e.getMessage());
        }
    }

    private static void handleClient(Socket listener) {
        System.out.println("Connected to client!");
        try (Socket client = listener;
                DataInputStream input = new DataInputStream(client.getInputStream())) {
            String message;
            while ((message = Protocol.readFrame(input)) != null) {
                Engine.getEngine().execute(message);
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        }
    }
}
