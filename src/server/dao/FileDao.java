package server.dao;

import common.FileResource;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FileDao {
    private final Connection conn;

    public FileDao(Connection conn) {
        this.conn = conn;
    }

    /* ===================== CREATE ===================== */

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
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    /* ===================== READ ===================== */

    /** Lấy list file theo message_id (1-n) */
    public List<FileResource> listByMessageId(long messageId) throws SQLException {
        String sql = "SELECT * FROM files WHERE message_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileResource> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /** Lấy metadata theo message_id (case cũ: 1 message ↔ 1 file) */
    public FileResource getByMessageId(long messageId) throws SQLException {
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
    public FileResource getById(long id) throws SQLException {
        String sql = "SELECT * FROM files WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /** Danh sách lịch sử file của 1 user (phân trang) */
    public List<FileResource> listByUserPaged(String username, int limit, int offset) throws SQLException {
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
                List<FileResource> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /** Lịch sử file giữa 2 user (phân trang) */
    public List<FileResource> listByUserAndPeer(String user, String peer, int limit, int offset) throws SQLException {
        String sql = """
            SELECT f.*
            FROM files f
            JOIN messages m ON f.message_id = m.id
            WHERE (m.sender = ? AND m.recipient = ?) OR (m.sender = ? AND m.recipient = ?)
            ORDER BY f.uploaded_at DESC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user);
            ps.setString(2, peer);
            ps.setString(3, peer);
            ps.setString(4, user);
            ps.setInt(5, Math.max(1, limit));
            ps.setInt(6, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<FileResource> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    /* ===================== DELETE ===================== */

    /** Xoá metadata theo message_id (trong DB) */
    public boolean deleteByMessageId(long messageId) throws SQLException {
        String sql = "DELETE FROM files WHERE message_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /* ===================== Helpers ===================== */

    /** Ánh xạ 1 hàng SQL → FileResource */
    private FileResource mapRow(ResultSet rs) throws SQLException {
        long id         = rs.getLong("id");
        long messageId  = rs.getLong("message_id");
        String fileName = rs.getString("file_name");
        String filePath = rs.getString("file_path");
        String mimeType = rs.getString("mime_type");
        long fileSize   = rs.getLong("file_size");

        Timestamp ts    = rs.getTimestamp("uploaded_at");
        Instant uploadedAt = (ts == null) ? null : ts.toInstant();

        return new FileResource(id, messageId, fileName, filePath, mimeType, fileSize, uploadedAt);
    }
}
