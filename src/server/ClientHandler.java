package server;

import server.dao.MessageDao;
import server.signaling.CallRouter;
import common.Frame;
import common.FrameIO;
import common.MessageType;
import server.dao.FileDao;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Set<ClientHandler> clients;
    private final Map<String, ClientHandler> online;
    private final MessageDao messageDao;
    private final FileDao fileDao;

    private DataInputStream binIn;
    private DataOutputStream binOut;

    private String username = null;
    private static final File UPLOAD_DIR = new File("uploads");
    private static final Map<String, String> fileNameMap = new ConcurrentHashMap<>();

    // ==== state 1 phiên upload (hợp nhất file/audio) ====
    private String upFileId;
    private String upToUser;
    private String upOrigName;
    private String upMime;
    private long upDeclaredSize;
    private int upExpectedSeq;
    private long upWritten;
    private BufferedOutputStream upOut;

    // map uuid<->id để tải lại theo uuid phía client cũ
    private static final Map<String, Long> uuidToFileId = new ConcurrentHashMap<>();
    private static final Map<String, Long> uuidToMsgId  = new ConcurrentHashMap<>();
    private static final String REPLY_TAG = "[REPLY:";
    private Long upReplyTo;

    public ClientHandler(Socket socket,
                         Set<ClientHandler> clients,
                         Map<String, ClientHandler> online,
                         MessageDao messageDao, FileDao fileDao) {
        this.socket = socket;
        this.clients = clients;
        this.online = online;
        this.messageDao = messageDao;
        this.fileDao = fileDao;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            binIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            binOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();

            while (true) {
                Frame f = FrameIO.read(binIn);
                if (f == null) break;

                switch (f.type) {
                    case REGISTER, LOGIN -> handleLogin(f);
                    case DM -> handleDirectMessage(f);
                    case HISTORY -> handleHistory(f);

                    // HỢP NHẤT: FILE + AUDIO đều đi qua 2 type meta/chunk này
                    case FILE_META, FILE_CHUNK -> handleFile(f);
                    case AUDIO_META, AUDIO_CHUNK -> handleFile(f); 

                    case CALL_INVITE, CALL_ACCEPT, CALL_REJECT,
                         CALL_CANCEL, CALL_BUSY, CALL_END,
                         CALL_OFFER, CALL_ANSWER, CALL_ICE -> handleCall(f);

                    case DELETE_MSG -> handleDeleteMessage(f);

                    // Lịch sử/tải/xoá file — CHUNG
                    case DOWNLOAD_FILE -> handleDownloadFile(f);
                    case FILE_HISTORY  -> handleFileHistory(f);
                    case DELETE_FILE   -> handleDeleteFile(f);

                    case EDIT_MSG -> handleEditMessage(f);
                    case SEARCH   -> handleSearch(f);

                    default -> System.out.println("[SERVER] Unknown frame: " + f.type);
                }
            }
        } catch (SocketException | EOFException ignored) {
        } catch (IOException e) {
            System.err.println("[SERVER] IO error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    /* ================= LOGIN ================= */
    private void handleLogin(Frame f) {
        String u = f.sender;
        if (u == null || u.isBlank() || online.containsKey(u)) {
            sendFrame(Frame.error("LOGIN_FAIL"));
            return;
        }
        username = u;
        online.put(username, this);
        CallRouter.getInstance().register(username, this);

        sendFrame(Frame.ack("OK LOGIN " + username));
        broadcast("🔵 " + username + " joined", true);

        try {
            var pending = messageDao.loadQueued(username);
            for (var m : pending) sendFrame(m);
            if (!pending.isEmpty()) sendFrame(Frame.ack("Delivered " + pending.size() + " offline messages"));
        } catch (SQLException e) {
            sendFrame(Frame.error("OFFLINE_DELIVERY_FAIL"));
        }
    }

    /* ================= DIRECT MESSAGE ================= */
    private void handleDirectMessage(Frame f) {
        String to = f.recipient;
        if (to == null || to.isBlank()) { sendFrame(Frame.error("BAD_DM")); return; }

        // Tách reply_to khỏi body (nếu có)
        StringBuilder bodyBuf = new StringBuilder(f.body == null ? "" : f.body);
        Long replyTo = extractReplyIdAndStrip(bodyBuf);
        String cleanBody = bodyBuf.toString();

        try {
            long id;
            ClientHandler target = online.get(to);
            if (target != null) {
                // Lưu bản gửi (đã strip prefix), kèm reply_to
                id = messageDao.saveSentReturnId(f.sender, to, cleanBody, replyTo);
                // Phát cho người nhận, thêm lại prefix để client render chip
                Frame deliver = new Frame(MessageType.DM, f.sender, to, prependReplyTag(cleanBody, replyTo));
                deliver.transferId = String.valueOf(id);
                target.sendFrame(deliver);

                Frame ack = Frame.ack("OK DM");
                ack.transferId = String.valueOf(id);
                sendFrame(ack);
            } else {
                id = messageDao.saveQueuedReturnId(f.sender, to, cleanBody, replyTo);
                Frame ack = Frame.ack("OK QUEUED");
                ack.transferId = String.valueOf(id);
                sendFrame(ack);
            }
        } catch (SQLException e) {
            sendFrame(Frame.error("DM_SAVE_FAIL"));
        }
    }

    /* ================= EDIT ================= */
    private void handleEditMessage(Frame f) {
        try {
            long id = parseLongSafe(f.transferId != null ? f.transferId : f.body, 0L);
            if (id <= 0) { sendFrame(Frame.error("BAD_ID")); return; }
            String newBody = (f.body == null) ? "" : f.body;

            String peer = messageDao.updateByIdReturningPeer(id, username, newBody);
            if (peer == null) { sendFrame(Frame.error("DENIED_OR_NOT_FOUND")); return; }

            Frame ack = Frame.ack("OK EDIT"); ack.transferId = String.valueOf(id); sendFrame(ack);

            Frame evt = new Frame(MessageType.EDIT_MSG, username, peer, newBody);
            evt.transferId = String.valueOf(id);
            ClientHandler peerHandler = online.get(peer);
            if (peerHandler != null) peerHandler.sendFrame(evt);
        } catch (Exception e) {
            sendFrame(Frame.error("EDIT_FAIL"));
        }
    }

    /* ================= SEARCH ================= */
    private void handleSearch(Frame f){
        String peer = f.recipient;
        String q = jsonGet(f.body, "q");
        if (q == null) q = "";
        int limit = (f.seq > 0) ? f.seq : 50;
        int offset = (int) parseLongSafe(jsonGet(f.body, "offset"), 0);
        try{
            var rows = messageDao.searchConversation(username, peer, q, limit, offset);
            for (var r : rows){
                Frame hit = new Frame(MessageType.SEARCH_HIT, r.sender, r.recipient, r.body);
                hit.transferId = String.valueOf(r.id);
                sendFrame(hit);
            }
            sendFrame(Frame.ack("OK SEARCH " + rows.size()));
        }catch(Exception e){
            sendFrame(Frame.error("SEARCH_FAIL"));
        }
    }

    /* ================= HISTORY ================= */
    private void handleHistory(Frame f) {
        String peer = f.recipient;
        int limit = 50;
        try { limit = Integer.parseInt(f.body); } catch (Exception ignore) {}

        try {
            var rows = messageDao.loadConversationWithReply(username, peer, limit);
            for (var r : rows) {
                boolean incoming = !r.sender.equals(username);
                String plain = r.body == null ? "" : r.body;
                String bodyWithReply = prependReplyTag(plain, r.replyTo);

                String txt = incoming
                        ? "[HIST IN] " + r.sender + ": " + bodyWithReply
                        : "[HIST OUT] " + bodyWithReply;

                Frame hist = new Frame(MessageType.HISTORY, r.sender, r.recipient, txt);
                hist.transferId = String.valueOf(r.id);
                sendFrame(hist);
            }
            sendFrame(Frame.ack("OK HISTORY " + rows.size()));
        } catch (SQLException e) {
            sendFrame(Frame.error("HISTORY_FAIL"));
        }
    }

    /* ================= FILE (hợp nhất cả AUDIO) ================= */
    private void handleFile(Frame f) {
        try {
            // --- META (FILE_META / AUDIO_META) ---
            if (f.type == MessageType.FILE_META || f.type == MessageType.AUDIO_META) {
                // Cho phép prefix [REPLY:<id>] đứng TRƯỚC JSON, sẽ strip ra và lưu vào upReplyTo
                String body = (f.body == null ? "" : f.body);
                Long replyToParsed = null;
                if (body.startsWith("[REPLY:")) {
                    int end = body.indexOf(']');
                    if (end > "[REPLY:".length()) {
                        String num = body.substring("[REPLY:".length(), end);
                        try { replyToParsed = Long.parseLong(num); } catch (NumberFormatException ignore) {}
                        body = body.substring(end + 1); // bỏ prefix
                    }
                }

                String to   = pickJson(body, "to");
                String name = pickJson(body, "name");
                String mime = pickJson(body, "mime");
                String fid  = pickJson(body, "fileId");
                long size   = parseLongSafe(pickJson(body, "size"), 0);

                if (fid == null || fid.isBlank()) fid = java.util.UUID.randomUUID().toString();
                if (name == null || name.isBlank()) name = "file-" + fid;
                if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                if (size > Frame.MAX_FILE_BYTES) throw new IOException("file too large");

                // reset phiên trước (nếu có)
                if (upOut != null) { try { upOut.close(); } catch (Exception ignore) {} }

                // set state phiên upload
                upFileId       = fid;
                upToUser       = to;
                upOrigName     = name;
                upMime         = mime;
                upDeclaredSize = size;
                upExpectedSeq  = 0;
                upWritten      = 0L;
                upReplyTo      = replyToParsed; // << quan trọng

                if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();
                File outFile = new File(UPLOAD_DIR, sanitizeFilename(fid));
                fileNameMap.put(fid, name);
                upOut = new BufferedOutputStream(new FileOutputStream(outFile));
                return;
            }

            // --- CHUNK (FILE_CHUNK / AUDIO_CHUNK) ---
            if (f.type == MessageType.FILE_CHUNK || f.type == MessageType.AUDIO_CHUNK) {
                if (upOut == null || upFileId == null) throw new IOException("CHUNK without META");
                if (!upFileId.equals(f.transferId))     throw new IOException("Mismatched fileId");
                if (f.seq != upExpectedSeq)             throw new IOException("Out-of-order chunk");

                int len = (f.bin == null ? 0 : f.bin.length);
                if (upWritten + len > Frame.MAX_FILE_BYTES) throw new IOException("File exceeds limit");
                if (len > 0) { upOut.write(f.bin); upWritten += len; }
                upExpectedSeq++;

                if (f.last) {
                    // đóng file
                    upOut.flush(); upOut.close(); upOut = null;

                    long msgId  = 0L;
                    long fileId = 0L;

                    try {
                        // 1) Tạo message text đại diện file và lấy messageId
                        //    LƯU Ý: lưu *không có* prefix [REPLY], thay vào đó truyền reply_to qua DAO
                        String fileBody = "[FILE] " + upOrigName;

                        // dùng overload mới có replyTo
                        msgId = messageDao.saveSentReturnId(
                                username,                // sender
                                upToUser,                // recipient
                                fileBody,                // body thuần
                                upReplyTo                // reply_to (nullable)
                        );

                        // 2) Lưu metadata file và lấy fileId
                        String filePath = new File(UPLOAD_DIR, sanitizeFilename(upFileId)).getAbsolutePath();
                        fileId = fileDao.save(msgId, upOrigName, filePath, upMime, upWritten);
                        System.out.println("[FILE/SAVE] sender=" + username
                                + " to=" + upToUser
                                + " msgId=" + msgId
                                + " fileId=" + fileId
                                + " origName=" + upOrigName
                                + " mime=" + upMime
                                + " bytes=" + upWritten
                                + " replyTo=" + upReplyTo);
                        if (fileId > 0) uuidToFileId.put(upFileId, fileId);
                        if (msgId  > 0) uuidToMsgId.put(upFileId, msgId);
                    } catch (SQLException sqle) {
                        System.err.println("[DB] Failed to save file metadata: " + sqle.getMessage());
                    }

                    // 3) Gửi ACK cho client GỬI: giữ nguyên transferId = uuid; body = JSON
                    String ackJson = "{"
                            + "\"status\":\"FILE_SAVED\","
                            + "\"messageId\":" + msgId + ","
                            + "\"fileId\":" + fileId + ","
                            + "\"bytes\":" + upWritten + ","
                            + "\"mime\":\"" + escJson(upMime) + "\""
                            + "}";
                    Frame ack = Frame.ack(ackJson);
                    ack.transferId = upFileId; // uuid
                    sendFrame(ack);

                    // 4) Push sự kiện cho người nhận (realtime)
                    if (upToUser != null && !upToUser.isBlank()) {
                        ClientHandler target = online.get(upToUser);
                        if (target != null) {
                            String savedName = sanitizeFilename(upOrigName);
                            String json = "{"
                                    + "\"from\":\"" + escJson(username) + "\","
                                    + "\"to\":\""   + escJson(upToUser) + "\","
                                    + "\"uuid\":\"" + escJson(upFileId) + "\","
                                    + "\"id\":\""   + escJson(upFileId) + "\","
                                    + "\"fileId\":" + fileId + ","
                                    + "\"messageId\":" + msgId + ","
                                    + "\"replyTo\":" + (upReplyTo == null ? "null" : upReplyTo) + ","  // << thêm replyTo
                                    + "\"name\":\"" + escJson(savedName) + "\","
                                    + "\"mime\":\"" + escJson(upMime) + "\","
                                    + "\"bytes\":"  + upWritten
                                    + "}";
                            Frame evt = new Frame(MessageType.FILE_EVT, username, upToUser, json);
                            System.out.println("[FILE/EVT] push to=" + upToUser
                                    + " uuid=" + upFileId
                                    + " fileId=" + fileId
                                    + " messageId=" + msgId
                                    + " replyTo=" + upReplyTo);
                            target.sendFrame(evt);
                        }
                    }

                    // 5) Reset state phiên upload
                    upFileId = null;
                    upToUser = null;
                    upOrigName = null;
                    upMime = null;
                    upDeclaredSize = 0;
                    upExpectedSeq = 0;
                    upWritten = 0;
                    upReplyTo = null; // << reset
                }
                return;
            }

        } catch (IOException e) {
            try { if (upOut != null) upOut.close(); } catch (Exception ignore) {}
            upOut = null; upFileId = null; upReplyTo = null;
            sendFrame(Frame.error("FILE_FAIL"));
        }
    }

    /* ================= DOWNLOAD (hợp nhất) ================= */
    private void handleDownloadFile(Frame f) {
        try {
            String body = (f.body == null) ? "" : f.body.trim();
            System.out.println("[DL] request body=" + body);
            Long fileId = null;
            Long messageId = null;

            String fileIdStr = jsonGet(body, "fileId");
            String msgIdStr  = jsonGet(body, "messageId");
            String legacyId  = jsonGet(body, "id"); // uuid cũ
            
            if (fileIdStr != null && !fileIdStr.isBlank()) { try { fileId = Long.parseLong(fileIdStr); } catch (Exception ignore) {} }
            if (msgIdStr  != null && !msgIdStr.isBlank())  { try { messageId = Long.parseLong(msgIdStr); }  catch (Exception ignore) {} }

            if (fileId == null && messageId == null) {
                long n = parseLongSafe(body, 0L);
                if (n > 0) fileId = n;
            }

            String uuid = null;
            if (legacyId != null && !legacyId.isBlank()) uuid = legacyId;
            else if ((fileId == null && messageId == null) && body.length() >= 32 && body.contains("-")) uuid = body;
            if (uuid != null) {
                Long mappedFileId = uuidToFileId.get(uuid);
                Long mappedMsgId  = uuidToMsgId.get(uuid);
                if (fileId == null && mappedFileId != null) fileId = mappedFileId;
                if (messageId == null && mappedMsgId  != null) messageId = mappedMsgId;
            }
            System.out.println("[DL] resolved fileId=" + fileId + " messageId=" + messageId + " legacyUuid=" + uuid);
            FileDao.FileRecord fileRow = null;
            if (fileId != null && fileId > 0) fileRow = fileDao.getById(fileId);
            if (fileRow == null && messageId != null && messageId > 0) fileRow = fileDao.getByMessageId(messageId);

            if (fileRow == null) { 
            	System.out.println("[DL] fileRow=null (not found by fileId/messageId)");
            	sendFrame(Frame.error("INVALID_FILE_ID")); 
            	return; 
            }
            System.out.println("[DL] fileRow id=" + fileRow.id
                    + " msgId=" + fileRow.messageId
                    + " name=" + fileRow.fileName
                    + " mime=" + fileRow.mimeType
                    + " path=" + fileRow.filePath);
            File file = new File(fileRow.filePath);
            if (!file.exists()) { sendFrame(Frame.error("FILE_NOT_FOUND_DISK")); return; }

            String mime = (fileRow.mimeType != null) ? fileRow.mimeType : "application/octet-stream";
            String name = (fileRow.fileName  != null) ? fileRow.fileName  : ("file-" + fileRow.id);
            
            Long replyTo = null;
            long msgIdForFile = fileRow.messageId;
            try {
                if (msgIdForFile > 0) {
                    replyTo = messageDao.getReplyToByMessageId(msgIdForFile);
                }
                System.out.println("[DL] replyTo for messageId=" + msgIdForFile + " -> " + replyTo);
            } catch (SQLException e) {
                System.out.println("[DL] getReplyToByMessageId failed: " + e.getMessage());
            }

            
            String metaJson = "{"
                    + "\"from\":\"" + escJson(username) + "\","
                    + "\"to\":\"\","
                    + "\"name\":\"" + escJson(name) + "\","
                    + "\"mime\":\"" + escJson(mime) + "\","
                    + "\"fileId\":\"" + fileRow.id + "\","
                    + "\"messageId\":\"" + (fileRow.messageId) + "\","
                    + "\"replyTo\":" + (replyTo == null ? "null" : replyTo) + "," 
                    + "\"size\":" + file.length()
                    + "}";
            System.out.println("[DL] send FILE_META fileId=" + fileRow.id
                    + " msgId=" + fileRow.messageId
                    + " replyTo=" + replyTo
                    + " size=" + file.length());
            sendFrame(new Frame(MessageType.FILE_META, username, "", metaJson));

            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[Frame.CHUNK_SIZE];
                int n, seq = 0;
                long rem = file.length();
                while ((n = fis.read(buf)) != -1) {
                	if (seq == 0) System.out.println("[DL] start streaming fileId=" + fileRow.id + " total=" + file.length());
                    rem -= n;
                    boolean last = (rem == 0);
                    byte[] slice = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    Frame ch = new Frame(MessageType.FILE_CHUNK, username, "", "");
                    ch.transferId = String.valueOf(fileRow.id);
                    ch.seq = seq++;
                    ch.last = last;
                    ch.bin = slice;
                    sendFrame(ch);
                    if (last) System.out.println("[DL] done streaming fileId=" + fileRow.id + " chunks=" + (seq));
                }
            }
        } catch (SQLException e) {
            sendFrame(Frame.error("DB_ERROR_FILE_FETCH"));
        } catch (IOException e) {
            sendFrame(Frame.error("DOWNLOAD_FAIL"));
        }
    }

    /* ================= FILE HISTORY (hợp nhất) ================= */
    private void handleFileHistory(Frame f) {
        int limit = 5;
        int offset = 0;
        
        // Lấy thông tin người đối thoại (peer) từ trường recipient
        String peer = f.recipient;
        if (peer == null || peer.isBlank()) {
            sendFrame(Frame.error("MISSING_RECIPIENT_FOR_FILE_HISTORY"));
            return;
        }
        
        try {
            if (f.body != null && !f.body.isBlank()) {
                String body = f.body.trim();
                String limitStr = jsonGet(body, "limit");
                String offsetStr = jsonGet(body, "offset");
                if (limitStr != null)  limit  = Integer.parseInt(limitStr);
                if (offsetStr != null) offset = Integer.parseInt(offsetStr);
            }
        } catch (Exception ignore) {}

        try {
            // Gọi phương thức listByUserAndPeer mới trong FileDao
            var rows = fileDao.listByUserAndPeer(username, peer, limit, offset);
            for (var r : rows) {
                String jsonBody = String.format(
                    "{\"file_name\":\"%s\",\"mime_type\":\"%s\",\"file_size\":%d,\"file_path\":\"%s\"}",
                    escJson(r.fileName), escJson(r.mimeType), r.fileSize, escJson(r.filePath)
                );
                Frame hist = new Frame(MessageType.FILE_HISTORY, username, peer, jsonBody);
                hist.transferId = String.valueOf(r.id);
                sendFrame(hist);
            }
            sendFrame(Frame.ack("OK FILE_HISTORY " + rows.size()));
        } catch (SQLException e) {
            sendFrame(Frame.error("FILE_HISTORY_FAIL"));
        }
    }

    /* ================= DELETE FILE (hợp nhất) ================= */
    private void handleDeleteFile(Frame f) {
        long msgId = parseLongSafe(f.body, 0L);
        if (msgId <= 0) { sendFrame(Frame.error("INVALID_FILE_ID")); return; }

        try {
            var row = fileDao.getByMessageId(msgId);
            boolean deleted = fileDao.deleteByMessageId(msgId);
            if (deleted) {
                if (row != null) {
                    File onDisk = new File(row.filePath);
                    if (onDisk.exists()) onDisk.delete();
                }
                sendFrame(Frame.ack("OK FILE_DELETED"));
            } else {
                sendFrame(Frame.error("FILE_NOT_FOUND"));
            }
        } catch (SQLException e) {
            sendFrame(Frame.error("DB_FILE_DELETE_FAIL"));
        }
    }

    /* ================= CALL ================= */
    private void handleCall(Frame f) {
        CallRouter.getInstance().route(username, f);
    }

    /* ================= DELETE MESSAGE ================= */
    private void handleDeleteMessage(Frame f) {
        try {
            long id = parseLongSafe(f.body, 0L);
            if (id <= 0) { sendFrame(Frame.error("BAD_ID")); return; }
            String peer = messageDao.deleteByIdReturningPeer(id, username);

            // xoá file nếu có
            try {
                var fileRow = fileDao.getByMessageId(id);
                if (fileRow != null) {
                    fileDao.deleteByMessageId(id);
                    File toDelete = new File(fileRow.filePath);
                    if (toDelete.exists()) toDelete.delete();
                }
            } catch (SQLException ignore) {}

            if (peer == null) { sendFrame(Frame.error("DENIED_OR_NOT_FOUND")); return; }

            Frame ack = Frame.ack("OK DELETE");
            ack.transferId = String.valueOf(id);
            sendFrame(ack);

            Frame evt = new Frame(MessageType.DELETE_MSG, username, peer, "");
            evt.transferId = String.valueOf(id);
            ClientHandler peerHandler = online.get(peer);
            if (peerHandler != null) peerHandler.sendFrame(evt);

        } catch (Exception e) {
            sendFrame(Frame.error("DELETE_FAIL"));
        }
    }

    /* ================= Helpers ================= */
    private void broadcast(String msg, boolean excludeSelf) {
        for (ClientHandler c : clients) {
            if (excludeSelf && c == this) continue;
            c.sendFrame(Frame.ack(msg));
        }
    }

    public void sendFrame(Frame f) {
        if (binOut == null) return;
        try {
            FrameIO.write(binOut, f);
            binOut.flush();
        } catch (Exception e) {
            System.err.println("[SERVER] Send frame failed: " + e.getMessage());
        }
    }

    private void cleanup() {
        if (username != null) {
            CallRouter.getInstance().unregister(username, this);
            online.remove(username, this);
            broadcast("🔴 " + username + " left", true);
            username = null;
        }
        clients.remove(this);
        close();
    }

    public void close() {
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }

    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }
    private static String pickJson(String json, String key) { return jsonGet(json, key); }
    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String safe = name.replaceAll("[\\r\\n\\t]", "").replaceAll("[<>:\"|?*]", "_");
        if (safe.equals(".") || safe.equals("..") || safe.isBlank()) safe = "file";
        return safe;
    }
    private static long parseLongSafe(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
    
    /*HEPLER FOR REPLY*/
    private static Long extractReplyIdAndStrip(StringBuilder bodyInOut) {
        if (bodyInOut == null) return null;
        String s = bodyInOut.toString();
        if (s.startsWith(REPLY_TAG)) {
            int end = s.indexOf(']');
            if (end > REPLY_TAG.length()) {
                String num = s.substring(REPLY_TAG.length(), end);
                try {
                    long id = Long.parseLong(num);
                    bodyInOut.setLength(0);
                    bodyInOut.append(s.substring(end + 1)); // strip tag
                    return id;
                } catch (NumberFormatException ignore) {}
            }
        }
        return null;
    }

    private static String prependReplyTag(String body, Long replyTo) {
        if (replyTo == null || replyTo <= 0) return body;
        return "[REPLY:" + replyTo + "]" + (body == null ? "" : body);
    }

}
