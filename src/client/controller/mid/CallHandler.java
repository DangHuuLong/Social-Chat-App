package client.controller.mid;

import java.time.LocalTime;

import client.controller.MidController;
import client.controller.VideoCallController;
import client.media.CallOffer;
import client.media.LanAudioSession;
import client.media.LanVideoSession;
import client.media.LanVideoSession.OfferInfo;
import common.User;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class CallHandler {
    private final MidController controller;

    private Long callStartedAt = null;
    private boolean connectedOnce = false;
    private boolean logPosted = false;

    public CallHandler(MidController controller) {
        this.controller = controller;
    }

    public void callCurrentPeer() {
        if (controller.getCurrentPeerUser() == null) {
            System.out.println("[CALL] No peer selected");
            return;
        }
        startCallTo(controller.getCurrentPeerUser());
    }

    public void startCallTo(User peerUser) {
        String peer = peerUser.getUsername();
        String callId = java.util.UUID.randomUUID().toString();
        controller.setCurrentPeer(peer);
        controller.setCurrentCallId(callId);
        controller.setCaller(true);
        resetCallState();
        openCallWindow(peer, callId, VideoCallController.Mode.OUTGOING);
        controller.getCallSvc().sendInvite(peer, callId);
    }

    public void onInvite(String fromUser, String callId) {
        Platform.runLater(() -> {
            controller.setCurrentPeer(fromUser);
            controller.setCurrentCallId(callId);
            controller.setCaller(false);
            resetCallState();
            openCallWindow(fromUser, callId, VideoCallController.Mode.INCOMING);
        });
    }

    public void onAccept(String fromUser, String callId) {
        Platform.runLater(() -> {
            if (controller.isCaller() && controller.getCallCtrl() != null && fromUser.equals(controller.getCurrentPeer()) && callId.equals(controller.getCurrentCallId())) {
                try {
                    controller.setVideoSession(new LanVideoSession());
                    OfferInfo v = controller.getVideoSession().prepareCaller();
                    controller.setAudioSession(new LanAudioSession());
                    int aport = controller.getAudioSession().prepareCaller(v.host);
                    CallOffer offer = new CallOffer(v.host, v.port, aport);
                    controller.getCallSvc().sendOffer(controller.getCurrentPeer(), controller.getCurrentCallId(), offer.toJson());
                    controller.getVideoSession().startAsCaller(controller.getCallCtrl().getLocalView(), controller.getCallCtrl().getRemoteView());
                    controller.getAudioSession().startAsCaller();
                    controller.getCallCtrl().setMode(VideoCallController.Mode.CONNECTING);
                } catch (Exception e) {
                    e.printStackTrace();
                    controller.getCallSvc().sendEnd(controller.getCurrentPeer(), controller.getCurrentCallId());
                    closeCallWindow();
                }
            }
        });
    }

    public void onReject(String fromUser, String callId) {
        Platform.runLater(this::closeCallWindow);
    }
    public void onCancel(String fromUser, String callId) {
        Platform.runLater(this::closeCallWindow);
    }
    public void onBusy(String fromUser, String callId) {
        Platform.runLater(this::closeCallWindow);
    }
    public void onEnd(String fromUser, String callId) {
        Platform.runLater(this::closeCallWindow);
    }
    public void onOffline(String toUser, String callId) {
        Platform.runLater(this::closeCallWindow);
    }

    public void onOffer(String fromUser, String callId, String sdpJson) {
        Platform.runLater(() -> {
            try {
                if (!fromUser.equals(controller.getCurrentPeer()) || !callId.equals(controller.getCurrentCallId())) return;
                CallOffer offer = CallOffer.fromJson(sdpJson);
                controller.setVideoSession(new LanVideoSession());
                controller.getVideoSession().startAsCallee(offer.host, offer.vport, controller.getCallCtrl().getLocalView(), controller.getCallCtrl().getRemoteView());
                controller.setAudioSession(new LanAudioSession());
                controller.getAudioSession().startAsCallee(offer.host, offer.aport);
                controller.getCallSvc().sendAnswer(controller.getCurrentPeer(), controller.getCurrentCallId(), "{\"ok\":true}");
                controller.getCallCtrl().setMode(VideoCallController.Mode.CONNECTED);
                markConnectedNow();
            } catch (Exception e) {
                e.printStackTrace();
                controller.getCallSvc().sendEnd(controller.getCurrentPeer(), controller.getCurrentCallId());
                closeCallWindow();
            }
        });
    }

    public void onAnswer(String fromUser, String callId, String sdpJson) {
        Platform.runLater(() -> {
            if (controller.isCaller() && controller.getCallCtrl() != null && fromUser.equals(controller.getCurrentPeer()) && callId.equals(controller.getCurrentCallId())) {
                controller.getCallCtrl().setMode(VideoCallController.Mode.CONNECTED);
                markConnectedNow();
            }
        });
    }

    public void onIce(String from, String id, String c) {
        System.out.println("[CALL] ICE len=" + c.length());
    }

    public void openCallWindow(String peer, String callId, VideoCallController.Mode mode) {
        try {
            if (controller.getCallStage() != null) {
                controller.getCallStage().close();
                controller.setCallStage(null);
            }
            FXMLLoader fx = new FXMLLoader(getClass().getResource("/client/view/VideoCall.fxml"));
            Parent root = fx.load();
            controller.setCallCtrl(fx.getController());
            controller.getCallCtrl().init(controller.getCallSvc(), peer, callId, mode, this::closeCallWindow, controller);
            controller.setCallStage(new Stage());
            controller.getCallStage().setTitle("Call • @" + peer);
            controller.getCallStage().setResizable(true);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            controller.getCallStage().setScene(scene);
            controller.getCallStage().setAlwaysOnTop(false);
            controller.getCallStage().show();
            controller.getCallStage().requestFocus();
            controller.getCallStage().setOnCloseRequest(ev -> {
                ev.consume();
                controller.getCallStage().setIconified(true);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void closeCallWindow() {
        if (controller.getVideoSession() != null) {
            controller.getVideoSession().stop();
            controller.setVideoSession(null);
        }
        if (controller.getAudioSession() != null) {
            controller.getAudioSession().stop();
            controller.setAudioSession(null);
        }
        if (controller.getCallStage() != null) {
            controller.getCallStage().close();
            controller.setCallStage(null);
        }
        controller.setCallCtrl(null);
        controller.setCurrentCallId(null);
        controller.setCurrentPeer(null);
        controller.setCaller(false);
        resetCallState();
    }

    private void markConnectedNow() {
        connectedOnce = true;
        if (callStartedAt == null) callStartedAt = System.currentTimeMillis();
    }

    private void resetCallState() {
        callStartedAt = null;
        connectedOnce = false;
        logPosted = false;
    }

    private void postCallLogIfNeeded(String reason, boolean sendToPeer) {
        if (logPosted) return;
        logPosted = true;

        final String peer = controller.getCurrentPeer();
        final var conn = controller.getConnection();
        if (peer == null) return;

        boolean connected = connectedOnce && callStartedAt != null;
        long started  = (callStartedAt == null) ? 0L : callStartedAt;
        long ended    = System.currentTimeMillis();
        long duration = connected ? Math.max(0, ended - started) : 0L;

        String icon, title, subtitle;
        String self = controller.getCurrentUser() != null ? controller.getCurrentUser().getUsername() : "";
        String callerName = controller.isCaller() ? self : peer;
        if (connected) {
            icon = "📹";
            title = "Cuộc gọi video";
            subtitle = MidController.formatCallDuration(duration);
        } else {
            icon = "❌";
            title = "Đã bỏ lỡ cuộc gọi video";
            LocalTime t = LocalTime.now();
            subtitle = String.format("%02d:%02d", t.getHour(), t.getMinute());
        }

        if (sendToPeer && conn != null && conn.isAlive()) {
            String calleeName = controller.isCaller() ? peer : self;

            String payload = "[CALLLOG]{"
                + "\"type\":\"video\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"subtitle\":\"" + escape(subtitle) + "\","
                + "\"icon\":\"" + escape(icon) + "\","
                + "\"started\":" + started + ","
                + "\"ended\":" + ended + ","
                + "\"durationMs\":" + duration + ","
                + "\"callId\":\"" + controller.getCurrentCallId() + "\","
                + "\"caller\":\"" + escape(callerName) + "\","
                + "\"callee\":\"" + escape(calleeName) + "\""
                + "}";
            try {
                conn.dm(self, peer, payload);
            } catch (Exception e) {
                System.err.println("[CALLLOG] send DM failed: " + e.getMessage());
            }
        }

        boolean incoming = !controller.isCaller();
        controller.addCallLog(
                icon, 
                title, 
                subtitle, 
                incoming, 
                callerName, // sender (người tạo ra log cuộc gọi, tức là người gọi)
                ended,      // createdAt: lấy thời điểm log được tạo (cuộc gọi kết thúc)
                0L          // updatedAt: 0L vì đây là log sự kiện, không chỉnh sửa
            );
    }

    private static String escape(String s) {
        return (s == null) ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void localCancelBeforeConnect() {
        try { controller.getCallSvc().sendCancel(controller.getCurrentPeer(), controller.getCurrentCallId()); }
        catch (Exception ignore) {}
        postCallLogIfNeeded("canceled", true);
        closeCallWindow();
    }

    public void localEndAfterConnect() {
        try { controller.getCallSvc().sendEnd(controller.getCurrentPeer(), controller.getCurrentCallId()); }
        catch (Exception ignore) {}
        postCallLogIfNeeded("ended", true);
        closeCallWindow();
    }

    public void localRejectIncoming() {
        try { controller.getCallSvc().sendReject(controller.getCurrentPeer(), controller.getCurrentCallId()); }
        catch (Exception ignore) {}
        postCallLogIfNeeded("rejected", true);
        closeCallWindow();
    }
}
