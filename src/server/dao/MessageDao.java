package server.dao;

import common.Frame;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageDao {
    private final Connection conn;

    public MessageDao(Connection conn) {
        this.conn = conn;
    }

    /* ----------------- SAVE (API cũ – giữ lại) ----------------- */
    public long saveQueuedReturnId(Frame f) throws SQLException {
        return saveQueuedReturnId(f.sender, f.recipient, f.body, null);
    }

    public long saveSentReturnId(Frame f) throws SQLException {
        return saveSentReturnId(f.sender, f.recipient, f.body, null);
    }

    public void saveQueued(Frame f) throws SQLException { saveQueuedReturnId(f); }
    public void saveSent(Frame f)   throws SQLException { saveSentReturnId(f); }

    /* ----------------- SAVE (API mới – có reply_to) ----------------- */
    public long saveQueuedReturnId(String sender, String recipient, String body, Long replyTo) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status, reply_to) VALUES(?,?,?, 'queued', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, body);
            if (replyTo == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, replyTo);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public long saveSentReturnId(String sender, String recipient, String body, Long replyTo) throws SQLException {
        String sql = "INSERT INTO messages(sender, recipient, body, status, reply_to) VALUES(?,?,?, 'delivered', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sender);
            ps.setString(2, recipient);
            ps.setString(3, body);
            if (replyTo == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, replyTo);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    /* ----------------- OFFLINE QUEUE ----------------- */
    // Trả về Frame đã kèm prefix [REPLY:x] nếu có, để client render chip ngay.
    public List<Frame> loadQueued(String recipient) throws SQLException {
        String sql = "SELECT id, sender, body, reply_to FROM messages WHERE recipient=? AND status='queued' ORDER BY id";
        List<Frame> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recipient);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long id = rs.getLong("id");
                String sender = rs.getString("sender");
                String body   = rs.getString("body");
                Long replyTo  = (Long) rs.getObject("reply_to"); // nullable

                String bodyWithReply = prependReplyTag(body, replyTo);

                Frame f = new Frame(common.MessageType.DM, sender, recipient, bodyWithReply);
                f.transferId = String.valueOf(id);
                out.add(f);

                markDelivered(id);
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

    /* ----------------- HISTORY (API cũ) ----------------- */
    public static class HistoryRow {
        public final long id;
        public final String sender, recipient, body;
        public final Timestamp createdAt;
        public HistoryRow(long id, String s, String r, String b, Timestamp c) {
            this.id = id;
            this.sender = s;
            this.recipient = r;
            this.body = b;
            this.createdAt = c;
        }
    }

    /* ----------------- HISTORY (API mới có replyTo) ----------------- */
    public static class HistoryRowEx extends HistoryRow {
        public final Long replyTo; // nullable
        public HistoryRowEx(long id, String s, String r, String b, Timestamp c, Long replyTo) {
            super(id, s, r, b, c);
            this.replyTo = replyTo;
        }
    }

    // Dùng cho ClientHandler.handleHistory(...)
    public List<HistoryRowEx> loadConversationWithReply(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, reply_to, created_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY id DESC
            LIMIT ?
        """;
        List<HistoryRowEx> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, b);
            ps.setString(4, a);
            ps.setInt(5, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new HistoryRowEx(
                    rs.getLong("id"),
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at"),
                    (Long) rs.getObject("reply_to")
                ));
            }
        }
        Collections.reverse(out);
        return out;
    }

    // API cũ vẫn giữ (không replyTo) – để code cũ dùng được nếu còn chỗ gọi
    public List<HistoryRow> loadConversation(String a, String b, int limit) throws SQLException {
        String sql = """
            SELECT id, sender, recipient, body, created_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY id DESC
            LIMIT ?
        """;
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.setString(3, b);
            ps.setString(4, a);
            ps.setInt(5, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new HistoryRow(
                    rs.getLong("id"),
                    rs.getString("sender"),
                    rs.getString("recipient"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at")
                ));
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
                    System.out.println("[DAO] getReplyToByMessageId id=" + messageId + " -> " + v);
                    return (v == null) ? null : ((Number) v).longValue();
                } else {
                    System.out.println("[DAO] getReplyToByMessageId id=" + messageId + " -> <no row>");
                }
            }
        }
        return null;
    }

    /* ----------------- DELETE / EDIT / SEARCH giữ nguyên ----------------- */
    public boolean deleteById(long id, String requester) throws SQLException {
        String checkSql = "SELECT sender FROM messages WHERE id=?";
        String sender = null;
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) sender = rs.getString("sender");
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

    /* ----------------- SEARCH giữ nguyên (không thay đổi) ----------------- */
    public List<HistoryRow> searchConversation(String a, String b, String q, int limit, int offset) throws SQLException {
        String sql = """
            SELECT id,sender,recipient,body,created_at
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
                List<HistoryRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new HistoryRow(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("recipient"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at")
                    ));
                }
                return out;
            }
        } catch (SQLException e) {
            return searchConversationFallbackJava(a, b, q, limit, offset);
        }
    }

    private static String normalizeAscii(String s){
        if(s==null) return "";
        String n=java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        n=n.replaceAll("\\p{M}+","");
        return n.toLowerCase(java.util.Locale.ROOT);
    }

    private List<HistoryRow> searchConversationFallbackJava(String a,String b,String q,int limit,int offset) throws SQLException {
        String sql = """
            SELECT id,sender,recipient,body,created_at
            FROM messages
            WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)
            ORDER BY created_at ASC
            LIMIT ? OFFSET ?
        """;
        List<HistoryRow> out = new ArrayList<>();
        String nq = normalizeAscii(q);
        int need = limit + offset;
        int page = Math.max(need * 3, 200);
        int off = 0;
        while (out.size() < need) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,a); ps.setString(2,b); ps.setString(3,b); ps.setString(4,a);
                ps.setInt(5,page); ps.setInt(6,off);
                try(ResultSet rs=ps.executeQuery()){
                    boolean any=false;
                    while(rs.next()){
                        any=true;
                        String body=rs.getString("body");
                        if(normalizeAscii(body).contains(nq)){
                            out.add(new HistoryRow(
                                rs.getLong("id"),
                                rs.getString("sender"),
                                rs.getString("recipient"),
                                body,
                                rs.getTimestamp("created_at")
                            ));
                            if(out.size()>=need) break;
                        }
                    }
                    if(!any) break;
                }
            }
            off += page;
        }
        if (out.size() <= offset) return Collections.emptyList();
        return out.subList(offset, Math.min(out.size(), offset+limit));
    }

    /* ----------------- Helpers ----------------- */
    private static String prependReplyTag(String body, Long replyTo) {
        if (replyTo == null || replyTo <= 0) return (body == null ? "" : body);
        return "[REPLY:" + replyTo + "]" + (body == null ? "" : body);
    }
}