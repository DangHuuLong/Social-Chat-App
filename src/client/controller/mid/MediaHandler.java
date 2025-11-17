package client.controller.mid;

import client.controller.MidController;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.io.File;
import java.net.URI;
import java.util.Optional;

public class MediaHandler {
    private final MidController controller;

    public MediaHandler(MidController controller) { this.controller = controller; }

    /* ------------ helpers ------------ */
    private boolean onFx() { return Platform.isFxApplicationThread(); }
    private void runFx(Runnable r) { if (onFx()) r.run(); else Platform.runLater(r); }

    private Node getBubbleNode(HBox row) {
        if (row == null || row.getChildren().isEmpty()) return null;

        boolean incoming = isIncomingRow(row);
        int sideIdx = incoming ? 0 : row.getChildren().size() - 1;
        Node side = row.getChildren().get(sideIdx);

        // Layout mới: side = messageVBoxWrapper
        if (side instanceof VBox wrapper && wrapper.getStyleClass().contains("message-vbox-wrapper")) {
            if (!wrapper.getChildren().isEmpty() && wrapper.getChildren().get(0) instanceof HBox contentHBox) {
                contentHBox = (HBox) wrapper.getChildren().get(0);

                // contentHBox: [avatar, contentVBox] (incoming) hoặc [contentVBox, avatar] (outgoing)
                Node contentNode;
                if (incoming) {
                    if (contentHBox.getChildren().size() < 2) return null;
                    contentNode = contentHBox.getChildren().get(1);
                } else {
                    if (contentHBox.getChildren().isEmpty()) return null;
                    contentNode = contentHBox.getChildren().get(0);
                }

                if (contentNode instanceof VBox contentVBox) {
                    // contentVBox: [header, bubble]
                    if (contentVBox.getChildren().size() >= 2) {
                        return contentVBox.getChildren().get(1); // bubble thật (image/file/voice/video)
                    }
                }
            }
        }

        // Fallback layout cũ
        return (incoming ? row.getChildren().get(0) : row.getChildren().get(row.getChildren().size() - 1));
    }


