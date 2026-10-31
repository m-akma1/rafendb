package org.rafendb.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.processor.Session;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.engine.Engine;
import org.rafendb.engine.Version;
import org.rafendb.net.Protocol;

/**
 * Server entry point (QP-02). Listens on port 3140 and serves each client on its own virtual
 * thread with its own {@link Session}. Every request frame gets exactly one reply frame: the
 * rendered result, or an {@code ERROR:} line.
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        Engine engine = Engine.withMocks();
        try (ServerSocket mainPort = new ServerSocket(3140)) {
            System.out.println(Version.banner("Server"));
            System.out.println("Starting server port at 3140. Waiting for clients...");
            while (true) {
                Socket listener = mainPort.accept();
                Thread.startVirtualThread(() -> handleClient(listener, engine.processor()));
            }
        } catch (IOException e) {
            System.err.println("Error encountered: " + e.getMessage());
        }
    }

    private static void handleClient(Socket listener, QueryProcessor processor) {
        System.out.println("Connected to client!");
        try (Socket client = listener;
                DataInputStream input = new DataInputStream(client.getInputStream());
                DataOutputStream output = new DataOutputStream(client.getOutputStream());
                Session session = processor.openSession()) {
            String message;
            while ((message = Protocol.readFrame(input)) != null) {
                Protocol.writeFrame(output, reply(session, message));
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        }
    }

    /** Runs one statement and returns the text to send back; never throws for a bad query. */
    private static String reply(Session session, String sql) {
        try {
            return ResultRenderer.render(session.execute(sql));
        } catch (RafenDBRuntimeException e) {
            return "ERROR: " + e.getMessage();
        } catch (RuntimeException e) {
            System.err.println("Unexpected error while executing \"" + sql + "\":");
            e.printStackTrace();
            return "ERROR: internal error";
        }
    }
}
