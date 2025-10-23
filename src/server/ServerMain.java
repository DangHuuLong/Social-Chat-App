package server;

import server.dao.DBConnection;
import server.dao.MessageDao;
import server.dao.FileDao;
import server.dao.GroupDao;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.*;

public class ServerMain {
    private static final int PORT = 5000;

    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientHandler> online = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();


    private Connection conn;
    private MessageDao messageDao;
    private FileDao filedao;
    private GroupDao groupdao;
    public static void main(String[] args) {
        new ServerMain().start();
    }

    public void start() {
        try {
       
            conn = DBConnection.get();              
            messageDao = new MessageDao(conn);
            filedao = new FileDao(conn);
            groupdao = new GroupDao(conn);
            try (ServerSocket ss = new ServerSocket(PORT)) {
                System.out.println("✅ Server started at port " + PORT);
                while (true) {
                    Socket s = ss.accept();
                    System.out.println("➡ Client connected: " + s.getRemoteSocketAddress());

                    ClientHandler handler = new ClientHandler(s, clients, online, messageDao, filedao, groupdao);;
                    clients.add(handler);
                    pool.submit(handler);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        try {
            for (ClientHandler h : clients) h.close();
        } catch (Exception ignored) {}
        pool.shutdownNow();

        try { if (conn != null && !conn.isClosed()) conn.close(); } catch (Exception ignored) {}

        System.out.println("🛑 Server shutdown.");
    }
}