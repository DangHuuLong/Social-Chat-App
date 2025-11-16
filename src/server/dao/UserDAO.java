package server.dao;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import org.mindrot.jbcrypt.BCrypt;
import common.User;

public class UserDAO {

    public boolean usernameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean register(String username, String plainPassword, byte[] avatar, String avatarMime) throws SQLException {
        if (usernameExists(username)) return false;
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));

        // online / last_seen / avatar_updated_at có thể để DB default,
        // nên mình vẫn chỉ insert các cột cần thiết.
        String sql = "INSERT INTO users(username, password, avatar, avatar_mime) VALUES(?, ?, ?, ?)";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashed);

            if (avatar != null && avatar.length > 0) {
                ps.setBytes(3, avatar);
                ps.setString(4, avatarMime);
            } else {
                ps.setNull(3, Types.BLOB);
                ps.setNull(4, Types.VARCHAR);
            }

            return ps.executeUpdate() == 1;
        }
    }

    public boolean login(String username, String plainPassword) throws SQLException {
        String sql = "SELECT password FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedHash = rs.getString(1);
                return BCrypt.checkpw(plainPassword, storedHash);
            }
        }
    }

    // ==== helper: map 1 dòng ResultSet -> User đầy đủ field ====
    private static User mapUserRow(ResultSet rs, boolean includePassword) throws SQLException {
        User u = new User();

        u.setId(rs.getInt("id"));
        u.setUsername(rs.getString("username"));

        if (includePassword) {
            // lưu password hash trong User nếu cần dùng phía server
            u.setPassword(rs.getString("password"));
        }

        // có thể NULL
        try {
            byte[] avatar = rs.getBytes("avatar");
            u.setAvatar(avatar);
        } catch (SQLException ignore) {}

        try {
            u.setAvatarMime(rs.getString("avatar_mime"));
        } catch (SQLException ignore) {}

        try {
            int onlineInt = rs.getInt("online");
            if (!rs.wasNull()) {
                u.setOnline(onlineInt == 1);
            }
        } catch (SQLException ignore) {}

        try {
            u.setLastSeenIso(rs.getString("last_seen"));
        } catch (SQLException ignore) {}

        try {
            u.setAvatarUpdatedAtIso(rs.getString("avatar_updated_at"));
        } catch (SQLException ignore) {}

        return u;
    }

    public static List<User> listOthers(int excludeUserId) throws SQLException {
        String sql =
            "SELECT id, username, avatar, avatar_mime, online, last_seen, avatar_updated_at " +
            "FROM users WHERE id <> ? ORDER BY username";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, excludeUserId);
            try (ResultSet rs = ps.executeQuery()) {
                List<User> list = new ArrayList<>();
                while (rs.next()) {
                    User u = mapUserRow(rs, false);
                    list.add(u);
                }
                return list;
            }
        }
    }

    public static List<User> searchUsers(String keyword, int excludeUserId) throws SQLException {
        String sql =
            "SELECT id, username, avatar, avatar_mime, online, last_seen, avatar_updated_at " +
            "FROM users " +
            "WHERE id <> ? AND username LIKE ? ORDER BY username";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, excludeUserId);
            ps.setString(2, "%" + keyword + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<User> list = new ArrayList<>();
                while (rs.next()) {
                    User u = mapUserRow(rs, false);
                    list.add(u);
                }
                return list;
            }
        }
    }

    public static User findByUsername(String username) throws SQLException {
        String sql =
            "SELECT id, username, password, avatar, avatar_mime, online, last_seen, avatar_updated_at " +
            "FROM users WHERE username = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUserRow(rs, true);
                }
                return null;
            }
        }
    }

    public static void setOnline(int userId, boolean online) throws SQLException {
        String sql = "UPDATE users SET online=?, last_seen=? WHERE id=?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, online ? 1 : 0);
            ps.setString(2, online ? null : Instant.now().toString());
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    public static Map<Integer, Presence> getPresenceOfAll() throws SQLException {
        String sql = "SELECT id, online, last_seen FROM users";
        Map<Integer, Presence> map = new HashMap<>();
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(
                    rs.getInt("id"),
                    new Presence(
                        rs.getInt("online") == 1,
                        rs.getString("last_seen")
                    )
                );
            }
        }
        return map;
    }

    public static class Presence {
        public final boolean online;
        public final String lastSeenIso;
        public Presence(boolean o, String t) {
            online = o;
            lastSeenIso = t;
        }
    }

    public static Presence getPresence(int userId) throws SQLException {
        String sql = "SELECT online, last_seen FROM users WHERE id = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean online = rs.getInt("online") == 1;
                    String lastSeen = rs.getString("last_seen");
                    return new Presence(online, lastSeen);
                }
            }
        }
        return null;
    }

    public static byte[] getAvatarById(int userId) throws SQLException {
        String sql = "SELECT avatar FROM users WHERE id = ?";
        try (Connection c = DBConnection.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes(1);
                }
            }
        }
        return null;
    }
}
