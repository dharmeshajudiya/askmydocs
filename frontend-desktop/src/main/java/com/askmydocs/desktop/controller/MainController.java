package com.askmydocs.desktop.controller;

import com.askmydocs.desktop.AskMyDocsApp;
import com.askmydocs.desktop.model.Document;
import com.askmydocs.desktop.service.AuthService;
import com.askmydocs.desktop.service.DocumentService;
import com.askmydocs.desktop.service.QueryService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainController implements Initializable {

    // ── Header ──
    @FXML private Label serverLabel;

    // ── Sidebar ──
    @FXML private VBox              uploadDropZone;
    @FXML private Label             uploadIcon;
    @FXML private Label             uploadStatus;
    @FXML private ProgressIndicator uploadProgress;
    @FXML private ListView<Document> documentList;
    @FXML private Label             docEmptyLabel;

    // ── Chat ──
    @FXML private Label       selectedDocLabel;
    @FXML private ScrollPane  messagesScrollPane;
    @FXML private VBox        messagesBox;
    @FXML private TextArea    questionInput;
    @FXML private Button      sendBtn;

    private final AuthService     authService     = new AuthService();
    private final DocumentService documentService = new DocumentService();
    private final QueryService    queryService    = new QueryService();

    private final ObservableList<Document> documents = FXCollections.observableArrayList();
    private Document selectedDocument;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "poll-thread");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        serverLabel.setText("API: " + com.askmydocs.desktop.service.ApiClient.BASE_URL);
        setupDocumentList();
        setupQuestionInput();
        loadDocuments();
    }

    // ── Document list ─────────────────────────────────────────────────────────

    private void setupDocumentList() {
        documentList.setItems(documents);
        documentList.setCellFactory(lv -> new DocumentCell());
        documentList.getSelectionModel().selectedItemProperty().addListener((obs, old, doc) -> {
            if (doc != null && doc.isReady()) {
                selectedDocument = doc;
                selectedDocLabel.setText("Asking about: " + doc.filename());
                messagesBox.getChildren().clear();
            }
        });
    }

    private void loadDocuments() {
        Task<List<Document>> task = new Task<>() {
            @Override protected List<Document> call() throws Exception {
                return documentService.list();
            }
        };
        task.setOnSucceeded(e -> {
            documents.setAll(task.getValue());
            updateEmptyState();
        });
        task.setOnFailed(e -> showUploadStatus("Failed to load documents", false));
        new Thread(task).start();
    }

    private void updateEmptyState() {
        boolean empty = documents.isEmpty();
        docEmptyLabel.setVisible(empty);
        docEmptyLabel.setManaged(empty);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @FXML
    public void handlePickFile() {
        var chooser = new FileChooser();
        chooser.setTitle("Select Document");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.docx", "*.txt")
        );
        File file = chooser.showOpenDialog(AskMyDocsApp.primaryStage);
        if (file != null) uploadFile(file);
    }

    @FXML
    public void onDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
            uploadDropZone.getStyleClass().add("drop-zone-hover");
        }
        event.consume();
    }

    @FXML
    public void onDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        uploadDropZone.getStyleClass().remove("drop-zone-hover");
        if (db.hasFiles()) {
            uploadFile(db.getFiles().get(0));
        }
        event.setDropCompleted(true);
        event.consume();
    }

    private void uploadFile(File file) {
        setUploadLoading(true);
        showUploadStatus("Uploading " + file.getName() + "…", true);

        Task<Document> task = new Task<>() {
            @Override protected Document call() throws Exception {
                return documentService.upload(file);
            }
        };

        task.setOnSucceeded(e -> {
            Document doc = task.getValue();
            documents.add(0, doc);
            updateEmptyState();
            showUploadStatus("Processing…", true);
            pollStatus(doc.id());
        });

        task.setOnFailed(e -> {
            setUploadLoading(false);
            showUploadStatus("Upload failed: " + task.getException().getMessage(), false);
        });

        new Thread(task).start();
    }

    private void pollStatus(String docId) {
        int[] pollCount = {0};

        Runnable poll = new Runnable() {
            @Override
            public void run() {
                try {
                    Document status = documentService.getStatus(docId);
                    Platform.runLater(() -> {
                        // Update the document in the list
                        for (int i = 0; i < documents.size(); i++) {
                            if (documents.get(i).id().equals(docId)) {
                                documents.set(i, status);
                                break;
                            }
                        }
                    });

                    if (status.isReady()) {
                        Platform.runLater(() -> {
                            setUploadLoading(false);
                            showUploadStatus("Ready! ✓", true);
                            uploadIcon.setText("✅");
                        });
                        return;
                    }

                    if (status.isError()) {
                        Platform.runLater(() -> {
                            setUploadLoading(false);
                            showUploadStatus("Ingestion failed.", false);
                            uploadIcon.setText("❌");
                        });
                        return;
                    }

                    pollCount[0]++;
                    long delay = pollCount[0] > 10
                        ? Math.min(2000L * (1L << (pollCount[0] - 10)), 30_000L)
                        : 2000L;
                    scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);

                } catch (Exception ex) {
                    scheduler.schedule(this, 2000, TimeUnit.MILLISECONDS);
                }
            }
        };

        scheduler.schedule(poll, 2000, TimeUnit.MILLISECONDS);
    }

    private void setUploadLoading(boolean loading) {
        uploadProgress.setVisible(loading);
        uploadProgress.setManaged(loading);
        if (!loading) uploadIcon.setText("📄");
    }

    private void showUploadStatus(String msg, boolean normal) {
        uploadStatus.setText(msg);
        uploadStatus.getStyleClass().removeAll("drop-hint-error");
        if (!normal) uploadStatus.getStyleClass().add("drop-hint-error");
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    private void setupQuestionInput() {
        questionInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                handleSend();
            }
        });
    }

    @FXML
    public void handleSend() {
        if (selectedDocument == null) {
            showSystemMessage("Please select a ready document first.");
            return;
        }
        String question = questionInput.getText().trim();
        if (question.isEmpty()) return;

        questionInput.clear();
        sendBtn.setDisable(true);

        // Append user bubble
        addMessageBubble(question, true, null);

        // Append empty assistant bubble (will be filled by stream)
        Label assistantLabel = addMessageBubble("", false, null);

        queryService.streamQuery(
            selectedDocument.id(),
            question,
            token  -> assistantLabel.setText(assistantLabel.getText() + token),
            ()     -> {
                sendBtn.setDisable(false);
                scrollToBottom();
            },
            error  -> {
                assistantLabel.setText("Error: " + error.getMessage());
                sendBtn.setDisable(false);
            }
        );
    }

    private Label addMessageBubble(String text, boolean isUser, List<?> sources) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(600);
        bubble.getStyleClass().add(isUser ? "bubble-user" : "bubble-assistant");

        HBox row = new HBox(bubble);
        row.getStyleClass().add(isUser ? "bubble-row-user" : "bubble-row-assistant");

        messagesBox.getChildren().add(row);
        scrollToBottom();
        return bubble;
    }

    private void showSystemMessage(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("system-message");
        lbl.setWrapText(true);
        HBox row = new HBox(lbl);
        row.getStyleClass().add("bubble-row-assistant");
        messagesBox.getChildren().add(row);
        scrollToBottom();
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @FXML
    public void handleLogout() {
        scheduler.shutdownNow();
        authService.logout();
        try { AskMyDocsApp.showLogin(); }
        catch (Exception e) { e.printStackTrace(); }
    }

    // ── Document cell ─────────────────────────────────────────────────────────

    private class DocumentCell extends ListCell<Document> {
        @Override
        protected void updateItem(Document doc, boolean empty) {
            super.updateItem(doc, empty);
            if (empty || doc == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox card = new VBox(4);
            card.getStyleClass().add("doc-card");

            HBox titleRow = new HBox(6);
            Label icon = new Label(fileIcon(doc.filename()));
            Label name = new Label(doc.filename());
            name.getStyleClass().add("doc-name");
            name.setMaxWidth(160);

            Label statusBadge = new Label(doc.status());
            statusBadge.getStyleClass().addAll("status-badge", "status-" + doc.status());

            Button del = new Button("✕");
            del.getStyleClass().add("delete-btn");
            del.setOnAction(e -> {
                if (new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete \"" + doc.filename() + "\" and all its data?",
                        ButtonType.YES, ButtonType.NO)
                    .showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                    deleteDocument(doc);
                }
            });

            titleRow.getChildren().addAll(icon, name, statusBadge, del);

            String chunks = doc.isReady() && doc.totalChunks() != null
                ? doc.totalChunks() + " chunks" : "";
            Label meta = new Label(chunks);
            meta.getStyleClass().add("doc-meta");

            card.getChildren().addAll(titleRow, meta);
            setGraphic(card);
            setText(null);

            setDisable(!doc.isReady());
        }

        private String fileIcon(String filename) {
            String f = filename.toLowerCase();
            if (f.endsWith(".pdf"))  return "📕";
            if (f.endsWith(".docx")) return "📘";
            return "📄";
        }
    }

    private void deleteDocument(Document doc) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                documentService.delete(doc.id());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            documents.removeIf(d -> d.id().equals(doc.id()));
            updateEmptyState();
            if (selectedDocument != null && selectedDocument.id().equals(doc.id())) {
                selectedDocument = null;
                selectedDocLabel.setText("Select a document from the sidebar to start");
                messagesBox.getChildren().clear();
            }
        });
        task.setOnFailed(e ->
            new Alert(Alert.AlertType.ERROR, "Delete failed: " + task.getException().getMessage()).show()
        );
        new Thread(task).start();
    }
}
