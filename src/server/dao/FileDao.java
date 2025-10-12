package server.dao;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FileDao {
    private final Connection conn;

    public FileDao(Connection conn) {
        this.conn = conn;
    }
    
    public List<FileRecord> listByMessageId(long messageId) throws SQLException {
        String sql = "SELECT * FROM files WHERE message_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileRecord> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /** Lưu metadata cho mọi loại file (image/video/audio/other) */
    public long save(long messageId, String fileName, String filePath,
                     String mimeType, long fileSize) throws SQLException {
        String sql = """
            INSERT INTO files (message_id, file_name, file_path, mime_type, file_size)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, messageId);
            ps.setString(2, fileName);
            ps.setString(3, filePath);
            ps.setString(4, mimeType);
            ps.setLong(5, fileSize);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    /** Lấy metadata theo message_id (duy nhất 1 file/1 message trong luồng này) */
    public FileRecord getByMessageId(long messageId) throws SQLException {
        String sql = "SELECT * FROM files WHERE message_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** Lấy metadata theo id file (PK) */
    public FileRecord getById(long id) throws SQLException {
        String sql = "SELECT * FROM files WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** Danh sách lịch sử file (mọi loại) của user (phân trang) */
    public List<FileRecord> listByUserPaged(String username, int limit, int offset) throws SQLException {
        String sql = """
            SELECT f.*
            FROM files f
            JOIN messages m ON f.message_id = m.id
            WHERE (m.sender = ? OR m.recipient = ?)
            ORDER BY f.uploaded_at DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, Math.max(1, limit));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<FileRecord> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /** Xoá theo message_id (trong DB) */
    public boolean deleteByMessageId(long messageId) throws SQLException {
        String sql = "DELETE FROM files WHERE message_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Ánh xạ 1 hàng SQL → FileRecord */
    private FileRecord mapRow(ResultSet rs) throws SQLException {
        FileRecord r = new FileRecord();
        r.id         = rs.getLong("id");
        r.messageId  = rs.getLong("message_id");
        r.fileName   = rs.getString("file_name");
        r.filePath   = rs.getString("file_path");
        r.mimeType   = rs.getString("mime_type");
        r.fileSize   = rs.getLong("file_size");
        r.uploadedAt = rs.getTimestamp("uploaded_at");
        return r;
    }

    /** POJO kết quả */
    public static class FileRecord {
        public long id;
        public long messageId;
        public String fileName;
        public String filePath;
        public String mimeType;
        public long fileSize;
        public Timestamp uploadedAt;

        @Override
        public String toString() {
            return String.format(
                "FileRecord{id=%d, msgId=%d, name='%s', size=%dB, path='%s', mime='%s'}",
                id, messageId, fileName, fileSize, filePath, mimeType
            );
        }
    }
}
