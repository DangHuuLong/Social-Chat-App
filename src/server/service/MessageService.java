package server.service;

import common.FileResource;
import server.dao.MessageDao;
import server.dao.FileDao;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

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
        boolean oldAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String sender = messageDao.getSenderById(messageId);
            if (sender == null || !sender.equals(requester)) {
                conn.rollback();
                return false;
            }

            List<FileResource> files = fileDao.listByMessageId(messageId);

            for (FileResource fr : files) {
                try {
                    String path = fr.getFilePath();
                    if (path != null && !path.isBlank()) {
                        File file = new File(path);
                        if (!file.isAbsolute()) {
                            file = new File("Uploads", path);
                        }
                        if (file.exists()) {
                            file.delete();
                        }
                    }
                } catch (Exception ignore) {
                }
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
            try { conn.setAutoCommit(oldAutoCommit); } catch (Exception ignore) {}
        }
    }
}
