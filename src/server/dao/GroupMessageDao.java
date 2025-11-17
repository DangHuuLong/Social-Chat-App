package server.dao;

import common.GroupMessage;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GroupMessageDao {
    private final Connection conn;

    public GroupMessageDao(Connection conn) {
        this.conn = conn;
    }

    public long saveMessage(int groupId, String sender, String body, Long replyTo) throws SQLException {
        String sql = """
            INSERT INTO group_messages (group_id, sender, body, reply_to)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, groupId);
            ps.setString(2, sender);
            ps.setString(3, body);
            if (replyTo == null) ps.setNull(4, Types.BIGINT);
            else ps.setLong(4, replyTo);

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    // LOAD RECENT
    public List<GroupMessage> loadRecentMessages(int groupId, int limit) throws SQLException {
        String sql = """
            SELECT id, group_id, sender, body, reply_to, created_at, updated_at
            FROM group_messages
            WHERE group_id = ?
            ORDER BY id ASC
            LIMIT ?
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<GroupMessage> list = new ArrayList<>();
                while (rs.next()) {
                    Timestamp c = rs.getTimestamp("created_at");
                    Timestamp u = rs.getTimestamp("updated_at");

                    Instant createdAt = (c != null) ? c.toInstant() : Instant.now();
                    Instant updatedAt = (u != null) ? u.toInstant() : null;

                    list.add(new GroupMessage(
                            rs.getLong("id"),
                            rs.getInt("group_id"),
                            rs.getString("sender"),
                            rs.getString("body"),
                            (Long) rs.getObject("reply_to"),
                            createdAt,
                            updatedAt
                    ));
                }
                return list;
            }
        }
    }


    // EDIT: trả về group_id nếu sửa được
    public Integer updateByIdReturningGroup(long id, String editor, String newBody) throws SQLException {
        String sql = """
            UPDATE group_messages
            SET body = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND sender = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newBody);
            ps.setLong(2, id);
            ps.setString(3, editor);
            int updated = ps.executeUpdate();
            if (updated == 0) return null;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT group_id FROM group_messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("group_id");
            }
        }
        return null;
    }

    // DELETE: trả về group_id nếu xoá được
    public Integer deleteByIdReturningGroup(long id, String requester) throws SQLException {
        Integer groupId = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT group_id, sender FROM group_messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String sender = rs.getString("sender");
                groupId = rs.getInt("group_id");
                if (!requester.equals(sender)) {
                    return null; // hoặc check thêm owner nếu muốn cho owner xoá
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM group_messages WHERE id = ?")) {
            ps.setLong(1, id);
            int deleted = ps.executeUpdate();
            if (deleted == 0) return null;
        }

        return groupId;
    }
}
