package server.dao;

import common.Frame;
import common.Message;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageDao {
    private final Connection conn;

    public MessageDao(Connection conn) {
        this.conn = conn;
    }

    /* ===================== SAVE (API cũ – giữ lại gọi từ Frame) ===================== */

    public long saveQueuedReturnId(Frame f) throws SQLException {
        return saveQueuedReturnId(f.sender, f.recipient, f.body, null);
    }

    public long saveSentReturnId(Frame f) throws SQLException {
        return saveSentReturnId(f.sender, f.recipient, f.body, null);
    }

    public void saveQueued(Frame f) throws SQLException {
        saveQueuedReturnId(f);
    }

    public void saveSent(Frame f) throws SQLException {
        saveSentReturnId(f);
    }

    /* ===================== SAVE (API mới – dùng trường đầy đủ) ===================== */

    public long saveQueuedReturnId(String sender, String recipient, String body, Long replyTo) throws SQLException {
        return insertMessage(sender, recipient, body, replyTo, "queued");
    }

    public long saveSentReturnId(String sender, String recipient, String body, Long replyTo) throws SQLException {
        return insertMessage(sender, recipient, body, replyTo, "delivered");
    }

    public long saveReturnId(Message m) throws SQLException {
        return insertMessage(
                m.getSender(),
                m.getRecipient(),
                m.getBody(),
                m.getReplyTo(),
                m.getStatus() == null ? "queued" : m.getStatus()
        );
    }

    private long insertMessage(String sender,
                               String recipient,
                               String body,
                               Long replyTo,
                               String status) throws SQLException {

        String sql = "INSERT INTO messages(sender, recipient, body, reply_to, status) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, body);
            if (replyTo == null) ps.setNull(4, Types.BIGINT);
            else ps.setLong(4, replyTo);
            ps.setString(5, status);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    /* ===================== OFFLINE QUEUE ===================== */
    public List<Message> loadQueued(String recipient) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, reply_to, status,
                   created_at, delivered_at, updated_at
            FROM messages
            WHERE recipient=? AND status='queued'
            ORDER BY id
        """;

        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Message m = mapRow(rs);
                    out.add(m);
                    markDelivered(m.getId());
                }
            }
        }
        return out;
    }

    private void markDelivered(long id) throws SQLException {
        String sql = "UPDATE messages SET status='delivered', delivered_at=NOW() WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /* ===================== HISTORY ===================== */
    public List<Message> loadConversation(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, reply_to, status,
                   created_at, delivered_at, updated_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY id DESC
            LIMIT ?
        """;

        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, b);
            ps.setString(4, a);
            ps.setInt(5, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
        }
        Collections.reverse(out);
        return out;
    }

    public Long getReplyToByMessageId(long messageId) throws SQLException {
        String sql = "SELECT reply_to FROM messages WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    return (v == null) ? null : ((Number) v).longValue();
                }
            }
        }
        return null;
    }

    /* ===================== DELETE / EDIT ===================== */

    public boolean deleteById(long id, String requester) throws SQLException {
        String checkSql = "SELECT sender FROM messages WHERE id=?";
        String sender = null;
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) sender = rs.getString("sender");
            }
        }
        if (sender == null || !sender.equals(requester)) return false;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public String deleteByIdReturningPeer(long id, String requester) throws SQLException {
        String sqlSel = "SELECT sender, recipient FROM messages WHERE id=?";
        String sender = null, recipient = null;
        try (PreparedStatement ps = conn.prepareStatement(sqlSel)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sender = rs.getString("sender");
                    recipient = rs.getString("recipient");
                }
            }
        }
        if (sender == null || !sender.equals(requester)) return null;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE id=?")) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            return (n > 0) ? recipient : null;
        }
    }

    public String updateByIdReturningPeer(long id, String requester, String newBody) throws SQLException {
        String sel = "SELECT sender, recipient FROM messages WHERE id=?";
        String sender = null, recipient = null;
        try (PreparedStatement ps = conn.prepareStatement(sel)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sender = rs.getString("sender");
                    recipient = rs.getString("recipient");
                }
            }
        }
        if (sender == null || !sender.equals(requester)) return null;

        String upd = "UPDATE messages SET body=?, updated_at=NOW() WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(upd)) {
            ps.setString(1, newBody);
            ps.setLong(2, id);
            int n = ps.executeUpdate();
            return (n > 0) ? recipient : null;
        }
    }

    public String getSenderById(long id) throws SQLException {
        String sql = "SELECT sender FROM messages WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("sender");
            }
        }
        return null;
    }

    /* ===================== SEARCH ===================== */

    public List<Message> searchConversation(String a, String b, String q, int limit, int offset) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, reply_to, status,
                   created_at, delivered_at, updated_at
            FROM messages
            WHERE ((sender=? AND recipient=?) OR (sender=? AND recipient=?))
              AND body COLLATE utf8mb4_0900_ai_ci LIKE ?
            ORDER BY created_at ASC
            LIMIT ? OFFSET ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, b);
            ps.setString(4, a);
            ps.setString(5, "%" + q + "%");
            ps.setInt(6, Math.max(1, limit));
            ps.setInt(7, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<Message> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            return searchConversationFallbackJava(a, b, q, limit, offset);
        }
    }

    private List<Message> searchConversationFallbackJava(String a, String b, String q, int limit, int offset) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, reply_to, status,
                   created_at, delivered_at, updated_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY created_at ASC
            LIMIT ? OFFSET ?
        """;

        List<Message> out = new ArrayList<>();
        String nq = normalizeAscii(q);
        int need = limit + offset;
        int page = Math.max(need * 3, 200);
        int off = 0;

        while (out.size() < need) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, a);
                ps.setString(2, b);
                ps.setString(3, b);
                ps.setString(4, a);
                ps.setInt(5, page);
                ps.setInt(6, off);

                try (ResultSet rs = ps.executeQuery()) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        String body = rs.getString("body");
                        if (normalizeAscii(body).contains(nq)) {
                            out.add(mapRow(rs));
                            if (out.size() >= need) break;
                        }
                    }
                    if (!any) break;
                }
            }
            off += page;
        }

        if (out.size() <= offset) return Collections.emptyList();
        return out.subList(offset, Math.min(out.size(), offset + limit));
    }

    /* ===================== Helpers ===================== */

    private static String normalizeAscii(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT);
    }

    private static Message mapRow(ResultSet rs) throws SQLException {
        Message m = new Message();

        m.setId(rs.getLong("id"));
        m.setSender(rs.getString("sender"));
        m.setRecipient(rs.getString("recipient"));
        m.setBody(rs.getString("body"));

        Object reply = rs.getObject("reply_to");
        m.setReplyTo(reply == null ? null : ((Number) reply).longValue());

        m.setStatus(rs.getString("status"));

        Timestamp c = rs.getTimestamp("created_at");
        Timestamp d = rs.getTimestamp("delivered_at");
        Timestamp u = rs.getTimestamp("updated_at");

        if (c != null) m.setCreatedAt(c.toInstant());
        if (d != null) m.setDeliveredAt(d.toInstant());
        if (u != null) m.setUpdatedAt(u.toInstant());

        return m;
    }
}
