package org.rafendb.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import org.rafendb.engine.Version;

/**
 * Placeholder for server entry point.
 *
 * <p>TODO: implementasi
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        try (ServerSocket mainPort = new ServerSocket(3140)) {
            System.out.println("Starting server port at 3140. Waiting for clients...");
            Socket listener = mainPort.accept();
            System.out.println("Connected to client!");

            InputStream input = listener.getInputStream();
            byte[] buffer = new byte[32];
            int byteRead;

            while ((byteRead = input.read(buffer)) != -1) {
                System.out.println("Receiving: " + new String(buffer, 0, byteRead));
            }

            listener.close();
            System.out.println("Port closed. ");
        } catch (IOException e) {
            System.err.println("Error encountered: " + e.getMessage());
        }

        System.out.println(Version.banner("Server"));
        System.out.println("Not implemented yet.");
    }
}
