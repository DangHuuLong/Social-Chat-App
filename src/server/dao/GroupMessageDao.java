package server.dao;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class GroupMessageDao {
    private final Connection conn;

    public GroupMessageDao(Connection conn) {
        this.conn = conn;
    }

    // === SAVE ===
    public long saveMessage(int groupId, String sender, String body) throws SQLException {
        String sql = "INSERT INTO group_messages (group_id, sender, body) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, groupId);
            ps.setString(2, sender);
            ps.setString(3, body);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    // === LIST HISTORY ===
    public List<String> loadRecentMessages(int groupId, int limit) throws SQLException {
        String sql = "SELECT CONCAT(sender, ': ', body) AS line FROM group_messages " +
                     "WHERE group_id=? ORDER BY id ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setInt(2, limit);	
            try (ResultSet rs = ps.executeQuery()) {
                List<String> msgs = new ArrayList<>();
                while (rs.next()) msgs.add(rs.getString("line"));
                return msgs;
            }
        }
    }
}
