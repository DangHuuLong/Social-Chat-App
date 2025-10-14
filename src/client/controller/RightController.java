package client.controller;

import client.controller.mid.UtilHandler;
import common.User;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RightController {
    private Label infoName;
    private Label chatStatus;
    private ImageView rightHeaderAvatar;
    
    private TabPane mediaTabs;
    private TilePane photoGrid;
    private TilePane videoGrid;
    private VBox     docList;

    private StackPane overlayLayer;
    private StackPane overlayContent;
    private javafx.scene.shape.Rectangle overlayDim;
    private Button btnOverlayClose;
    private Button btnOverlayDownload;

    private MidController midController;
    private String currentDownloadingFileId = null;
    private String currentOverlayFileId = null;
    private final Map<String, MediaPlayer> thumbnailPlayers = new ConcurrentHashMap<>();

    private MediaPlayer currentOverlayPlayer;   
    private ChangeListener<Boolean> overlayVisibleListener;

    public void setMidController(MidController controller) {
        this.midController = controller;
    }

    public void bind(Label infoName, Label chatStatus, ImageView rightHeaderAvatar, TabPane mediaTabs, TilePane photoGrid, TilePane videoGrid, VBox docList) {
        this.infoName = infoName;
        this.chatStatus = chatStatus;
        this.rightHeaderAvatar = rightHeaderAvatar;
        this.mediaTabs = mediaTabs;
        this.photoGrid = photoGrid;
        this.videoGrid = videoGrid;
        this.docList = docList;
        
        if (midController != null) {
            midController.getDownloadedFiles().addListener(new MapChangeListener<String, File>() {
                @Override
                public void onChanged(Change<? extends String, ? extends File> change) {
                    if (change.wasAdded()) {
                        String fileId = change.getKey();
                        File file = change.getValueAdded();
                        String mime = midController.getFileIdToMime().get(fileId);
                        String name = midController.getFileIdToName().get(fileId);
                        
                        if (UtilHandler.classifyMedia(mime, name) == UtilHandler.MediaKind.VIDEO) {
                            // Cập nhật giao diện thumbnail
                            Platform.runLater(() -> updateVideoThumbnail(fileId, file));
                        }
                    }
                }
            });
        }
    }
    
    public void bindOverlay(StackPane overlayLayer, StackPane overlayContent,
            javafx.scene.shape.Rectangle overlayDim, Button btnClose, Button btnDownload) {
        this.overlayLayer       = overlayLayer;
        this.overlayContent     = overlayContent;
        this.overlayDim         = overlayDim;
        this.btnOverlayClose    = btnClose;
        this.btnOverlayDownload = btnDownload;

        if (overlayDim != null && overlayLayer != null) {
            overlayDim.widthProperty().bind(overlayLayer.widthProperty());
            overlayDim.heightProperty().bind(overlayLayer.heightProperty());
        }

        if (btnOverlayClose != null) btnOverlayClose.setOnAction(e -> hideOverlay());
        if (overlayDim != null) overlayDim.setOnMouseClicked(e -> hideOverlay());

        if (overlayLayer != null) overlayLayer.setPickOnBounds(true);
        if (overlayContent != null) {
            overlayContent.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            overlayContent.setAlignment(Pos.CENTER);
        }
        
        if (overlayVisibleListener == null && overlayLayer != null) {
            overlayVisibleListener = (obs, oldV, newV) -> {
                if (!newV) {
                    // overlay bị ẩn -> giải phóng player hiện tại
                    destroyCurrentOverlayPlayer();
                }
            };
            overlayLayer.visibleProperty().addListener(overlayVisibleListener);
        }
    }

    public void clearMediaTabs() {
        Platform.runLater(() -> {
            photoGrid.getChildren().clear();
            videoGrid.getChildren().clear();
            docList.getChildren().clear();
        });
    }
    
    private void updateVideoThumbnail(String fileId, File file) {
        Node thumbNode = videoGrid.getChildren().stream()
                .filter(node -> fileId.equals(node.getUserData()))
                .findFirst()
                .orElse(null);

        if (thumbNode != null && thumbNode instanceof StackPane cell) {
            cell.getChildren().clear();

            Media media = new Media(file.toURI().toString());
            MediaPlayer mediaPlayer = new MediaPlayer(media);
            MediaView mediaView = new MediaView(mediaPlayer);

            mediaPlayer.setOnReady(() -> {
                mediaView.setFitWidth(92);
                mediaView.setFitHeight(92);

                mediaPlayer.seek(Duration.ZERO);

                WritableImage snapshot = mediaView.snapshot(null, null);

                ImageView iv = new ImageView(snapshot);
                iv.setFitWidth(92);
                iv.setFitHeight(92);
                iv.setPreserveRatio(false);
                cell.getChildren().add(iv);
                
                mediaPlayer.stop();
                mediaPlayer.dispose();
            });
            mediaPlayer.setOnError(() -> {
                System.err.println("Failed to load video for thumbnail snapshot: " + mediaPlayer.getError());
                mediaPlayer.stop();
                mediaPlayer.dispose();
            });
        }
    }
    
    private void destroyCurrentOverlayPlayer() {
        MediaPlayer p = currentOverlayPlayer;
        currentOverlayPlayer = null; 
        if (p == null) return;
        try { p.stop(); } catch (Exception ignore) {}
        try { p.dispose(); } catch (Exception ignore) {}
    }

    
    private void attachMediaDebug(Media media, MediaPlayer player, String fileUrl) {
        media.getMetadata().addListener((MapChangeListener<String, Object>) ch -> {
            if (ch.wasAdded()) {
                System.out.println("[MEDIA] meta " + ch.getKey() + " = " + ch.getValueAdded());
            }
        });
        media.setOnError(() -> {
            MediaException me = media.getError();
            System.err.println("[MEDIA] Media error. type=" + (me != null ? me.getType() : "null")
                    + " msg=" + (me != null ? me.getMessage() : "null")
                    + " src=" + fileUrl);
            if (me != null && me.getCause() != null) me.getCause().printStackTrace();
        });
        player.setOnError(() -> {
            MediaException me = player.getError();
            System.err.println("[MEDIA] Player error. type=" + (me != null ? me.getType() : "null")
                    + " msg=" + (me != null ? me.getMessage() : "null")
                    + " status=" + player.getStatus()
                    + " src=" + fileUrl);
            if (me != null && me.getCause() != null) me.getCause().printStackTrace();
        });
    }


    public void onDownloadDocument(String fileId, String fileName, String mimeType) {
        if (fileId == null || midController.getConnection() == null || !midController.getConnection().isAlive()) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File");
        String extension = UtilHandler.guessExt(mimeType, fileName);
        fileChooser.setInitialFileName(fileName);
        
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File saveFile = fileChooser.showSaveDialog(new Stage()); 
        if (saveFile == null) {
            System.out.println("User cancelled download.");
            return;
        }

        try {
            midController.getConnection().downloadFileByFileId(Long.parseLong(fileId));
            midController.getDlPath().put(fileId, saveFile);
            midController.getDlOut().put(fileId, new BufferedOutputStream(new FileOutputStream(saveFile)));
            
            System.out.println("[DOWNLOAD] Request sent. File will be saved to: " + saveFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[DOWNLOAD] Failed to start download: " + e.getMessage());
            midController.getDlPath().remove(fileId);
            midController.getDlOut().remove(fileId);
        }
    }

    public void addPhotoThumb(String fileId, String fileName, long fileSize, String filePath) {
        Platform.runLater(() -> {
            File localFile = new File(filePath);
            Image image = localFile.exists() ? 
                    new Image(localFile.toURI().toString()) : 
                    new Image(getClass().getResource("/client/view/images/avatar.jpg").toExternalForm());
            
            StackPane cell = makeSquareThumb(92);
            ImageView iv = new ImageView(image);
            iv.setFitWidth(92);
            iv.setFitHeight(92);
            iv.setPreserveRatio(false);
            cell.getChildren().add(iv);

            Rectangle clip = new Rectangle(92, 92);
            clip.setArcWidth(18); clip.setArcHeight(18);
            cell.setClip(clip);

            cell.setUserData(fileId);
            photoGrid.getChildren().add(cell);

            cell.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    // Check if file is already downloaded
                    File downloadedFile = midController.getDlPath().get(fileId);
                    if (downloadedFile != null && downloadedFile.exists()) {
                        // If yes, show overlay directly
                        showImageOverlay(new Image(downloadedFile.toURI().toString()), fileId);
                    } else {
                        // If no, initiate download and wait for it to finish before showing
                        downloadFileAndShowOverlay(fileId, UtilHandler.MediaKind.IMAGE);
                    }
                }
            });

        });
    }

    public void addVideoThumb(String fileId, String fileName, long fileSize, String filePath) {
        Platform.runLater(() -> {
            File videoFile = new File(filePath);
            if (!videoFile.exists()) {
                System.err.println("Video file not found for thumbnail: " + filePath);
            }

            StackPane cell = makeSquareThumb(92);
            
            Image placeholderImage;
            try {
                placeholderImage = new Image(getClass().getResource("/client/view/images/video-placeholder.png").toExternalForm());
            } catch (Exception e) {
                System.err.println("Video placeholder image not found, using a blank image.");
                placeholderImage = new WritableImage(1, 1);
            }
            
            ImageView placeholder = new ImageView(placeholderImage);
            placeholder.setFitWidth(92);
            placeholder.setFitHeight(92);
            placeholder.setPreserveRatio(false);
            cell.getChildren().add(placeholder);
            
            Rectangle clip = new Rectangle(92, 92);
            clip.setArcWidth(18);
            clip.setArcHeight(18);
            cell.setClip(clip);

            cell.setUserData(fileId);
            videoGrid.getChildren().add(cell);

            cell.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    downloadFileAndShowOverlay(fileId, UtilHandler.MediaKind.VIDEO);
                }
            });
        });
    }
    
	 public void addDocumentItem(String fileId, String fileName, long fileSize, String mimeType, String filePath) {
	        Platform.runLater(() -> {
	            String humanSize = UtilHandler.humanBytes(fileSize);
	            HBox row = makeDocRow(fileName, humanSize);
	            row.setUserData(fileId);
	            docList.getChildren().add(row);
	            
	            row.setOnMouseClicked(e -> {
	                if (e.getButton() == MouseButton.PRIMARY) {
	                	onDownloadDocument(fileId, fileName, mimeType);
	                }
	            });
	        });
	    }

    private StackPane makeSquareThumb(double size) {
        StackPane p = new StackPane();
        p.setPrefSize(size, size);
        p.setMinSize(size, size);
        p.setMaxSize(size, size);
        p.getStyleClass().add("media-thumb");
        return p;
    }

    private HBox makeDocRow(String filename, String meta) {
        HBox row = new HBox();
        row.setSpacing(0);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox();
        box.setId("outgoing-file");
        box.setPadding(new Insets(8,12,8,12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label nameLbl = new Label(filename == null ? "" : filename);
        nameLbl.setId("fileNamePrimary");
        nameLbl.getStyleClass().add("file-name");

        Label metaLbl = new Label(meta == null ? "" : meta);
        metaLbl.setId("fileMeta");
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.setId("fileInfoBox");
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);
        
        content.getChildren().addAll(info, innerSpacer);

        box.getChildren().add(content);
        row.getChildren().add(box);
        return row;
    }

    private void showImageOverlay(Image image, String fileId) {
        if (overlayLayer == null || overlayContent == null || image == null) return;
        overlayContent.getChildren().clear();
        currentOverlayFileId = fileId;

        ImageView iv = new ImageView(image);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setFitHeight(600);
        iv.setFitWidth(900);

        overlayContent.getChildren().add(iv);
        btnOverlayDownload.setOnAction(e -> {
            String fileName = midController.getFileIdToName().getOrDefault(fileId, "file");
            String mimeType = midController.getFileIdToMime().getOrDefault(fileId, "application/octet-stream");
            onDownloadDocument(fileId, fileName, mimeType);
        });
        showOverlayElements();
    }
    
    private void showVideoOverlay(String fileUrl, String fileId) {
    	File f;
    	try { f = new File(new java.net.URI(fileUrl)); } catch (Exception ex) { f = null; }

    	if (f != null) {
    	    Long expect = midController.getFileIdToSize().get(fileId);
    	    if (expect != null && expect > 0 && f.length() < expect) {
    	        runLaterDelay(200, () -> showVideoOverlay(fileUrl, fileId));
    	        return;
    	    }
    	}

        if (overlayLayer == null || overlayContent == null || fileUrl == null) {
            System.err.println("Cannot show video overlay: Missing required components or file URL is null.");
            return;
        }
        
        overlayContent.getChildren().clear();
        
        destroyCurrentOverlayPlayer();
        Media media = new Media(fileUrl);
        MediaPlayer mediaPlayer = new MediaPlayer(media);
        MediaView mediaView = new MediaView(mediaPlayer);
        attachMediaDebug(media, mediaPlayer, fileUrl);
        
        mediaView.setFitHeight(600);
        mediaView.setFitWidth(900);
        mediaView.setPreserveRatio(true);
        mediaView.setSmooth(true);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("overlay-action");

        Slider progress = new Slider();
        progress.getStyleClass().add("media-progress-slider");
        HBox.setHgrow(progress, Priority.ALWAYS);

        Button volumeBtn = new Button("🔊");
        volumeBtn.getStyleClass().add("overlay-action");
        Slider volumeSlider = new Slider(0, 1, 1);
        volumeSlider.setPrefWidth(80);

        HBox controls = new HBox(10, playBtn, progress, volumeBtn, volumeSlider);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        BorderPane playerPane = new BorderPane();
        playerPane.getStyleClass().add("player-pane");
        playerPane.setCenter(mediaView);
        playerPane.setBottom(controls);
        overlayContent.getChildren().add(playerPane);

        mediaPlayer.setOnReady(() -> {
            System.out.println("Video is ready to play!");
            progress.setMax(mediaPlayer.getMedia().getDuration().toSeconds());
            
            mediaPlayer.play();
            playBtn.setText("⏸");
        });
        
        mediaPlayer.setOnError(() -> {
            MediaException error = mediaPlayer.getError();
            try { mediaPlayer.dispose(); } catch (Exception ignore) {}
            if (currentOverlayPlayer == mediaPlayer) currentOverlayPlayer = null;
            File tmp;
            try {
                tmp = new File(new java.net.URI(fileUrl));
            } catch (Exception ignore) {
                tmp = null;
            }
            final File fileRef = tmp;                   
            final long sizeNow = (fileRef != null && fileRef.exists()) ? fileRef.length() : -1;

            System.err.println("MediaPlayer error: " + error + " size=" + sizeNow);

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Lỗi phát video");
                alert.setHeaderText("Không thể phát video này.");

                String errorMessage = "Đã xảy ra lỗi khi phát file video. ";
                if (error != null) {
                    var t = error.getType();
                    if (t == MediaException.Type.MEDIA_CORRUPTED)      errorMessage += "File video có thể đã bị hỏng hoặc chưa sẵn sàng.";
                    else if (t == MediaException.Type.MEDIA_UNSUPPORTED) errorMessage += "Định dạng/codec không được JavaFX hỗ trợ (ví dụ HEVC/H.265).";
                    else if (t == MediaException.Type.MEDIA_INACCESSIBLE) errorMessage += "Không truy cập được file (đường dẫn/quyền).";
                    else if (t == MediaException.Type.UNKNOWN)          errorMessage += "Lỗi không xác định từ backend media.";
                    else                                                errorMessage += "Loại lỗi: " + t;
                }

                errorMessage += "\nKích thước hiện tại: " + (sizeNow >= 0 ? sizeNow + " bytes" : "không xác định");
                errorMessage += "\nBạn có thể mở bằng trình phát ngoài.";
                alert.setContentText(errorMessage);
                alert.showAndWait();

                try { if (fileRef != null && fileRef.exists()) java.awt.Desktop.getDesktop().open(fileRef); }
                catch (Exception ex) { System.err.println("Fallback open external failed: " + ex.getMessage()); }
            });
        });

        mediaPlayer.currentTimeProperty().addListener((obs, oldV, newV) -> {
            if (!progress.isValueChanging()) {
                progress.setValue(newV.toSeconds());
            }
        });

        progress.setOnMousePressed(event -> {
            mediaPlayer.pause();
            playBtn.setText("▶");
        });
        
        progress.setOnMouseReleased(event -> {
            mediaPlayer.seek(Duration.seconds(progress.getValue()));
            mediaPlayer.play();
            playBtn.setText("⏸");
        });

        playBtn.setOnAction(e -> {
            System.out.println("Play button clicked. Current status: " + mediaPlayer.getStatus());
            if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                mediaPlayer.pause();
                playBtn.setText("▶");
            } else {
                mediaPlayer.play();
                playBtn.setText("⏸");
            }
        });
        mediaPlayer.volumeProperty().bindBidirectional(volumeSlider.valueProperty());
        volumeBtn.setOnAction(e -> {
            boolean mute = mediaPlayer.isMute();
            mediaPlayer.setMute(!mute);
            volumeBtn.setText(mute ? "🔊" : "🔇");
        });
        mediaPlayer.currentTimeProperty().addListener((obs, ov, nv) -> {
            if (!progress.isValueChanging()) {
                progress.setValue(nv.toSeconds());
            }
        });
        mediaPlayer.setOnReady(() -> {
            double dur = mediaPlayer.getMedia().getDuration().toSeconds();
            progress.setMax(dur > 0 ? dur : 0);
            mediaPlayer.play();
            playBtn.setText("⏸");
        });

        progress.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) { 
                mediaPlayer.seek(Duration.seconds(progress.getValue()));
            }
        });
        progress.setOnMouseReleased(e -> {
            if (!progress.isValueChanging()) {
                mediaPlayer.seek(Duration.seconds(progress.getValue()));
            }
        });
        mediaPlayer.volumeProperty().bind(volumeSlider.valueProperty());

        btnOverlayDownload.setOnAction(e -> {
            String fileName = midController.getFileIdToName().getOrDefault(fileId, "file");
            String mimeType = midController.getFileIdToMime().getOrDefault(fileId, "application/octet-stream");
            onDownloadDocument(fileId, fileName, mimeType);
        });

        showOverlayElements();
        
    }
    
    private void showOverlayElements() {
        overlayLayer.setVisible(true);
        overlayLayer.setManaged(true);

        Platform.runLater(() -> {
            if (overlayDim != null) overlayDim.toBack();
            if (overlayContent != null) overlayContent.toFront();

            Node anyActionBtn = overlayLayer.lookup(".overlay-action");
            if (anyActionBtn != null && anyActionBtn.getParent() != null) {
                anyActionBtn.getParent().toFront();
            }
            if (btnOverlayClose != null) btnOverlayClose.toFront();
            if (btnOverlayDownload != null) btnOverlayDownload.toFront();
        });
    }
    
    private boolean isFileComplete(String fileId, File f) {
        if (f == null || !f.exists()) return false;
        try {
            Long expect = midController.getFileIdToSize().get(fileId);
            if (expect == null || expect <= 0) return f.length() > 0; 
            return f.length() == expect;
        } catch (Exception ignore) {
            return f.length() > 0;
        }
    }

    
    
    private void hideOverlay() {
        if (overlayLayer == null) return;
        destroyCurrentOverlayPlayer();

        overlayLayer.setVisible(false);
        overlayLayer.setManaged(false);
        if (overlayContent != null) overlayContent.getChildren().clear();
        currentOverlayFileId = null;
    }


    private void downloadFileAndShowOverlay(String fileId, UtilHandler.MediaKind kind) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        File completedFile = midController.getDownloadedFiles().get(fileId);
        if (completedFile != null && completedFile.exists()) {
            Platform.runLater(() -> {
                if (kind == UtilHandler.MediaKind.IMAGE) {
                    showImageOverlay(new Image(completedFile.toURI().toString()), fileId);
                } else if (kind == UtilHandler.MediaKind.VIDEO) {
                    showVideoOverlay(completedFile.toURI().toString(), fileId);
                }
            });
            return;
        }
        if (midController.getDlOut().containsKey(fileId) || midController.getDlPath().containsKey(fileId)) {
            System.out.println("File is downloading. Will open when complete.");
            return;
        }

        File downloadedFile = midController.getDlPath().get(fileId);
        
        // Nếu file đã có, hiển thị overlay ngay lập tức.
        if (downloadedFile != null && downloadedFile.exists()) {
            Platform.runLater(() -> {
                if (kind == UtilHandler.MediaKind.IMAGE) {
                    showImageOverlay(new Image(downloadedFile.toURI().toString()), fileId);
                } else if (kind == UtilHandler.MediaKind.VIDEO) {
                    // Sửa đổi dòng này để truyền URL file
                    showVideoOverlay(downloadedFile.toURI().toString(), fileId);
                }
            });
            return;
        }
        
        // Nếu file đang tải, không làm gì cả.
        if (midController.getDlOut().containsKey(fileId) && midController.getDlPath().containsKey(fileId)) {
            System.out.println("File is already downloading. Please wait.");
            return;
        }

        midController.getDownloadedFiles().addListener(new MapChangeListener<String, File>() {
            @Override
            public void onChanged(Change<? extends String, ? extends File> change) {
                if (change.wasAdded() && fileId.equals(change.getKey())) {
                    Platform.runLater(() -> {
                        File completed = change.getValueAdded();
                        if (!isFileComplete(fileId, completed)) {
                            System.out.println("File not complete or size mismatch, will retry later: " + completed);
                            return;
                        }

                        if (completed != null && completed.exists()) {
                            System.out.println("Download finished for " + fileId + ". Opening overlay.");
                            if (kind == UtilHandler.MediaKind.IMAGE) {
                                showImageOverlay(new Image(completed.toURI().toString()), fileId);
                            } else if (kind == UtilHandler.MediaKind.VIDEO) {
                            	runLaterDelay(150, () -> showVideoOverlay(completed.toURI().toString(), fileId));
                            }
                        }
                        midController.getDownloadedFiles().removeListener(this);
                    });
                }
            }
        });

        Thread downloadThread = new Thread(() -> {
            try {
                midController.getConnection().downloadFileByFileId(Long.parseLong(fileId));
            } catch (IOException | NumberFormatException e) {
                System.err.println("Failed to download file " + fileId + ": " + e.getMessage());
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Lỗi tải xuống");
                    alert.setHeaderText(null);
                    alert.setContentText("Không thể tải file: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        });
        downloadThread.setDaemon(true);
        downloadThread.start();
    }
    
    private void runLaterDelay(long millis, Runnable r) {
        new Thread(() -> {
            try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
            Platform.runLater(r);
        }).start();
    }

    
    public void showUser(User u, boolean online, String lastSeenIso) {
        if (infoName != null) infoName.setText(u.getUsername());
        applyStatusLabel(chatStatus, online, lastSeenIso);
    }
    
    public void setAvatar(Image img) {
        if (rightHeaderAvatar != null && img != null) {
            rightHeaderAvatar.setImage(img);
        }
    }

    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private String humanize(String iso, boolean withDot) {
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
}