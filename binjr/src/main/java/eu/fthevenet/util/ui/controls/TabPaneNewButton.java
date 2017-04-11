package eu.fthevenet.util.ui.controls;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A TabPane container with a button to add a new tab
 *
 * @author Frederic Thevenet
 */
public class TabPaneNewButton extends TabPane {

    private Supplier<Optional<Tab>> newTabFactory = () ->Optional.of(new Tab());

    public TabPaneNewButton(){
        this((Tab[])null);
    }

    public TabPaneNewButton(Tab... tabs) {
        super(tabs);

        Platform.runLater(this::positionNewTabButton);

        // Prepare to change the button on screen position if the tabs side changes
        sideProperty().addListener((observable, oldValue, newValue) -> {
           if (newValue != null) {
               positionNewTabButton();
           }
        });
    }


    private void positionNewTabButton(){
        Pane tabHeaderBg = (Pane) this.lookup(".tab-header-background");
        if (tabHeaderBg == null){
            // TabPane is not ready
            return;
        }
        Button newTabButton  = (Button)tabHeaderBg.lookup("#newTabButton");
        newTabButton.getStyleClass().add("new-tab-button");
        // Remove the button if it was already present
        if (newTabButton != null){
            tabHeaderBg.getChildren().remove(newTabButton);
        }
        newTabButton = new Button("+");
        newTabButton.setId("newTabButton");
        newTabButton.setFocusTraversable(false);
        Pane headersRegion = (Pane) this.lookup(".headers-region");
//        newTabButton.setPrefHeight(headersRegion.getHeight());
//        newTabButton.setPrefWidth(headersRegion.getHeight());
//        newTabButton.setMaxHeight(newTabButton.getPrefHeight());
//        newTabButton.setMinHeight(newTabButton.getPrefHeight());
        newTabButton.setAlignment(Pos.CENTER);
        newTabButton.setOnAction(event -> {
            newTabFactory.get().ifPresent(newTab -> {
                getTabs().add(newTab);
                this.getSelectionModel().select(newTab);
            });
        });

        tabHeaderBg.getChildren().add(newTabButton);
        StackPane.setAlignment(newTabButton, Pos.CENTER_LEFT);
        StackPane.setMargin(newTabButton, new Insets(2));

        switch (getSide()) {
            case TOP:
                newTabButton.translateXProperty().bind(
                        headersRegion.widthProperty()//.add(5)
                );
                break;
            case LEFT:
                newTabButton.translateXProperty().bind(
                        tabHeaderBg.widthProperty()
                                .subtract(headersRegion.widthProperty())
                                .subtract(newTabButton.widthProperty())
                        //  .subtract(6)
                );
                break;
            case BOTTOM:
                newTabButton.translateXProperty().bind(
                        tabHeaderBg.widthProperty()
                                .subtract(headersRegion.widthProperty())
                                .subtract(newTabButton.widthProperty())
                        // .subtract(5)
                );
                break;
            case RIGHT:
                newTabButton.translateXProperty().bind(
                        headersRegion.widthProperty()//.add(5)
                );
                break;
        }
    }

    public Supplier<Optional<Tab>> getNewTabFactory() {
        return newTabFactory;
    }

    public void setNewTabFactory(Supplier<Optional<Tab>> newTabFactory) {
        this.newTabFactory = newTabFactory;
    }
}
