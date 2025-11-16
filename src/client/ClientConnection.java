package client;

import client.controller.MidController;
import client.signaling.CallSignalingService;
import common.Frame;
import common.FrameIO;
import common.MessageType;
import common.User;
import java.util.Base64;
import javafx.scene.layout.HBox;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class ClientConnection {
    private Socket socket;
    private DataInputStream binIn;
    private DataOutputStream binOut;
    private Thread readerThread;
    private CallSignalingService callService;
    private Consumer<Frame> onFrame;
    private Consumer<Exception> onError;
    private final ConcurrentHashMap<String, CompletableFuture<Frame>> pendingAcks = new ConcurrentHashMap<>();
    private MidController midController;
    private final ConcurrentHashMap<String, Long> fidToMsgId  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> fidToFileId = new ConcurrentHashMap<>();
    private final java.util.Set<Long> inFlightDownloads =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);

            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();
            binIn = new DataInputStream(new BufferedInputStream(rawIn));
            binOut = new DataOutputStream(new BufferedOutputStream(rawOut));
            return true;
        } catch (IOException e) {
            e.printStackTrace(); // THÊM DÒNG NÀY
            System.err.println("[CLIENT] connect() failed to " + host + ":" + port + " - " + e.getMessage());
            return false;
        }
    }

    
    public void markDownloadDone(String fid) {
        if (fid == null) return;
        try {
            long id = Long.parseLong(fid);
            inFlightDownloads.remove(id);
        } catch (NumberFormatException ignore) {
        }
    }

    public void markDownloadDone(long fileId) {
        inFlightDownloads.remove(fileId);
    }


    public boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
    }

    public void attachCallService(CallSignalingService s) { this.callService = s; }
    public void setMidController(MidController controller) {
        this.midController = controller;
    }

    public void downloadFile(String fileId) throws IOException {
        Frame req = new Frame(MessageType.DOWNLOAD_FILE, "", "", fileId);
        sendFrame(req);
    }
    
    // ==== USER LIST (for LeftController) ====
    public void requestUserList(String keyword) throws IOException {
        String body;
        if (keyword == null || keyword.isBlank()) {
            body = "{}";
        } else {
            body = "{\"q\":\"" + esc(keyword) + "\"}";
        }
        Frame f = new Frame(MessageType.USER_LIST_REQ, "", "", body);
        sendFrame(f);
    }

    public void startListener(Consumer<Frame> onFrame, Consumer<Exception> onError) {
        this.onFrame = onFrame;
        this.onError = onError;

        readerThread = new Thread(() -> {
            try {
                while (true) {
                    Frame f = FrameIO.read(binIn);
                    if (f == null) break;

                    if (callService != null && callService.tryHandleIncoming(f)) {
                        continue;
                    }

                    System.out.println("[NET] RECV type=" + f.type + " transferId=" + f.transferId + " body=" + f.body);

                    if (f.type == MessageType.ACK && f.transferId != null && !f.transferId.isEmpty()) {
                        String status = jsonGet(f.body, "status");
                        if ("FILE_SAVED".equals(status)) {
                            final String fid = f.transferId;
                            final long messageId = parseLongSafe(jsonGet(f.body, "messageId"), 0L);
                            final long fileId  = parseLongSafe(jsonGet(f.body, "fileId"), 0L);

                            if (midController != null) {
                                javafx.application.Platform.runLater(() -> {
                                    try {
                                        midController.onOutgoingFileSaved(fid, messageId, fileId);
                                    } catch (Exception uiEx) {
                                        System.err.println("[UI] onOutgoingFileSaved failed: " + uiEx.getMessage());
                                    }
                                });
                            }
                        }

                        CompletableFuture<Frame> fut = pendingAcks.remove(f.transferId);
                        if (fut != null) {
                            fut.complete(f);
                        }
                    }

                    if (f.type == MessageType.ERROR && f.transferId != null && !f.transferId.isEmpty()) {
                        CompletableFuture<Frame> fut = pendingAcks.remove(f.transferId);
                        if (fut != null) {
                            fut.completeExceptionally(new IOException(f.body));
                        }
                    }

                    if (this.onFrame != null) {
                        this.onFrame.accept(f);
                    }
                }

                if (this.onError != null) this.onError.accept(new EOFException("Server closed connection"));
            } catch (IOException e) {
                if (this.onError != null) this.onError.accept(e);
            }
        }, "server-listener");

        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    // ========== AUTH REGISTER/LOGIN (đi qua server) ==========

    /**
     * Gửi request đăng ký tài khoản lên server.
     * @return true nếu đăng ký OK, false nếu tên trùng / lỗi.
     */
    public boolean authRegister(String username, String password, byte[] avatarBytes, String avatarMime) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(esc(username)).append("\",")
          .append("\"password\":\"").append(esc(password)).append("\"");

        if (avatarBytes != null && avatarBytes.length > 0 &&
                avatarMime != null && !avatarMime.isBlank()) {
            String b64 = Base64.getEncoder().encodeToString(avatarBytes);	
            sb.append(",\"avatarMime\":\"").append(esc(avatarMime)).append("\",")
              .append("\"avatarBase64\":\"").append(b64).append("\"");
        }
        sb.append("}");

        Frame req = new Frame(MessageType.AUTH_REGISTER, username, "", sb.toString());
        sendFrame(req);
        binOut.flush();

        Frame resp = FrameIO.read(binIn);
        if (resp == null) throw new IOException("Server closed connection during register");

        if (resp.type == MessageType.ACK) {
            String status = jsonGet(resp.body, "status");
            // nếu server gửi {"status":"OK"} hoặc body rỗng thì coi như OK
            return status == null || "OK".equals(status);
        }
        if (resp.type == MessageType.ERROR) {
            System.err.println("[AUTH_REGISTER] ERROR: " + resp.body);
            return false;
        }
        return false;
    }

    /**
     * Gửi request đăng nhập lên server, nhận lại User (ít nhất có username).
     * @return User nếu đăng nhập OK, null nếu sai tài khoản/mật khẩu.
     */
    public User authLogin(String username, String password) throws IOException {
        String body = "{\"username\":\"" + esc(username) + "\",\"password\":\"" + esc(password) + "\"}";
        Frame req = new Frame(MessageType.AUTH_LOGIN, username, "", body);
        sendFrame(req);
        binOut.flush();

        Frame resp = FrameIO.read(binIn);
        if (resp == null) throw new IOException("Server closed connection during login");

        if (resp.type == MessageType.ERROR) {
            System.err.println("[AUTH_LOGIN] ERROR: " + resp.body);
            return null;
        }
        if (resp.type != MessageType.ACK) {
            System.err.println("[AUTH_LOGIN] Unexpected frame type: " + resp.type);
            return null;
        }

        String status = jsonGet(resp.body, "status");
        if (!"OK".equals(status)) {
            System.err.println("[AUTH_LOGIN] status != OK: " + resp.body);
            return null;
        }

        String uname = jsonGet(resp.body, "username");
        String finalName = (uname != null && !uname.isBlank()) ? uname : username;

        // 👇 LẤY ID TỪ JSON
        String idStr = jsonGet(resp.body, "id");
        int id = 0;
        if (idStr != null && !idStr.isBlank()) {
            try {
                id = (int) Long.parseLong(idStr);
            } catch (NumberFormatException ignore) {}
        }

        User u = new User();
        u.setId(id);               // 👈 QUAN TRỌNG: set id vào User
        u.setUsername(finalName);
        return u;
    }

    public void loginFrame(String username) throws IOException {
        sendFrame(new Frame(MessageType.LOGIN, username, "", ""));
    }

    public synchronized void sendFrame(Frame f) throws IOException {
        System.out.println("[DEBUG] sendFrame: type=" + f.type + ", transferId=" + f.transferId);
        FrameIO.write(binOut, f);
    }

    public void register(String username) throws IOException {
        sendFrame(Frame.register(username));
    }

    public void dm(String from, String to, String text) throws IOException {
        sendFrame(Frame.dm(from, to, text));
    }

    public void history(String from, String peer, int limit) throws IOException {
        Frame f = new Frame(MessageType.HISTORY, from, peer, String.valueOf(limit));
        sendFrame(f);
    }

    public void deleteMessage(long id) throws IOException {
        String from = "";
        if (midController != null && midController.getCurrentUser() != null) {
            from = midController.getCurrentUser().getUsername();
        }
        Frame f = new Frame(MessageType.DELETE_MSG, from, "", String.valueOf(id));
        f.transferId = String.valueOf(id);
        sendFrame(f);
    }

    public void editMessage(long id, String newBody) throws IOException {
        if (newBody == null) return;

        String from = "";
        if (midController != null && midController.getCurrentUser() != null) {
            from = midController.getCurrentUser().getUsername();
        }

        Frame f = new Frame(MessageType.EDIT_MSG, from, "", newBody);
        f.transferId = String.valueOf(id);
        sendFrame(f);
    }

    
    public void search(String from, String peer, String query, int limit, int offset) throws IOException {
        String q = (query == null) ? "" : query;
        String body = "{\"q\":\"" + q.replace("\\","\\\\").replace("\"","\\\"") + "\",\"offset\":" + Math.max(0, offset) + "}";
        Frame f = new Frame(common.MessageType.SEARCH, from, peer, body);
        f.seq = limit;
        sendFrame(f);
    }
    
    public void downloadFileByFileId(long fileId) throws IOException {
        // Guard chống gửi trùng
        if (!inFlightDownloads.add(fileId)) {
            System.out.println("[DOWNLOAD] skip duplicate request fileId=" + fileId);
            return;
        }
        Frame req = new Frame(MessageType.DOWNLOAD_FILE, "", "", "{\"fileId\":" + fileId + "}");
        sendFrame(req);
    }
    
    public void downloadFileByMsgId(long msgId) throws IOException {
        Frame req = new Frame(MessageType.DOWNLOAD_FILE, "", "", "{\"messageId\":" + msgId + "}");
        sendFrame(req);
    }

    public void downloadFileLegacy(String uuidOrLegacyId) throws IOException {
        Frame req = new Frame(MessageType.DOWNLOAD_FILE, "", "", "{\"id\":\"" + uuidOrLegacyId + "\"}");
        sendFrame(req);
    }

    public void sendFileHistoryRequest(String fromUser, String peerUsername, int limit, int offset) throws IOException {
        String body = String.format("{\"limit\":%d, \"offset\":%d}", limit, offset);
        Frame req = new Frame(MessageType.FILE_HISTORY, fromUser, peerUsername, body);
        sendFrame(req);
    }

    public synchronized Frame sendFileWithAck(String from, String to, File file, String mimeOrNull, String fileId, long timeoutMs)
            throws Exception {
        int retries = 3;
        Exception lastEx = null;

        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                ensureSendableFile(file);

                String mime = (mimeOrNull != null && !mimeOrNull.isBlank()) ? mimeOrNull : guessMime(file);
                String durationVal = "--:--";
                if (mime != null && (mime.equals("audio/wav") || mime.equals("audio/x-wav")
                        || mime.equals("audio/aiff") || mime.equals("audio/x-aiff"))) {
                    try {
                        var aff = javax.sound.sampled.AudioSystem.getAudioFileFormat(file);
                        long frames = aff.getFrameLength();
                        float frameRate = aff.getFormat().getFrameRate();
                        if (frames > 0 && frameRate > 0) {
                            int sec = (int) ((frames / frameRate));
                            durationVal = String.format("%d:%02d", sec / 60, sec % 60);
                        }
                    } catch (Exception ignored) { }
                }

                final String fFrom = from;
                final String fTo = to;
                final String fFileId = fileId;
                final String fMime = (mime == null ? "application/octet-stream" : mime);
                final String fName = file.getName();
                final long  fSize = file.length();
                final String fDuration = durationVal;
                final String localUrl = file.toURI().toString();

                if (midController != null) {
                    javafx.application.Platform.runLater(() -> {
                        try { midController.showOutgoingFile(fName, fMime, fSize, fFileId, fDuration); }
                        catch (Exception uiEx) { System.err.println("[UI] showOutgoingFile failed: " + uiEx.getMessage()); }
                    });
                }

                CompletableFuture<Frame> fut = new CompletableFuture<>();
                pendingAcks.put(fFileId, fut);

             // ★ CHANGED: TẠO META THỦ CÔNG + prepend [REPLY:...] nếu có
                Long replyTo = currentReplyToIdFromUI();
                String metaJson = "{"
                        + "\"to\":\""   + esc(fTo)   + "\","
                        + "\"name\":\"" + esc(fName) + "\","
                        + "\"mime\":\"" + esc(fMime) + "\","
                        + "\"fileId\":\"" + esc(fFileId) + "\","
                        + "\"size\":" + fSize
                        + "}";

                // KHÔNG có ký tự nào trước “[REPLY:...]”
                String wireBody = (replyTo != null && replyTo > 0)
                        ? "[REPLY:" + replyTo + "]" + metaJson
                        : metaJson;

                // Thay vì Frame.fileMeta(...), tạo frame trực tiếp để giữ nguyên body
                Frame meta = new Frame(common.MessageType.FILE_META, fFrom, fTo, wireBody);
                meta.transferId = fFileId;
                sendFrame(meta);

                try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buf = new byte[Frame.CHUNK_SIZE];
                    int n, seq = 0;
                    long rem = fSize;
                    while ((n = fis.read(buf)) != -1) {
                        rem -= n;
                        boolean last = (rem == 0);
                        byte[] slice = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                        Frame ch = Frame.fileChunk(fFrom, fTo, fFileId, seq++, last, slice);
                        sendFrame(ch);
                    }
                }

                Frame ack = fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                if (midController != null) {
                    javafx.application.Platform.runLater(() -> {
                        try {
                            HBox row = midController.outgoingFileBubbles.get(fFileId);
                            if (row != null) {
                                boolean isAudio = fMime.startsWith("audio/");
                                boolean isVideo = fMime.startsWith("video/");
                                boolean isImage = fMime.startsWith("image/");
                                if (isAudio) {
                                    midController.updateVoiceBubbleFromUrl(row, localUrl);
                                } else if (isVideo) {
                                    midController.updateVideoBubbleFromUrl(row, localUrl);
                                } else if (isImage) {
                                    midController.updateImageBubbleFromUrl(row, localUrl);
                                }
                            }
                        } catch (Exception uiEx) {
                            System.err.println("[UI] finalize bubble failed: " + uiEx.getMessage());
                        }
                    });
                }

                return ack;

            } catch (java.util.concurrent.TimeoutException te) {
                pendingAcks.remove(fileId);
                lastEx = te;
                System.err.println("[RETRY] Attempt " + (attempt + 1) + " timed out");
                Thread.sleep(1000);
            } catch (IOException ioex) {
                lastEx = ioex;
                System.err.println("[RETRY] Attempt " + (attempt + 1) + " failed: " + ioex.getMessage());
                Thread.sleep(1000);
            }
        }

        throw lastEx != null ? lastEx : new IOException("Failed to send file after " + retries + " attempts");
    }

    public synchronized void sendAudio(String from, String to, byte[] audioBytes, String codec, int sampleRate, int durationSec)
            throws IOException {
        if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
        if (durationSec > Frame.MAX_AUDIO_SECONDS) throw new IOException("Audio too long (>30s)");

        String audioId = java.util.UUID.randomUUID().toString();

     // ★ CHANGED: META thủ công + prepend [REPLY:...]
        Long replyTo = currentReplyToIdFromUI();
        String metaJson = "{"
                + "\"to\":\"" + esc(to) + "\","
                + "\"codec\":\"" + esc(codec) + "\","
                + "\"sampleRate\":" + sampleRate + ","
                + "\"duration\":" + durationSec + ","
                + "\"fileId\":\"" + esc(audioId) + "\","
                + "\"size\":" + audioBytes.length
                + "}";

        String wireBody = (replyTo != null && replyTo > 0)
                ? "[REPLY:" + replyTo + "]" + metaJson
                : metaJson;

        Frame meta = new Frame(common.MessageType.AUDIO_META, from, to, wireBody);
        meta.transferId = audioId;
        sendFrame(meta);

        int off = 0, seq = 0;
        while (off < audioBytes.length) {
            int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
            boolean last = (off + len) >= audioBytes.length;
            byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
            Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
            sendFrame(ch);
            off += len;
        }
    }

    public synchronized Frame sendAudioWithAck(String from, String to, byte[] audioBytes, String codec, int sampleRate, int durationSec, long timeoutMs)
            throws Exception {
        int retries = 3;
        Exception lastEx = null;
        String audioId = null;
        for (int i = 0; i < retries; i++) {
            try {
                if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
                if (durationSec > Frame.MAX_AUDIO_SECONDS) throw new IOException("Audio too long (>30s)");

                audioId = java.util.UUID.randomUUID().toString();
                CompletableFuture<Frame> fut = new CompletableFuture<>();
                pendingAcks.put(audioId, fut);

                Long replyTo = currentReplyToIdFromUI();
                String metaJson = "{"
                        + "\"to\":\"" + esc(to) + "\","
                        + "\"codec\":\"" + esc(codec) + "\","
                        + "\"sampleRate\":" + sampleRate + ","
                        + "\"duration\":" + durationSec + ","
                        + "\"fileId\":\"" + esc(audioId) + "\","
                        + "\"size\":" + audioBytes.length
                        + "}";

                String wireBody = (replyTo != null && replyTo > 0)
                        ? "[REPLY:" + replyTo + "]" + metaJson
                        : metaJson;

                Frame meta = new Frame(common.MessageType.AUDIO_META, from, to, wireBody);
                meta.transferId = audioId;
                sendFrame(meta);

                int off = 0, seq = 0;
                while (off < audioBytes.length) {
                    int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
                    boolean last = (off + len) >= audioBytes.length;
                    byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
                    Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
                    sendFrame(ch);
                    off += len;
                }

                Frame ack = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (midController != null) {
                }
                return ack;
            } catch (IOException e) {
                lastEx = e;
                System.err.println("[RETRY] Attempt " + (i + 1) + " failed: " + e.getMessage());
                Thread.sleep(1000);
                continue;
            } catch (TimeoutException te) {
                pendingAcks.remove(audioId);
                if (midController != null) {
                }
                throw te;
            }
        }
        if (midController != null) {
        }
        throw lastEx != null ? lastEx : new IOException("Failed to send audio after " + retries + " attempts");
    }
    public void groupHistory(String username, String groupId, int limit) throws IOException {
        Frame f = new Frame(MessageType.GROUP_HISTORY, username, "group:" + groupId, String.valueOf(limit));
        sendFrame(f);
    }

    private static void ensureSendableFile(File file) throws IOException {
        if (file == null || !file.exists()) throw new FileNotFoundException("File not found");
        if (file.length() > Frame.MAX_FILE_BYTES) throw new IOException("File too large (>25MB)");
    }

    public static String guessMime(File f) {
        try {
            String m = Files.probeContentType(f.toPath());
            if (m != null && !m.isBlank()) {
                System.out.println("[DEBUG] Mime from probe: " + m);
                return m;
            }
        } catch (IOException e) {
            System.err.println("[DEBUG] Probe failed: " + e.getMessage());
        }
        String name = f.getName().toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
            return "image/" + name.substring(name.lastIndexOf('.') + 1);
        }
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a") || name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp4") || name.endsWith(".mov")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        System.out.println("[DEBUG] Fallback mime: application/octet-stream");
        return "application/octet-stream";
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
    private static long parseLongSafe(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
    /*REPLY*/
    // ★ NEW: escape cho JSON chuỗi đơn giản
       private static String esc(String s) {
           if (s == null) return "";
           return s.replace("\\", "\\\\").replace("\"", "\\\"");
       }

       // ★ NEW: lấy replyTo hiện tại từ UI (MidController -> replyingRow)
       private Long currentReplyToIdFromUI() {
           if (midController == null) return null;
           try {
               // MidController đã có hasReplyContext() + getReplyingRow()
               if (!midController.hasReplyContext()) return null;
               HBox replyingRow = midController.getReplyingRow();
               if (replyingRow == null) return null;

               // Ưu tiên messageId (userData)
               Object ud = replyingRow.getUserData();
               if (ud != null) {
                   try { return Long.parseLong(String.valueOf(ud)); } catch (Exception ignore) {}
               }
               // fallback: nếu file bubble, thử lấy từ "fid"
               Object fidProp = replyingRow.getProperties().get("fid");
               if (fidProp != null) {
                   try { return Long.parseLong(String.valueOf(fidProp)); } catch (Exception ignore) {}
               }
               // fallback 2: nếu chip reply đã lưu sẵn "replyTo"
               Object chip = replyingRow.getProperties().get("replyTo");
               if (chip != null) {
                   try { return Long.parseLong(String.valueOf(chip)); } catch (Exception ignore) {}
               }
           } catch (Exception ignore) {}
           return null;
       }
}