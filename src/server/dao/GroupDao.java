package server.dao;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class GroupDao {
    private final Connection conn;

    public GroupDao(Connection conn) {
        this.conn = conn;
    }


    public int createGroup(String owner, String name) throws SQLException {
        String sql = "INSERT INTO groups (name, owner) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, owner);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int groupId = rs.getInt(1);
                    addMember(groupId, owner); // owner auto-joins
                    return groupId;
                }
            }
        }
        return -1;
    }


    public boolean addMember(int groupId, String username) throws SQLException {
        String sql = "INSERT IGNORE INTO group_members (group_id, username) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        }
    }


    public boolean removeMember(int groupId, String username) throws SQLException {
        String sql = "DELETE FROM group_members WHERE group_id=? AND username=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        }
    }

   
    public boolean deleteGroup(int groupId, String owner) throws SQLException {
        if (!isOwner(groupId, owner)) {
            return false; // prevent unauthorized delete
        }

        // Deleting from 'groups' cascades to 'group_members' if FK has ON DELETE CASCADE
        String sql = "DELETE FROM groups WHERE id=? AND owner=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setString(2, owner);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean isOwner(int groupId, String username) throws SQLException {
        String sql = "SELECT 1 FROM groups WHERE id=? AND owner=? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }


    public List<String> listMembers(int groupId) throws SQLException {
        String sql = "SELECT username FROM group_members WHERE group_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> members = new ArrayList<>();
                while (rs.next()) {
                    members.add(rs.getString("username"));
                }
                return members;
            }
        }
    }


    public String getGroupName(int groupId) throws SQLException {
        String sql = "SELECT name FROM groups WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        }
        return null;
    }
}
