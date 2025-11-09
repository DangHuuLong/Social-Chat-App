package server;

import server.dao.MessageDao;
import server.signaling.CallRouter;
import common.Frame;
import common.FrameIO;
import common.GroupMessage;
import common.MessageType;
import server.dao.FileDao;
import server.dao.GroupDao;
import server.dao.GroupMessageDao;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import common.Message;
import common.FileResource;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Set<ClientHandler> clients;
    private final Map<String, ClientHandler> online;
    private final MessageDao messageDao;
    private final FileDao fileDao;
    private final GroupDao groupDao;
    private final GroupMessageDao groupMessageDao;
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
                         MessageDao messageDao, FileDao fileDao, GroupDao groupDao, GroupMessageDao groupmessageDao) {
        this.socket = socket;
        this.clients = clients;
        this.online = online;
        this.messageDao = messageDao;
        this.fileDao = fileDao;
        this.groupDao = groupDao;
        this.groupMessageDao = groupmessageDao;
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
                    case AUDIO_META, AUDIO_CHUNK -> handleFile(f); // tương thích ngược

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
                    case CREATE_GROUP -> handleCreateGroup(f);
                    case ADD_MEMBER -> handleAddMember(f);
                    case REMOVE_MEMBER -> handleRemoveMember(f);
                    case DELETE_GROUP -> handleDeleteGroup(f);
                    case LIST_MEMBERS -> handleListMember(f);
                    case GROUP_MSG -> handleGroupMessage(f);
                    case GROUP_HISTORY -> handleGroupHistory(f);

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
            // DAO giờ trả về List<Message>
            List<Message> pending = messageDao.loadQueued(username);
            for (Message m : pending) {
                String bodyWithReply = prependReplyTag(
                        m.getBody() == null ? "" : m.getBody(),
                        m.getReplyTo()
                );
                Frame dm = new Frame(
                        MessageType.DM,
                        m.getSender(),
                        m.getRecipient(),
                        bodyWithReply
                );
                dm.transferId = String.valueOf(m.getId());
                sendFrame(dm);
            }
            if (!pending.isEmpty()) {
                sendFrame(Frame.ack("Delivered " + pending.size() + " offline messages"));
            }
        } catch (SQLException e) {
            sendFrame(Frame.error("OFFLINE_DELIVERY_FAIL"));
        }

        // 🔥 NEW: gửi danh sách group user đang ở
        sendGroupListToClient();
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
            requestSmartReply(String.valueOf(id), username, to, f.body);
        } catch (SQLException e) {
            sendFrame(Frame.error("DM_SAVE_FAIL"));
        }
    }
    
    // === SMART REPLY ===
    private void handleSmartReply(Frame f) {
        // Dự phòng nếu bạn muốn client gửi SMART_REPLY (chưa dùng)
        System.out.println("[SERVER] Received SMART_REPLY frame (no-op): " + f.body);
    }

    // === CALL FASTAPI SMART-REPLY BACKEND ===
    private void requestSmartReply(String conversationId, String sender, String recipient, String lastMessage) {
        try {
            // ✅ Tạo payload JSON gọn gàng
            String escapedMsg = (lastMessage == null ? "" : lastMessage.replace("\"", "\\\""));
            String payload = """
            {
                "conversation_id": "%s",
                "messages": [
                    {"role": "A", "text": "%s"}
                ],
                "n": 3,
                "max_words": 12,
                "lang": "vi"
            }
            """.formatted(conversationId, escapedMsg);

            System.out.println("[SMART-REPLY] Request payload: " + payload);

            // ✅ Gọi FastAPI /smart-reply
            var url = new java.net.URL("http://localhost:8000/smart-reply");
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                System.err.println("[SMART-REPLY] API returned status: " + status);
                return;
            }

            // ✅ Đọc phản hồi JSON từ FastAPI
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            String resp = sb.toString();
            System.out.println("[SMART-REPLY] Response: " + resp);

            if (resp.contains("suggestions")) {
                // Gửi Frame SMART_REPLY tới client nhận
                Frame replyFrame = new Frame(MessageType.SMART_REPLY, "system", recipient, resp);
                ClientHandler target = online.get(recipient);
                if (target != null) {
                    target.sendFrame(replyFrame);
                    System.out.println("[SMART-REPLY] Sent SMART_REPLY to " + recipient);
                } else {
                    System.out.println("[SMART-REPLY] Recipient " + recipient + " is offline");
                }
            }

        } catch (Exception e) {
            System.err.println("[SMART-REPLY] failed: " + e.getMessage());
        }
    }
    /* ================= EDIT ================= */
    private void handleEditMessage(Frame f) {
        try {
            long id = parseLongSafe(f.transferId != null ? f.transferId : f.body, 0L);
            if (id <= 0) { sendFrame(Frame.error("BAD_ID")); return; }
            String newBodyRaw = (f.body == null) ? "" : f.body;

            // 1) thử edit trong bảng DM
            String peer = messageDao.updateByIdReturningPeer(id, username, newBodyRaw);
            if (peer != null) {
                Frame ack = Frame.ack("OK EDIT"); ack.transferId = String.valueOf(id); sendFrame(ack);

                Frame evt = new Frame(MessageType.EDIT_MSG, username, peer, newBodyRaw);
                evt.transferId = String.valueOf(id);
                ClientHandler peerHandler = online.get(peer);
                if (peerHandler != null) peerHandler.sendFrame(evt);
                return;
            }

            // 2) nếu không phải DM => thử group
            Integer groupId = groupMessageDao.updateByIdReturningGroup(id, username, newBodyRaw);
            if (groupId == null) {
                sendFrame(Frame.error("DENIED_OR_NOT_FOUND"));
                return;
            }

            // ACK cho sender
            Frame ack = Frame.ack("OK GROUP_EDIT");
            ack.transferId = String.valueOf(id);
            sendFrame(ack);

            // Broadcast cho members
            List<String> members = groupDao.listMembers(groupId);
            for (String m : members) {
                ClientHandler target = online.get(m);
                if (target != null && !m.equals(username)) {
                    Frame evt = new Frame(
                            MessageType.EDIT_MSG,
                            username,
                            String.valueOf(groupId),
                            newBodyRaw
                    );
                    evt.transferId = String.valueOf(id);
                    target.sendFrame(evt);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendFrame(Frame.error("EDIT_FAIL"));
        }
    }

    /* ================= SEARCH ================= */
    private void handleSearch(Frame f) {
        String peer = f.recipient;
        String q = jsonGet(f.body, "q");
        if (q == null) q = "";
        int limit = (f.seq > 0) ? f.seq : 50;
        int offset = (int) parseLongSafe(jsonGet(f.body, "offset"), 0);

        try {
            List<Message> rows = messageDao.searchConversation(username, peer, q, limit, offset);
            for (Message m : rows) {
                String plain = (m.getBody() == null) ? "" : m.getBody();
                String bodyWithReply = prependReplyTag(plain, m.getReplyTo());

                Frame hit = new Frame(
                        MessageType.SEARCH_HIT,
                        m.getSender(),
                        m.getRecipient(),
                        bodyWithReply
                );
                hit.transferId = String.valueOf(m.getId());
                sendFrame(hit);
            }
            sendFrame(Frame.ack("OK SEARCH " + rows.size()));
        } catch (Exception e) {
            sendFrame(Frame.error("SEARCH_FAIL"));
        }
    }

    /* ================= HISTORY ================= */
    private void handleHistory(Frame f) {
        String peer = f.recipient;
        int limit = 50;
        try { limit = Integer.parseInt(f.body); } catch (Exception ignore) {}

        try {
            List<Message> rows = messageDao.loadConversation(username, peer, limit);
            for (Message m : rows) {
                boolean incoming = !username.equals(m.getSender());
                String plain = (m.getBody() == null) ? "" : m.getBody();
                String bodyWithReply = prependReplyTag(plain, m.getReplyTo());

                String txt = incoming
                        ? "[HIST IN] " + m.getSender() + ": " + bodyWithReply
                        : "[HIST OUT] " + bodyWithReply;

                Frame hist = new Frame(
                        MessageType.HISTORY,
                        m.getSender(),
                        m.getRecipient(),
                        txt
                );
                hist.transferId = String.valueOf(m.getId());
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

                if (upOut != null) { try { upOut.close(); } catch (Exception ignore) {} }
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
                    upOut.flush();
                    upOut.close();
                    upOut = null;

                    long msgId = 0L;
                    long fileId = 0L;

                    try {
                        String fileBody = "[FILE] " + upOrigName;

                        // 1) Nhận diện group hay DM
                        boolean isGroup = false;
                        int groupId = -1;

                        if (upToUser != null) {
                            String t = upToUser.trim();

                            if (t.startsWith("group:")) {
                                try {
                                    groupId = Integer.parseInt(t.substring("group:".length()));
                                    isGroup = true;
                                } catch (NumberFormatException ignore) {}
                            } else if (t.matches("\\d+")) {
                                try {
                                    int gid = Integer.parseInt(t);
                                    // nếu user hiện tại là member của gid → xem như gửi file group
                                    if (groupDao != null && groupDao.isMember(gid, username)) {
                                        groupId = gid;
                                        isGroup = true;
                                    }
                                } catch (NumberFormatException ignore) {}
                            }
                        }

                        // 2) Lưu message
                        if (isGroup) {
                            // lưu vào bảng message group
                            msgId = groupMessageDao.saveMessage(groupId, username, fileBody, upReplyTo);
                        } else {
                            // DM như cũ
                            msgId = messageDao.saveSentReturnId(
                                    username,
                                    upToUser,
                                    fileBody,
                                    upReplyTo
                            );
                        }

                        // 3) Lưu file gắn với msgId
                        String filePath = new File(UPLOAD_DIR, sanitizeFilename(upFileId)).getAbsolutePath();
                        fileId = fileDao.save(msgId, upOrigName, filePath, upMime, upWritten);

                        System.out.println("[FILE/SAVE] sender=" + username
                                + " to=" + upToUser
                                + " isGroup=" + isGroup
                                + " groupId=" + groupId
                                + " msgId=" + msgId
                                + " fileId=" + fileId
                                + " origName=" + upOrigName
                                + " mime=" + upMime
                                + " bytes=" + upWritten
                                + " replyTo=" + upReplyTo);

                        if (fileId > 0) uuidToFileId.put(upFileId, fileId);
                        if (msgId  > 0) uuidToMsgId.put(upFileId, msgId);

                        // 4) ACK cho sender (giữ nguyên)
                        String ackJson = "{"
                                + "\"status\":\"FILE_SAVED\","
                                + "\"messageId\":" + msgId + ","
                                + "\"fileId\":" + fileId + ","
                                + "\"bytes\":" + upWritten + ","
                                + "\"mime\":\"" + escJson(upMime) + "\""
                                + "}";
                        Frame ack = Frame.ack(ackJson);
                        ack.transferId = upFileId;
                        sendFrame(ack);

                        // 5) Broadcast realtime
                        if (isGroup && groupId > 0) {
                            // Gửi cho tất cả member online trong group
                            List<String> members = groupDao.listMembers(groupId);
                            if (members != null) {
                                String json = "{"
                                        + "\"from\":\"" + escJson(username) + "\","
                                        + "\"to\":\"group:" + groupId + "\","
                                        + "\"uuid\":\"" + escJson(upFileId) + "\","
                                        + "\"id\":\""   + escJson(upFileId) + "\","
                                        + "\"fileId\":" + fileId + ","
                                        + "\"messageId\":" + msgId + ","
                                        + "\"replyTo\":" + (upReplyTo == null ? "null" : upReplyTo) + ","
                                        + "\"name\":\"" + escJson(upOrigName) + "\","
                                        + "\"mime\":\"" + escJson(upMime) + "\","
                                        + "\"bytes\":"  + upWritten
                                        + "}";

                                for (String m : members) {
                                    if (m.equals(username)) continue; // sender tự render local
                                    ClientHandler target = online.get(m);
                                    if (target != null) {
                                        Frame evt = new Frame(
                                                MessageType.FILE_EVT,
                                                username,
                                                String.valueOf(groupId), // recipient = groupId
                                                json
                                        );
                                        System.out.println("[FILE/EVT][GROUP] push to member="
                                                + m + " groupId=" + groupId
                                                + " fileId=" + fileId
                                                + " msgId=" + msgId);
                                        target.sendFrame(evt);
                                    }
                                }
                            }
                        } else if (upToUser != null && !upToUser.isBlank()) {
                            // DM cũ giữ nguyên
                            ClientHandler target = online.get(upToUser);
                            if (target != null) {
                                String json = "{"
                                        + "\"from\":\"" + escJson(username) + "\","
                                        + "\"to\":\""   + escJson(upToUser) + "\","
                                        + "\"uuid\":\"" + escJson(upFileId) + "\","
                                        + "\"id\":\""   + escJson(upFileId) + "\","
                                        + "\"fileId\":" + fileId + ","
                                        + "\"messageId\":" + msgId + ","
                                        + "\"replyTo\":" + (upReplyTo == null ? "null" : upReplyTo) + ","
                                        + "\"name\":\"" + escJson(upOrigName) + "\","
                                        + "\"mime\":\"" + escJson(upMime) + "\","
                                        + "\"bytes\":"  + upWritten
                                        + "}";
                                Frame evt = new Frame(MessageType.FILE_EVT, username, upToUser, json);
                                System.out.println("[FILE/EVT][DM] push to=" + upToUser
                                        + " fileId=" + fileId
                                        + " msgId=" + msgId);
                                target.sendFrame(evt);
                            }
                        }

                    } catch (SQLException sqle) {
                        System.err.println("[DB] Failed to save file metadata: " + sqle.getMessage());
                    }

                    // 6) Reset state
                    upFileId = null;
                    upToUser = null;
                    upOrigName = null;
                    upMime = null;
                    upDeclaredSize = 0;
                    upExpectedSeq = 0;
                    upWritten = 0;
                    upReplyTo = null;
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

            if (fileIdStr != null && !fileIdStr.isBlank()) {
                try { fileId = Long.parseLong(fileIdStr); } catch (Exception ignore) {}
            }
            if (msgIdStr != null && !msgIdStr.isBlank()) {
                try { messageId = Long.parseLong(msgIdStr); } catch (Exception ignore) {}
            }

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
                if (messageId == null && mappedMsgId != null) messageId = mappedMsgId;
            }

            System.out.println("[DL] resolved fileId=" + fileId + " messageId=" + messageId + " legacyUuid=" + uuid);

            FileResource fileRow = null;
            if (fileId != null && fileId > 0) fileRow = fileDao.getById(fileId);
            if (fileRow == null && messageId != null && messageId > 0) fileRow = fileDao.getByMessageId(messageId);

            if (fileRow == null) {
                System.out.println("[DL] fileRow=null (not found by fileId/messageId)");
                sendFrame(Frame.error("INVALID_FILE_ID"));
                return;
            }

            long frId        = fileRow.getId();
            long frMsgId     = fileRow.getMessageId();
            String frName    = fileRow.getFileName();
            String frPath    = fileRow.getFilePath();
            String frMime    = fileRow.getMimeType();
            long frSize      = fileRow.getFileSize();

            System.out.println("[DL] fileRow id=" + frId
                    + " msgId=" + frMsgId
                    + " name=" + frName
                    + " mime=" + frMime
                    + " path=" + frPath);

            File file = new File(frPath);
            if (!file.exists()) {
                sendFrame(Frame.error("FILE_NOT_FOUND_DISK"));
                return;
            }

            String mime = (frMime != null) ? frMime : "application/octet-stream";
            String name = (frName != null) ? frName : ("file-" + frId);

            Long replyTo = null;
            try {
                if (frMsgId > 0) {
                    replyTo = messageDao.getReplyToByMessageId(frMsgId);
                }
                System.out.println("[DL] replyTo for messageId=" + frMsgId + " -> " + replyTo);
            } catch (SQLException e) {
                System.out.println("[DL] getReplyToByMessageId failed: " + e.getMessage());
            }

            String metaJson = "{"
                    + "\"from\":\"" + escJson(username) + "\","
                    + "\"to\":\"\","
                    + "\"name\":\"" + escJson(name) + "\","
                    + "\"mime\":\"" + escJson(mime) + "\","
                    + "\"fileId\":\"" + frId + "\","
                    + "\"messageId\":\"" + frMsgId + "\","
                    + "\"replyTo\":" + (replyTo == null ? "null" : replyTo) + ","
                    + "\"size\":" + file.length()
                    + "}";

            System.out.println("[DL] send FILE_META fileId=" + frId
                    + " msgId=" + frMsgId
                    + " replyTo=" + replyTo
                    + " size=" + file.length());

            sendFrame(new Frame(MessageType.FILE_META, username, "", metaJson));

            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[Frame.CHUNK_SIZE];
                int n, seq = 0;
                long rem = file.length();
                while ((n = fis.read(buf)) != -1) {
                    if (seq == 0) {
                        System.out.println("[DL] start streaming fileId=" + frId + " total=" + file.length());
                    }
                    rem -= n;
                    boolean last = (rem == 0);
                    byte[] slice = (n == buf.length) ? buf : Arrays.copyOf(buf, n);

                    Frame ch = new Frame(MessageType.FILE_CHUNK, username, "", "");
                    ch.transferId = String.valueOf(frId);
                    ch.seq = seq++;
                    ch.last = last;
                    ch.bin = slice;
                    sendFrame(ch);

                    if (last) {
                        System.out.println("[DL] done streaming fileId=" + frId + " chunks=" + (seq));
                    }
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
            List<FileResource> rows = fileDao.listByUserAndPeer(username, peer, limit, offset);
            for (FileResource r : rows) {
                String jsonBody = String.format(
                    "{\"file_name\":\"%s\",\"mime_type\":\"%s\",\"file_size\":%d,\"file_path\":\"%s\"}",
                    escJson(r.getFileName()),
                    escJson(r.getMimeType()),
                    r.getFileSize(),
                    escJson(r.getFilePath())
                );
                Frame hist = new Frame(MessageType.FILE_HISTORY, username, peer, jsonBody);
                hist.transferId = String.valueOf(r.getId());
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
        if (msgId <= 0) {
            sendFrame(Frame.error("INVALID_FILE_ID"));
            return;
        }

        try {
            FileResource row = fileDao.getByMessageId(msgId);
            boolean deleted = fileDao.deleteByMessageId(msgId);
            if (deleted) {
                if (row != null) {
                    File onDisk = new File(row.getFilePath());
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
            long id = parseLongSafe(f.transferId != null ? f.transferId : f.body, 0L);
            if (id <= 0) { sendFrame(Frame.error("BAD_ID")); return; }

            // 1) thử delete trong DM
            String peer = messageDao.deleteByIdReturningPeer(id, username);
            if (peer != null) {
                // xoá file DM nếu có
                try {
                    FileResource fileRow = fileDao.getByMessageId(id);
                    if (fileRow != null) {
                        fileDao.deleteByMessageId(id);
                        File toDelete = new File(fileRow.getFilePath());
                        if (toDelete.exists()) toDelete.delete();
                    }
                } catch (SQLException ignore) {}

                Frame ack = Frame.ack("OK DELETE");
                ack.transferId = String.valueOf(id);
                sendFrame(ack);

                ClientHandler peerHandler = online.get(peer);
                if (peerHandler != null) {
                    Frame evt = new Frame(MessageType.DELETE_MSG, username, peer, "");
                    evt.transferId = String.valueOf(id);
                    peerHandler.sendFrame(evt);
                }
                return;
            }

            // 2) nếu không phải DM => group
            Integer groupId = groupMessageDao.deleteByIdReturningGroup(id, username);
            if (groupId == null) {
                sendFrame(Frame.error("DENIED_OR_NOT_FOUND"));
                return;
            }

            Frame ack = Frame.ack("OK GROUP_DELETE");
            ack.transferId = String.valueOf(id);
            sendFrame(ack);

            // broadcast tới các member
            List<String> members = groupDao.listMembers(groupId);
            for (String m : members) {
                ClientHandler target = online.get(m);
                if (target != null && !m.equals(username)) {
                    Frame evt = new Frame(
                            MessageType.DELETE_MSG,
                            username,
                            String.valueOf(groupId),
                            ""
                    );
                    evt.transferId = String.valueOf(id);
                    target.sendFrame(evt);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendFrame(Frame.error("DELETE_FAIL"));
        }
    }

    /* GROUP */
    /* ================= CREATE GROUP ================= */
    private void handleCreateGroup(Frame f) {
        try {
            String name = (f.body == null || f.body.isBlank()) ? "Unnamed Group" : f.body.trim();
            int groupId = groupDao.createGroup(username, name);

            if (groupId > 0) {
            	String respJson = "{"
            	        + "\"status\":\"OK_GROUP_CREATED\","
            	        + "\"group_id\":" + groupId + ","
            	        + "\"name\":\"" + escJson(name) + "\","
            	        + "\"owner\":\"" + escJson(username) + "\""
            	        + "}";
            	    sendFrame(Frame.ack(respJson));
            } else {
                sendFrame(Frame.error("GROUP_CREATE_FAIL"));
            }
        } catch (SQLException e) {
        	e.printStackTrace();
            sendFrame(Frame.error("DB_ERROR_CREATE_GROUP"));
        }
    }

    /* ================= ADD MEMBER ================= */
    private void handleAddMember(Frame f) {
        try {
            String body = f.body;
            int groupId = Integer.parseInt(jsonGet(body, "group_id"));
            List<String> newMembers = extractArray(body, "members");

            if (newMembers == null || newMembers.isEmpty()) {
                sendFrame(Frame.error("NO_MEMBERS_PROVIDED"));
                return;
            }

            // 1. Permission: người đang gọi phải là owner
            boolean isOwner = groupDao.isOwner(groupId, username);
            boolean isMember = groupDao.isMember(groupId, username);

            if (!isOwner && !isMember) {
                // 🔧 Add helpful debug
                System.out.println("[DEBUG] Permission denied for user=" + username + " group=" + groupId);
                System.out.println("isOwner=" + isOwner + ", isMember=" + isMember);
                sendFrame(Frame.error("PERMISSION_DENIED_ADD"));
                return;
            }


            // 2. Add từng member
            List<String> actuallyAdded = new ArrayList<>();
            for (String m : newMembers) {
                if (groupDao.addMember(groupId, m)) {
                    actuallyAdded.add(m);

                    // nếu user vừa được thêm đang online
                    ClientHandler target = online.get(m);
                    if (target != null) {
                        // 2a. báo cho người đó biết họ đã được add
                        String notice = "{"
                                + "\"event\":\"ADDED_TO_GROUP\","
                                + "\"group_id\":"+groupId+","
                                + "\"by\":\""+escJson(username)+"\""
                                + "}";
                        Frame noticeFrame = Frame.ack(notice);
                        target.sendFrame(noticeFrame);

                        // 2b. đẩy lại danh sách group đầy đủ cho người đó
                        // để client của họ cập nhật sidebar ngay lập tức
                        target.sendGroupListToClient();
                    }
                }
            }

            // 3. Broadcast system message tới các thành viên hiện tại trong group
            if (!actuallyAdded.isEmpty()) {
                var membersNow = groupDao.listMembers(groupId);
                String addedJoined = String.join(",", actuallyAdded);

                for (String memberName : membersNow) {
                    ClientHandler h = online.get(memberName);
                    if (h != null) {
                        Frame sys = new Frame(
                            MessageType.GROUP_SYSTEM,
                            "system",
                            String.valueOf(groupId),
                            username + " added " + addedJoined + " to the group"
                        );
                        h.sendFrame(sys);
                    }
                }
            }

            // 4. ACK cho người gọi (owner – người bấm "Tạo nhóm"/"Thêm thành viên")
            Frame ack = Frame.ack("OK ADD_MEMBER " + actuallyAdded.size());
            sendFrame(ack);

        } catch (Exception e) {
            sendFrame(Frame.error("ADD_MEMBER_EXCEPTION"));
            e.printStackTrace();
        }
    }




    private void handleRemoveMember(Frame f) {
        try {
            String body = f.body;
            int groupId = Integer.parseInt(jsonGet(body, "group_id"));
            String targetUser = jsonGet(body, "username");

            if (targetUser == null || targetUser.isBlank()) {
                sendFrame(Frame.error("MISSING_MEMBER"));
                return;
            }

            boolean isOwnerLeaving = groupDao.isOwner(groupId, targetUser);

            // ✅ 1️⃣ Xóa thành viên khỏi nhóm
            boolean removed = groupDao.removeMember(groupId, targetUser);
            if (!removed) {
                sendFrame(Frame.error("REMOVE_MEMBER_FAIL"));
                return;
            }

            // ✅ 2️⃣ Nếu owner rời nhóm
            if (isOwnerLeaving) {
                List<String> remaining = groupDao.listMembersOrderByJoinTime(groupId);

                if (remaining == null || remaining.isEmpty()) {
                    // ❌ Không còn ai → xóa nhóm
                    groupDao.deleteGroup(groupId, targetUser);
                    sendFrame(Frame.ack("OK GROUP_DELETED_EMPTY"));
                    System.out.println("[GROUP] Owner left, no members left -> group deleted: " + groupId);
                    return;
                }

                // ✅ Còn thành viên khác → chọn người sớm nhất làm owner mới
                String newOwner = remaining.get(0);
                groupDao.updateOwner(groupId, newOwner);

                // Gửi thông báo nội bộ
                for (String m : remaining) {
                    ClientHandler h = online.get(m);
                    if (h != null) {
                        Frame notice = new Frame(
                            MessageType.GROUP_SYSTEM,
                            "system",
                            String.valueOf(groupId),
                            "👑 Owner " + targetUser + " rời nhóm — owner mới là " + newOwner
                        );
                        h.sendFrame(notice);
                    }
                }

                sendFrame(Frame.ack("OK OWNER_CHANGED_TO " + newOwner));
                return;
            }

            // ✅ 3️⃣ Nếu là member bình thường tự rời
            sendFrame(Frame.ack("OK MEMBER_LEFT " + targetUser));

            // Thông báo cho các thành viên còn lại
            List<String> remaining = groupDao.listMembers(groupId);
            for (String m : remaining) {
                ClientHandler h = online.get(m);
                if (h != null) {
                    Frame sys = new Frame(
                        MessageType.GROUP_SYSTEM,
                        "system",
                        String.valueOf(groupId),
                        targetUser + " đã rời nhóm."
                    );
                    h.sendFrame(sys);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendFrame(Frame.error("REMOVE_MEMBER_EXCEPTION"));
        }
    }


    private void handleDeleteGroup(Frame f) {
        try {
            int groupId = Integer.parseInt(f.body.trim());

            // lấy danh sách members TRƯỚC KHI XÓA
            var members = groupDao.listMembers(groupId);

            boolean ok = groupDao.deleteGroup(groupId, username);

            if (ok) {
                sendFrame(Frame.ack("OK GROUP_DELETED " + groupId));

                for (String m : members) {
                    if (m.equals(username)) continue;
                    ClientHandler target = online.get(m);
                    if (target != null) {
                        Frame sys = new Frame(
                            MessageType.GROUP_SYSTEM,
                            "system",
                            String.valueOf(groupId),
                            "Group " + groupId + " was deleted by owner " + username
                        );
                        target.sendFrame(sys);
                    }
                }
            } else {
                sendFrame(Frame.error("DELETE_GROUP_DENIED"));
            }
        } catch (Exception e) {
            sendFrame(Frame.error("DELETE_GROUP_FAIL"));
        }
    }

    /* ================= LIST MEMBERS (only owner) ================= */
    private void handleListMember(Frame f) {
    	try {
            int groupId = Integer.parseInt(f.body.trim());
            var members = groupDao.listMembers(groupId);

            if (members == null || members.isEmpty()) {
                sendFrame(Frame.error("NO_MEMBERS_IN_GROUP"));
                return;
            }

            // Gộp danh sách thành JSON gọn để client dễ parse
            StringBuilder sb = new StringBuilder();
            sb.append("{\"group_id\":").append(groupId).append(",\"members\":[");

            for (int i = 0; i < members.size(); i++) {
                sb.append("\"").append(escJson(members.get(i))).append("\"");
                if (i < members.size() - 1) sb.append(",");
            }
            sb.append("]}");

            Frame resp = new Frame(MessageType.LIST_MEMBERS, "server", username, sb.toString());
            sendFrame(resp);

            // Gửi ACK cuối cùng
            sendFrame(Frame.ack("OK LIST_MEMBERS " + members.size()));

        } catch (SQLException e) {
            sendFrame(Frame.error("DB_LIST_MEMBER_FAIL"));
        } catch (Exception e) {
            sendFrame(Frame.error("LIST_MEMBER_FAIL"));
        }
    	
    }
 // gửi danh sách tất cả group mà user hiện tại (this.username) đang ở trong
    public void sendGroupListToClient() {
        try {
            List<common.Group> groups = groupDao.listGroupsForUser(username);

            for (common.Group g : groups) {
                // NOTE: common.Group trong GroupDao.listGroupsForUser(...) được tạo như:
                // new Group(rs.getInt("id"), rs.getString("name"), rs.getString("owner"))
                // => mình giả định class common.Group có getter kiểu getId(), getName(), getOwner()
                // Nếu bạn dùng record Group(int id, String name, String owner)
                // thì đổi g.getId() -> g.id(), v.v.

                int gid         = g.getId();
                String gname    = g.getName();
                String gowner   = g.getOwner();

                String json = "{"
                    + "\"group_id\":" + gid + ","
                    + "\"name\":\"" + escJson(gname) + "\","
                    + "\"owner\":\"" + escJson(gowner) + "\""
                    + "}";

                Frame out = new Frame(
                    MessageType.GROUP_LIST,
                    "server",
                    username,
                    json
                );
                sendFrame(out);
            }

            // báo cho client biết đã gửi xong list
            Frame done = Frame.ack("OK GROUP_LIST " + groups.size());
            sendFrame(done);

        } catch (SQLException e) {
            sendFrame(Frame.error("DB_GROUP_LIST_FAIL"));
        } catch (Exception e) {
            sendFrame(Frame.error("GROUP_LIST_FAIL"));
        }
    }

    /* ================= GROUP MESSAGE ================= */
    private void handleGroupMessage(Frame f) {
        try {
            System.out.println("[SERVER] handleGroupMessage f=" + f);

            if (f == null) throw new RuntimeException("Frame null");
            System.out.println("[SERVER] recipient=" + f.recipient + ", sender=" + username + ", body=" + f.body);

            int groupId = Integer.parseInt(f.recipient); // <== kiểm tra lỗi parse
            System.out.println("[SERVER] parsed groupId=" + groupId);

            String msgBodyRaw = (f.body == null) ? "" : f.body;
            StringBuilder buf = new StringBuilder(msgBodyRaw);
            Long replyTo = extractReplyIdAndStrip(buf);
            String msgBody = buf.toString().trim();
            System.out.println("[SERVER] msgBody='" + msgBody + "'");

            if (groupDao == null) throw new RuntimeException("groupDao is null");
            if (groupMessageDao == null) throw new RuntimeException("groupMessageDao is null");

            System.out.println("[SERVER] checking membership...");
            if (!groupDao.isMember(groupId, username)) {
                System.out.println("[SERVER] user not member of group");
                sendFrame(Frame.error("NOT_GROUP_MEMBER"));
                return;
            }

            System.out.println("[SERVER] saving message...");
            long msgId = groupMessageDao.saveMessage(groupId, username, msgBody, replyTo);
            System.out.println("[SERVER] message saved with id=" + msgId);

            var members = groupDao.listMembers(groupId);
            System.out.println("[SERVER] members=" + members);

            for (String m : members) {
                System.out.println("[SERVER] pushing to member " + m);
                if (m.equals(username)) continue;
                if (m.equals(username)) continue;
                ClientHandler target = online.get(m);
                if (target != null) {
                	Frame gf = new Frame(MessageType.GROUP_MSG, username, String.valueOf(groupId),
                	        prependReplyTag(msgBody, replyTo));
                	gf.transferId = String.valueOf(msgId);
                	target.sendFrame(gf);
                }
            }

            System.out.println("[SERVER] ack to sender");
            Frame ack = Frame.ack("OK GROUP_MSG_SENT");
            ack.transferId = String.valueOf(msgId);
            sendFrame(ack);

        } catch (SQLException e) {
            e.printStackTrace();
            sendFrame(Frame.error("DB_GROUP_MSG_FAIL"));
        } catch (Exception e) {
            e.printStackTrace();
            sendFrame(Frame.error("GROUP_MSG_FAIL"));
        }
    }

    /* ================= GROUP HISTORY ================= */
    private void handleGroupHistory(Frame f) {
        try {
            int groupId = Integer.parseInt(f.recipient.replace("group:", ""));
            int limit = 50;
            try { limit = Integer.parseInt(f.body); } catch (Exception ignore) {}

            if (!groupDao.isMember(groupId, username)) {
                sendFrame(Frame.error("NOT_GROUP_MEMBER"));
                return;
            }

            List<GroupMessage> messages = groupMessageDao.loadRecentMessages(groupId, limit);

            for (GroupMessage m : messages) {
                String bodyWithReply = prependReplyTag(m.getBody(), m.getReplyTo());
                String line = m.getSender() + ": " + (bodyWithReply == null ? "" : bodyWithReply);

                Frame hist = new Frame(
                        MessageType.GROUP_HISTORY,
                        m.getSender(),
                        String.valueOf(groupId),
                        line
                );
                hist.transferId = String.valueOf(m.getId()); // quan trọng
                sendFrame(hist);
            }


            sendFrame(Frame.ack("OK GROUP_HISTORY " + messages.size()));

        } catch (SQLException e) {
            e.printStackTrace();
            sendFrame(Frame.error("DB_GROUP_HISTORY_FAIL"));
        } catch (Exception e) {
            e.printStackTrace();
            sendFrame(Frame.error("GROUP_HISTORY_FAIL"));
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
    private static List<String> extractArray(String json, String key) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;
        int start = json.indexOf("\"" + key + "\"");
        if (start < 0) return list;
        start = json.indexOf('[', start);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) return list;
        String arr = json.substring(start + 1, end);
        for (String item : arr.split(",")) {
            item = item.replaceAll("[\"\\s]", "");
            if (!item.isEmpty()) list.add(item);
        }
        return list;
    }

}