    private <T extends Node> Optional<T> findById(Node root, String id, Class<T> type) {
        if (root == null) return Optional.empty();
        if (type.isInstance(root) && id.equals(root.getId())) return Optional.of(type.cast(root));
        if (root instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Optional<T> r = findById(c, id, type);
                if (r.isPresent()) return r;
            }
        }
        return Optional.empty();
    }

    private <T extends Node> Optional<T> findFirst(Node root, Class<T> type) {
        if (root == null) return Optional.empty();
        if (type.isInstance(root)) return Optional.of(type.cast(root));
        if (root instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Optional<T> r = findFirst(c, type);
                if (r.isPresent()) return r;
            }
        }
        return Optional.empty();
    }

    private boolean isIncomingRow(HBox row) {
        return row != null && row.getAlignment() == Pos.CENTER_LEFT;
    }

    private void replaceBubble(HBox row, Node newBubble) {
        if (row == null || newBubble == null || row.getChildren().isEmpty()) return;
        boolean incoming = isIncomingRow(row);
        int sideIdx = incoming ? 0 : row.getChildren().size() - 1;
        Node side = row.getChildren().get(sideIdx);

        // Layout mới: sửa bubble bên trong messageVBoxWrapper, giữ nguyên avatar + header
        if (side instanceof VBox wrapper && wrapper.getStyleClass().contains("message-vbox-wrapper")) {
            if (!wrapper.getChildren().isEmpty() && wrapper.getChildren().get(0) instanceof HBox contentHBox) {
                contentHBox = (HBox) wrapper.getChildren().get(0);

                int contentIdx;
                if (incoming) {
                    if (contentHBox.getChildren().size() < 2) return;
                    contentIdx = 1; // [avatar, contentVBox]
                } else {
                    if (contentHBox.getChildren().isEmpty()) return;
                    contentIdx = 0; // [contentVBox, avatar]
                }

                Node contentNode = contentHBox.getChildren().get(contentIdx);
                if (contentNode instanceof VBox contentVBox) {
                    // contentVBox: [header, bubble]
                    if (contentVBox.getChildren().size() >= 2) {
                        contentVBox.getChildren().set(1, newBubble);
                    } else {
                        contentVBox.getChildren().add(newBubble);
                    }
                    return;
                }
            }
        }

        // Fallback: layout cũ -> thay trực tiếp
        row.getChildren().set(sideIdx, newBubble);
    }


    /* ===================== AUDIO ===================== */
    public void updateVoiceBubbleFromUrl(HBox row, String fileUrl) {
        runFx(() -> {
            try {
                Media media = new Media(fileUrl);
                MediaPlayer player = new MediaPlayer(media);

                Node bubble = getBubbleNode(row);
                HBox voiceBox;
                Button playBtn;
                Slider slider;
                Label durLbl;

                boolean needUpgrade = true;
                if (bubble instanceof HBox b && b.getId() != null && b.getId().endsWith("-voice")) {
                    needUpgrade = false;
                }

                if (needUpgrade) {
                    boolean incoming = isIncomingRow(row);

                    voiceBox = new HBox(10);
                    voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
                    voiceBox.setAlignment(Pos.CENTER_LEFT);

                    playBtn = new Button("▶");
                    playBtn.getStyleClass().add("audio-btn");
                    playBtn.setId("voicePlay");

                    slider = new Slider();
                    slider.setPrefWidth(200);
                    slider.setId("voiceSlider");

                    durLbl = new Label("--:--");
                    durLbl.setId("voiceDuration");

                    voiceBox.getChildren().addAll(playBtn, slider, durLbl);
                    replaceBubble(row, voiceBox);
                } else {
                    voiceBox = (HBox) bubble;
                    playBtn = findById(voiceBox, "voicePlay", Button.class)
                            .orElseGet(() -> findFirst(voiceBox, Button.class).orElse(null));
                    slider = findById(voiceBox, "voiceSlider", Slider.class)
                            .orElseGet(() -> findFirst(voiceBox, Slider.class).orElse(null));
                    durLbl = findById(voiceBox, "voiceDuration", Label.class)
                            .orElseGet(() -> findFirst(voiceBox, Label.class).orElse(null));
                }

                if (playBtn == null || slider == null || durLbl == null) return;

                playBtn.setText("▶");

                player.setOnError(() -> controller.showErrorAlert("Phát audio lỗi: " + player.getError()));

                player.setOnReady(() -> {
                    double total = player.getTotalDuration().toSeconds();
                    slider.setMin(0);
                    slider.setMax(total > 0 ? total : 0);
                    if (total > 0) {
                        try {
                            durLbl.setText(UtilHandler.formatDuration((int) total));
                        } catch (Exception ignore) {
                            durLbl.setText(String.format("%d:%02d", (int)total/60, (int)total%60));
                        }
                    }
                });

                player.currentTimeProperty().addListener((obs, old, val) -> {
                    if (!slider.isValueChanging()) slider.setValue(val.toSeconds());
                });
                slider.valueChangingProperty().addListener((obs, was, ch) -> {
                    if (!ch) player.seek(Duration.seconds(slider.getValue()));
                });
                slider.setOnMouseReleased(e -> player.seek(Duration.seconds(slider.getValue())));

                playBtn.setOnAction(event -> {
                    switch (player.getStatus()) {
                        case PLAYING -> { player.pause(); playBtn.setText("▶"); }
                        default       -> { player.play();  playBtn.setText("⏸"); }
                    }
                });

            } catch (Exception e) {
                controller.showErrorAlert("Phát âm thanh thất bại: " + e.getMessage());
            }
        });
    }

    /* ===================== VIDEO (KHÔNG label) ===================== */
    public void updateVideoBubbleFromUrl(HBox row, String fileUrl) {
        runFx(() -> {
            try {
                Node bubble = getBubbleNode(row);
                VBox box;

                if (!(bubble instanceof VBox b) || b.getId() == null || !b.getId().endsWith("-video")) {
                    boolean incoming = isIncomingRow(row);
                    box = new VBox(6);
                    box.setId(incoming ? "incoming-video" : "outgoing-video");
                    box.setAlignment(Pos.CENTER_LEFT);

                    Region videoArea = new Region();
                    videoArea.setPrefSize(320, 180);
                    videoArea.setStyle("-fx-background-color: #111111; -fx-background-radius: 8;");
                    videoArea.setId("videoArea");

                    VBox controls = new VBox(4);
                    controls.setId("videoControls");

                    Button playBtn = new Button("▶");
                    playBtn.setId("videoPlay");

                    Slider slider = new Slider();
                    slider.setPrefWidth(220);
                    slider.setId("videoSlider");

                    controls.getChildren().addAll(playBtn, slider);
                    box.getChildren().addAll(videoArea, controls);

                    replaceBubble(row, box);
                } else {
                    box = (VBox) bubble;
                    // Đảm bảo controls tồn tại, nhưng KHÔNG có label
                    VBox controls = findById(box, "videoControls", VBox.class)
                            .orElseGet(() -> {
                                VBox c = new VBox(4);
                                c.setId("videoControls");
                                box.getChildren().add(c);
                                return c;
                            });

                    // Xoá mọi label còn sót (nếu có)
                    controls.getChildren().removeIf(n -> n instanceof Label);

                    // Tạo nút/slider nếu thiếu
                    if (findById(controls, "videoPlay", Button.class).isEmpty()) {
                        Button playBtn = new Button("▶");
                        playBtn.setId("videoPlay");
                        controls.getChildren().add(0, playBtn);
                    }
                    if (findById(controls, "videoSlider", Slider.class).isEmpty()) {
                        Slider slider = new Slider();
                        slider.setPrefWidth(220);
                        slider.setId("videoSlider");
                        controls.getChildren().add(slider);
                    }
                }

                VBox controls = findById(box, "videoControls", VBox.class)
                        .orElseGet(() -> findFirst(box, VBox.class).orElse(null));
                Button playBtn = (controls == null) ? null
                        : findById(controls, "videoPlay", Button.class).orElseGet(() -> findFirst(controls, Button.class).orElse(null));
                Slider slider = (controls == null) ? null
                        : findById(controls, "videoSlider", Slider.class).orElseGet(() -> findFirst(controls, Slider.class).orElse(null));

                MediaView view = new MediaView();
                view.setFitWidth(320);
                view.setFitHeight(180);
                view.setPreserveRatio(true);

                Region videoArea = findById(box, "videoArea", Region.class).orElse(null);
                if (videoArea != null) {
                    int idx = box.getChildren().indexOf(videoArea);
                    if (idx >= 0) box.getChildren().set(idx, view);
                    else box.getChildren().add(0, view);
                } else {
                    if (box.getChildren().isEmpty()) box.getChildren().add(view);
                    else box.getChildren().set(0, view);
                }

                String key = (row.getUserData() == null) ? null : String.valueOf(row.getUserData());
                attachVideoPlayer(key, playBtn, slider, view, fileUrl, 0, true);

            } catch (Exception e) {
                controller.showErrorAlert("Phát video thất bại: " + e.getMessage());
            }
        });
    }

    private void attachVideoPlayer(String key, Button playBtn, Slider slider, MediaView view,
                                   String mediaUrl, int attempt, boolean allowFix) {
        runFx(() -> {
            try {
                try {
                    var old = controller.getVideoPlayer(key);
                    if (old != null) { old.stop(); old.dispose(); }
                } catch (Exception ignore) {}

                Media media = new Media(mediaUrl);
                MediaPlayer player = new MediaPlayer(media);
                view.setMediaPlayer(player);
                if (key != null) controller.putVideoPlayer(key, player);

                if (slider != null) { slider.setMin(0); slider.setMax(1); slider.setDisable(true); }

                player.setOnReady(() -> {
                    double total = player.getTotalDuration().toSeconds();
                    if (slider != null) { slider.setDisable(false); slider.setMax(total > 0 ? total : 0); }
                    if (playBtn != null) playBtn.setText("▶");
                });

                player.currentTimeProperty().addListener((obs, o, n) -> {
                    if (slider != null && !slider.isValueChanging()) slider.setValue(n.toSeconds());
                });

                if (slider != null) {
                    slider.valueChangingProperty().addListener((obs, was, changing) -> {
                        if (!changing) player.seek(Duration.seconds(slider.getValue()));
                    });
                    slider.setOnMouseReleased(e -> player.seek(Duration.seconds(slider.getValue())));
                }

                if (playBtn != null) {
                    playBtn.setOnAction(e -> {
                        switch (player.getStatus()) {
                            case PLAYING -> { player.pause(); playBtn.setText("▶"); }
                            default       -> { player.play();  playBtn.setText("⏸"); }
                        }
                    });
                }

                player.setOnEndOfMedia(() -> {
                    if (playBtn != null) playBtn.setText("▶");
                    if (slider != null)  slider.setValue(slider.getMax());
                });

                player.setOnError(() -> handleVideoOpenError(key, playBtn, slider, view, mediaUrl, attempt, allowFix, player));

            } catch (Exception e) {
                controller.showErrorAlert("Phát video thất bại: " + e.getMessage());
            }
        });
    }

    private void handleVideoOpenError(String key, Button playBtn, Slider slider, MediaView view,
                                      String mediaUrl, int attempt, boolean allowFix, MediaPlayer current) {
        MediaException me = current.getError();
        boolean retryable = me != null && switch (me.getType()) {
            case MEDIA_UNAVAILABLE, MEDIA_INACCESSIBLE, UNKNOWN -> true;
            default -> false;
        };
        try { current.stop(); current.dispose(); } catch (Exception ignore) {}

        if (retryable && attempt < 2) {
            javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(Duration.millis(400 + attempt * 300));
            pt.setOnFinished(e -> attachVideoPlayer(key, playBtn, slider, view, mediaUrl, attempt + 1, allowFix));
            pt.play();
            return;
        }
        handleVideoErrorFixOnce(key, playBtn, slider, view, mediaUrl, allowFix);
    }

    private void handleVideoErrorFixOnce(String key, Button playBtn, Slider slider,
                                         MediaView view, String mediaUrl, boolean allowFix) {
        if (!allowFix) {
            controller.showErrorAlert("Phát video lỗi: " + String.valueOf(view.getMediaPlayer() != null ? view.getMediaPlayer().getError() : "unknown"));
            return;
        }
        File in;
        try { in = new File(URI.create(mediaUrl)); }
        catch (Exception ex) {
            controller.showErrorAlert("Đường dẫn video không hợp lệ.");
            return;
        }

        new Thread(() -> {
            try {
                File out = File.createTempFile("fx_fix_", ".mp4");
                String inPath = in.getAbsolutePath(), outPath = out.getAbsolutePath();

                if (!hasFfmpeg()) {
                    Platform.runLater(() -> controller.showErrorAlert("Không xử lý được video (thiếu ffmpeg trong PATH)."));
                    return;
                }

                if (runFfmpeg(new String[]{"ffmpeg","-y","-i",inPath,"-c:v","copy","-c:a","copy","-movflags","+faststart",outPath})) {
                    Platform.runLater(() -> attachVideoPlayer(key, playBtn, slider, view, out.toURI().toString(), 0, false));
                    return;
                }
                if (runFfmpeg(new String[]{"ffmpeg","-y","-i",inPath,"-c:v","copy","-c:a","aac","-b:a","128k","-ac","2","-ar","48000","-movflags","+faststart",outPath})) {
                    Platform.runLater(() -> attachVideoPlayer(key, playBtn, slider, view, out.toURI().toString(), 0, false));
                    return;
                }
                if (runFfmpeg(new String[]{"ffmpeg","-y","-i",inPath,"-c:v","copy","-an","-movflags","+faststart",outPath})) {
                    Platform.runLater(() -> attachVideoPlayer(key, playBtn, slider, view, out.toURI().toString(), 0, false));
                    return;
                }
                if (runFfmpeg(new String[]{"ffmpeg","-y","-i",inPath,"-c:v","libx264","-pix_fmt","yuv420p","-profile:v","baseline","-level","3.1",
                        "-c:a","aac","-b:a","128k","-ac","2","-ar","48000","-movflags","+faststart",outPath})) {
                    Platform.runLater(() -> attachVideoPlayer(key, playBtn, slider, view, out.toURI().toString(), 0, false));
                    return;
                }
                Platform.runLater(() -> controller.showErrorAlert("Không xử lý được video (codec/container không hỗ trợ)."));
            } catch (Exception ex) {
                Platform.runLater(() -> controller.showErrorAlert("Không thể chuyển mã video: " + ex.getMessage()));
            }
        }, "fx-video-fix").start();
    }

    private boolean hasFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg","-version").redirectErrorStream(true).start();
            try (var is = p.getInputStream()) { while (is.read() != -1) {} }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean runFfmpeg(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var is = p.getInputStream()) { while (is.read() != -1) {} }
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    /* ===================== IMAGE (KHÔNG label) ===================== */
    public void updateImageBubbleFromUrl(HBox row, String fileUrl) {
        runFx(() -> {
            try {
                Image img = new Image(fileUrl, true);
                Node bubble = getBubbleNode(row);

                if (!(bubble instanceof VBox box) || box.getId() == null || !box.getId().endsWith("-image")) {
                    boolean incoming = isIncomingRow(row);
                    VBox newBox = new VBox(4);
                    newBox.setId(incoming ? "incoming-image" : "outgoing-image");

                    ImageView iv = new ImageView();
                    iv.setFitWidth(260);
                    iv.setPreserveRatio(true);
                    iv.setImage(img);

                    newBox.getChildren().add(iv);
                    replaceBubble(row, newBox);
                    return;
                }

                box = (VBox) bubble;
                ImageView iv = findFirst(box, ImageView.class).orElse(null);
                if (iv == null) {
                    iv = new ImageView();
                    iv.setFitWidth(260);
                    iv.setPreserveRatio(true);
                    box.getChildren().add(0, iv);
                }
                iv.setImage(img);

                // Xoá mọi label còn sót (nếu có)
                box.getChildren().removeIf(n -> n instanceof Label);

            } catch (Exception e) {
                // ignore
            }
        });
    }

    /* ===================== META: chỉ áp dụng cho FILE thường ===================== */
    public void updateGenericFileMeta(HBox row, String fid) {
        updateGenericFileMeta(row, fid, null);
    }

    public void updateGenericFileMeta(HBox row, String fid, Long bytesHint) {
        if (row == null) return;

        Node bubble = getBubbleNode(row);
        if (!(bubble instanceof VBox box)) return;
        String bid = box.getId() == null ? "" : box.getId();

        if (!bid.endsWith("-file")) return; // chỉ cập nhật file thường

        String name = controller.getFileIdToName().getOrDefault(fid, "");
        long size = (bytesHint != null && bytesHint >= 0)
                ? bytesHint
                : Optional.ofNullable(controller.getDlPath().get(fid))
                          .filter(File::exists).map(File::length).orElse(-1L);
        if (size < 0) {
            size = Optional.ofNullable(controller.getFileIdToSize().get(fid)).orElse(-1L);
        }
        String meta = (size >= 0) ? UtilHandler.humanBytes(size) : "";

        Label nameLbl = findById(box, "fileNamePrimary", Label.class)
                .orElseGet(() -> findFirst(box, Label.class).orElse(null));
        Label metaLbl = findById(box, "fileMeta", Label.class)
                .orElseGet(() -> findFirst(box, Label.class).orElse(null));
        if (nameLbl != null) nameLbl.setText(name);
        if (metaLbl != null) metaLbl.setText(meta);
    }

    public void updateGenericFileMetaByFid(String fid) {
        HBox row = controller.findRowByUserData(fid);
        if (row == null) return;
        long size = Optional.ofNullable(controller.getDlPath().get(fid))
                .filter(File::exists).map(File::length).orElse(-1L);
        updateGenericFileMeta(row, fid, size);
    }
}
