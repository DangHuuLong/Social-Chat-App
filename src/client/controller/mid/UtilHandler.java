package client.controller.mid;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class UtilHandler {
	private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm"); // hoặc "HH:mm dd/MM"

    public static String formatTime(long millis) {
        if (millis <= 0) return "";
        Instant ins = Instant.ofEpochMilli(millis);
        // Dùng đúng múi giờ máy client (VN thì systemDefault sẽ là +7)
        ZonedDateTime zdt = ins.atZone(ZoneId.systemDefault());
        return zdt.format(TIME_FMT);
    }
    public static String humanize(String iso, boolean withDot) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            var d = java.time.Duration.between(t, Instant.now());
            long m = d.toMinutes();
            String p;
            if (m < 1) p = "just now";
            else if (m < 60) p = m + "m ago";
            else {
                long h = m / 60;
                p = (h < 24) ? (h + "h ago") : ((h / 24) + "d ago");
            }
            return withDot ? " • " + p : p;
        } catch (Exception e) {
            return withDot ? " • " + iso : iso;
        }
    }

    public static String humanBytes(long v) {
        if (v <= 0) return "0 B";
        final String[] u = {"B","KB","MB","GB","TB"};
        int i = 0;
        double d = v;
        while (d >= 1024 && i < u.length - 1) { d /= 1024.0; i++; }
        return (d >= 10 ? String.format("%.0f %s", d, u[i]) : String.format("%.1f %s", d, u[i]));
    }

    public static String formatDuration(int sec) {
        if (sec <= 0) return "--:--";
        int m = sec / 60;
        int s = sec % 60;
        return String.format("%d:%02d", m, s);
    }

    public static MediaKind classifyMedia(String mime, String name) {
        String m = (mime == null ? "" : mime.toLowerCase());
        if (m.startsWith("image/")) return MediaKind.IMAGE;
        if (m.startsWith("audio/")) return MediaKind.AUDIO;
        if (m.startsWith("video/")) return MediaKind.VIDEO;

        String n = (name == null ? "" : name.toLowerCase());
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp"))
            return MediaKind.IMAGE;
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac"))
            return MediaKind.AUDIO;
        if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm"))
            return MediaKind.VIDEO;

        return MediaKind.FILE;
    }

    public static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;

        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;

        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length()) return null;

        char c = json.charAt(j);

        // ===== CHUỖI =====
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            boolean escape = false;

            for (int k = j + 1; k < json.length(); k++) {
                char ch = json.charAt(k);

                if (escape) {
                    switch (ch) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        default: sb.append(ch); break;
                    }
                    escape = false;
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    // kết thúc chuỗi
                    return sb.toString();
                } else {
                    sb.append(ch);
                }
            }
            // không tìm thấy dấu " đóng, trả về những gì đọc được
            return sb.toString();
        }

        // ===== SỐ (giữ nguyên behaviour cũ) =====
        int end = j;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        if (end == j) return null;
        return json.substring(j, end);
    }

    public static String guessExt(String mime, String fallbackName) {
        if (mime == null) return ".bin";
        mime = mime.toLowerCase(Locale.ROOT);
        if (mime.startsWith("audio/")) {
            if (mime.contains("mpeg")) return ".mp3";
            if (mime.contains("wav"))  return ".wav";
            if (mime.contains("ogg"))  return ".ogg";
            if (mime.contains("aac"))  return ".m4a";
            return ".audio";
        }
        if (mime.startsWith("video/")) {
            if (mime.contains("mp4"))  return ".mp4";
            if (mime.contains("webm")) return ".webm";
            if (mime.contains("matroska")) return ".mkv";
            return ".video";
        }
        if (mime.startsWith("image/")) {
            if (mime.contains("jpeg")) return ".jpg";
            if (mime.contains("png"))  return ".png";
            if (mime.contains("gif"))  return ".gif";
            if (mime.contains("bmp"))  return ".bmp";
            if (mime.contains("webp")) return ".webp";
            return ".img";
        }
        if (fallbackName != null && fallbackName.contains(".")) {
            return fallbackName.substring(fallbackName.lastIndexOf('.'));
        }
        return ".bin";
    }

    public static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    public static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    public enum MediaKind { IMAGE, AUDIO, VIDEO, FILE }
}
