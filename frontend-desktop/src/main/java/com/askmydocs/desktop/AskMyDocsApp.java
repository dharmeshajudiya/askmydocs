package com.askmydocs.desktop;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class AskMyDocsApp extends Application {

    public static Stage primaryStage;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        stage.setTitle("AskMyDocs");
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        showLogin();
        stage.show();
    }

    public static void showLogin() throws Exception {
        var loader = new FXMLLoader(AskMyDocsApp.class.getResource("fxml/login.fxml"));
        var scene  = new Scene(loader.load(), 440, 520);
        scene.getStylesheets().add(AskMyDocsApp.class.getResource("css/styles.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setWidth(440);
        primaryStage.setHeight(520);
        primaryStage.centerOnScreen();
    }

    public static void showMain() throws Exception {
        var loader = new FXMLLoader(AskMyDocsApp.class.getResource("fxml/main.fxml"));
        var scene  = new Scene(loader.load(), 1100, 700);
        scene.getStylesheets().add(AskMyDocsApp.class.getResource("css/styles.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setWidth(1100);
        primaryStage.setHeight(700);
        primaryStage.centerOnScreen();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
