package server.service;

import server.dao.MessageDao;
import server.dao.FileDao;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class MessageService {
    private final Connection conn;
    private final MessageDao messageDao;
    private final FileDao fileDao;

    public MessageService(Connection conn) {
        this.conn = conn;
        this.messageDao = new MessageDao(conn);
        this.fileDao    = new FileDao(conn);
    }
    public boolean deleteMessageCascade(long messageId, String requester) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String peer = messageDao.deleteByIdReturningPeer(-1L, requester); 
            String sender = messageDao.getSenderById(messageId);
            if (sender == null || !sender.equals(requester)) {
                conn.rollback();
                return false;
            }

            var files = fileDao.listByMessageId(messageId);

            for (var fr : files) {
                try {
                    if (fr.filePath != null && !fr.filePath.isBlank()) {
                        File f = new File(fr.filePath);
                        if (!f.isAbsolute()) {
                            f = new File("Uploads", fr.filePath);
                        }
                        if (f.exists()) f.delete();
                    }
                } catch (Exception ignore) {}
            }

            fileDao.deleteByMessageId(messageId);
            boolean ok = messageDao.deleteById(messageId, requester);

            if (ok) {
                conn.commit();
                return true;
            } else {
                conn.rollback();
                return false;
            }
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignore) {}
        }
    }
}
