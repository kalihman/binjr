/*
 *    Copyright 2016-2020 Frederic Thevenet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package eu.binjr.core.controllers;

import com.sun.javafx.charts.Legend;
import eu.binjr.common.io.IOUtils;
import eu.binjr.common.javafx.bindings.BindingManager;
import eu.binjr.common.javafx.charts.*;
import eu.binjr.common.javafx.controls.*;
import eu.binjr.common.logging.Logger;
import eu.binjr.common.logging.Profiler;
import eu.binjr.core.data.adapters.DataAdapter;
import eu.binjr.core.data.adapters.SourceBinding;
import eu.binjr.core.data.adapters.TimeSeriesBinding;
import eu.binjr.core.data.async.AsyncTaskManager;
import eu.binjr.core.data.exceptions.DataAdapterException;
import eu.binjr.core.data.exceptions.NoAdapterFoundException;
import eu.binjr.core.data.workspace.Chart;
import eu.binjr.core.data.workspace.*;
import eu.binjr.core.dialogs.Dialogs;
import eu.binjr.core.preferences.SnapshotOutputScale;
import eu.binjr.core.preferences.UserHistory;
import eu.binjr.core.preferences.UserPreferences;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Path;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.controlsfx.control.MaskerPane;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static javafx.scene.layout.Region.USE_COMPUTED_SIZE;

/**
 * The controller class for the time series view.
 *
 * @author Frederic Thevenet
 */
public class XYChartsWorksheetController extends WorksheetController {
    public static final String WORKSHEET_VIEW_FXML = "/eu/binjr/views/XYChartsWorksheetView.fxml";
    private static final DataFormat SERIALIZED_MIME_TYPE = new DataFormat("application/x-java-serialized-object");
    private static final Logger logger = Logger.create(XYChartsWorksheetController.class);
    private static final double Y_AXIS_SEPARATION = 10;
    private static final PseudoClass HOVER_PSEUDO_CLASS = PseudoClass.getPseudoClass("hover");
    private final UserPreferences userPrefs = UserPreferences.getInstance();
    private final ToggleGroup editButtonsGroup = new ToggleGroup();
    private final IntegerProperty nbBusyPlotTasks = new SimpleIntegerProperty(0);
    @FXML
    public AnchorPane root;
    @FXML
    Pane newChartDropTarget;
    private List<ChartViewPort> viewPorts = new ArrayList<>();
    private XYChartsWorksheet worksheet;
    private volatile boolean preventReload = false;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    @FXML
    private Pane chartParent;
    @FXML
    private AnchorPane chartViewport;
    @FXML
    private AnchorPane chartView;
    @FXML
    private TextField yMinRange;
    @FXML
    private TextField yMaxRange;
    @FXML
    private Accordion seriesTableContainer;
    @FXML
    private SplitPane splitPane;
    @FXML
    private Button backButton;
    @FXML
    private Button forwardButton;
    @FXML
    private Button refreshButton;
    @FXML
    private Button snapshotButton;
    @FXML
    private ToggleButton vCrosshair;
    @FXML
    private ToggleButton hCrosshair;
    @FXML
    private Button addChartButton;
    @FXML
    private MaskerPane worksheetMaskerPane;
    @FXML
    private ContextMenu seriesListMenu;
    @FXML
    private MenuButton selectChartLayout;
    @FXML
    private TimeRangePicker timeRangePicker;
    @FXML
    private AnchorPane chartsLegendsPane;
    @FXML
    private DrawerPane chartProperties;
    @FXML
    private ToolBar chartsToolbar;
    @FXML
    private HBox navigationToolbar;
    private ChartViewportsState currentState;

    private Pane worksheetTitleBlock;
    private VBox screenshotCanvas;
    private Profiler worksheetRefreshProfiler = null;

    public XYChartsWorksheetController(MainViewController parentController, XYChartsWorksheet worksheet, Collection<DataAdapter<Double>> sourcesAdapters)
            throws NoAdapterFoundException {
        super(parentController);
        this.worksheet = worksheet;
        // Attach bindings
        for (Chart chart : worksheet.getCharts()) {
            for (TimeSeriesInfo<Double> s : chart.getSeries()) {
                UUID id = s.getBinding().getAdapterId();
                DataAdapter<Double> da = sourcesAdapters
                        .stream()
                        .filter(a -> (id != null && a != null && a.getId() != null) && id.equals(a.getId()))
                        .findAny()
                        .orElseThrow(() -> new NoAdapterFoundException("Failed to find a valid adapter with id " + (id != null ? id.toString() : "null")));
                s.getBinding().setAdapter(da);
            }
        }
    }

    private static String colorToRgbaString(Color c) {
        return String.format("rgba(%d,%d,%d,%f)", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255), c.getOpacity());
    }

    private Optional<List<Chart>> treeItemsAsChartList(Collection<TreeItem<SourceBinding>> treeItems, Node dlgRoot) {
        var charts = new ArrayList<Chart>();
        var totalBindings = 0;
        for (var treeItem : treeItems) {
            for (var t : TreeViewUtils.splitAboveLeaves(treeItem, true)) {
                if (t.getValue() instanceof TimeSeriesBinding) {
                    TimeSeriesBinding binding = (TimeSeriesBinding) t.getValue();
                    Chart chart = new Chart(
                            binding.getLegend(),
                            binding.getGraphType(),
                            binding.getUnitName(),
                            binding.getUnitPrefix()
                    );
                    for (var b : TreeViewUtils.flattenLeaves(t)) {
                        if (b instanceof TimeSeriesBinding) {
                            chart.addSeries(TimeSeriesInfo.fromBinding((TimeSeriesBinding) b));
                            totalBindings++;
                        }
                    }
                    charts.add(chart);
                }
            }
        }
        if (totalBindings >= UserPreferences.getInstance().maxSeriesPerChartBeforeWarning.get().intValue()) {
            if (Dialogs.confirmDialog(dlgRoot,
                    "This action will add " + totalBindings + " series on a single worksheet.",
                    "Are you sure you want to proceed?",
                    ButtonType.YES, ButtonType.NO) != ButtonType.YES) {
                return Optional.empty();
            }
        }
        return Optional.of(charts);
    }

    private ChartPropertiesController buildChartPropertiesController(Chart chart) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/eu/binjr/views/ChartPropertiesView.fxml"));
        ChartPropertiesController propertiesController = new ChartPropertiesController(worksheet, chart);
        loader.setController(propertiesController);
        Pane settingsPane = loader.load();
        chartProperties.getChildren().add(settingsPane);
        AnchorPane.setRightAnchor(settingsPane, 0.0);
        AnchorPane.setBottomAnchor(settingsPane, 0.0);
        AnchorPane.setTopAnchor(settingsPane, 0.0);
        AnchorPane.setLeftAnchor(settingsPane, 0.0);
        settingsPane.getStyleClass().add("toolPane");
        return propertiesController;
    }

    @Override
    public Worksheet getWorksheet() {
        return this.worksheet;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        assert root != null : "fx:id\"root\" was not injected!";
        assert chartParent != null : "fx:id\"chartParent\" was not injected!";
        assert seriesTableContainer != null : "fx:id\"seriesTableContainer\" was not injected!";
        assert backButton != null : "fx:id\"backButton\" was not injected!";
        assert forwardButton != null : "fx:id\"forwardButton\" was not injected!";
        assert refreshButton != null : "fx:id\"refreshButton\" was not injected!";
        assert vCrosshair != null : "fx:id\"vCrosshair\" was not injected!";
        assert hCrosshair != null : "fx:id\"hCrosshair\" was not injected!";
        assert snapshotButton != null : "fx:id\"snapshotButton\" was not injected!";

        try {
            bindingManager.bind(worksheetMaskerPane.visibleProperty(), nbBusyPlotTasks.greaterThan(0));
            bindingManager.attachListener(worksheetMaskerPane.visibleProperty(),
                    (ChangeListener<Boolean>) (observable, oldValue, newValue) -> {
                        if (!oldValue & newValue) {
                            worksheetRefreshProfiler = Profiler.start(
                                    "Worksheet " + worksheet.getName() + " refresh total elapsed time",
                                    logger::perf);
                        } else if (oldValue & !newValue) {
                            worksheetRefreshProfiler.close();
                        }
                    }
            );
            initChartViewPorts();
            initNavigationPane();
            initTableViewPane();
            Platform.runLater(() -> invalidateAll(false, false, false));
            bindingManager.attachListener(userPrefs.downSamplingEnabled.property(), ((observable, oldValue, newValue) -> refresh()));
            bindingManager.attachListener(userPrefs.downSamplingThreshold.property(), ((observable, oldValue, newValue) -> {
                if (userPrefs.downSamplingEnabled.get())
                    refresh();
            }));
            bindingManager.attachListener(userPrefs.downSamplingAlgorithm.property(), ((observable, oldValue, newValue) -> {
                if (userPrefs.downSamplingEnabled.get())
                    refresh();
            }));

            newChartDropTarget.setOnDragOver(bindingManager.registerHandler(this::handleDragOverNewChartTarget));
            newChartDropTarget.setOnDragDropped(bindingManager.registerHandler(this::handleDragDroppedONewChartTarget));
            newChartDropTarget.setOnDragEntered(bindingManager.registerHandler(event -> newChartDropTarget.pseudoClassStateChanged(HOVER_PSEUDO_CLASS, true)));
            newChartDropTarget.setOnDragExited(bindingManager.registerHandler(event -> newChartDropTarget.pseudoClassStateChanged(HOVER_PSEUDO_CLASS, false)));
            bindingManager.bind(newChartDropTarget.managedProperty(), parentController.treeItemDragAndDropInProgressProperty());
            bindingManager.bind(newChartDropTarget.visibleProperty(), parentController.treeItemDragAndDropInProgressProperty());
        } catch (Exception e) {
            Platform.runLater(() -> Dialogs.notifyException("Error loading worksheet controller", e, root));
        }
        super.initialize(location, resources);
    }

    @Override
    protected void setEditChartMode(Boolean newValue) {
        if (!newValue) {
            bindingManager.suspend(worksheet.dividerPositionProperty());
            splitPane.setDividerPositions(1.0);
            chartsLegendsPane.setVisible(false);
            chartsLegendsPane.setMaxHeight(0.0);
        } else {
            chartsLegendsPane.setMaxHeight(Double.MAX_VALUE);
            chartsLegendsPane.setVisible(true);
            splitPane.setDividerPositions(worksheet.getDividerPosition());
            bindingManager.resume(worksheet.dividerPositionProperty());
        }
        setShowPropertiesPane(newValue);
        super.setEditChartMode(newValue);
    }

    private ZonedDateTimeAxis buildTimeAxis() {
        ZonedDateTimeAxis axis = new ZonedDateTimeAxis(worksheet.getTimeZone());
        bindingManager.bind(axis.zoneIdProperty(), worksheet.timeZoneProperty());
        axis.setAnimated(false);
        axis.setSide(Side.BOTTOM);
        return axis;
    }

    private void initChartViewPorts() throws IOException {
        ZonedDateTimeAxis defaultXAxis = buildTimeAxis();
        for (Chart currentChart : worksheet.getCharts()) {
            ZonedDateTimeAxis xAxis;
            switch (worksheet.getChartLayout()) {
                case OVERLAID:
                    xAxis = defaultXAxis;
                    break;
                case STACKED:
                default:
                    xAxis = buildTimeAxis();
                    break;
            }
            StableTicksAxis<Double> yAxis;
            if (currentChart.getUnitPrefixes() == UnitPrefixes.BINARY) {
                yAxis = new BinaryStableTicksAxis<>();
            } else {
                yAxis = new MetricStableTicksAxis<>();
            }
            yAxis.autoRangingProperty().bindBidirectional(currentChart.autoScaleYAxisProperty());
            yAxis.setAnimated(false);
            yAxis.setTickSpacing(30);

            bindingManager.bind(yAxis.labelProperty(),
                    Bindings.createStringBinding(
                            () -> String.format("%s - %s", currentChart.getName(), currentChart.getUnit()),
                            currentChart.nameProperty(),
                            currentChart.unitProperty()));
            XYChart<ZonedDateTime, Double> viewPort;
            switch (currentChart.getChartType()) {
                case AREA:
                    viewPort = new AreaChart<>(xAxis, yAxis);
                    ((AreaChart) viewPort).setCreateSymbols(false);
                    break;
                case STACKED:
                    viewPort = new NaNStackedAreaChart<>(xAxis, yAxis);
                    ((StackedAreaChart) viewPort).setCreateSymbols(false);
                    break;
                case SCATTER:
                    viewPort = new ScatterChart<>(xAxis, yAxis);
                    break;
                case LINE:
                default:
                    viewPort = new LineChart<>(xAxis, yAxis);
                    ((LineChart) viewPort).setCreateSymbols(false);
            }
            viewPort.setCache(true);
            viewPort.setCacheHint(CacheHint.SPEED);
            viewPort.setCacheShape(true);
            viewPort.setFocusTraversable(true);
            viewPort.legendVisibleProperty().bind(worksheet.editModeEnabledProperty()
                    .not()
                    .and(Bindings.equal(ChartLayout.STACKED, (ObjectProperty) worksheet.chartLayoutProperty())));
            viewPort.setLegendSide(Side.BOTTOM);

            viewPort.setAnimated(false);
            viewPorts.add(new ChartViewPort(currentChart, viewPort, buildChartPropertiesController(currentChart)));
            viewPort.getYAxis().addEventFilter(MouseEvent.MOUSE_CLICKED, bindingManager.registerHandler(event -> {
                for (int i = 0; i < viewPorts.size(); i++) {
                    if (viewPorts.get(i).getChart() == viewPort) {
                        worksheet.setSelectedChart(i);
                    }
                }
            }));
            bindingManager.bind(((StableTicksAxis) viewPort.getYAxis()).selectionMarkerVisibleProperty(), worksheet.editModeEnabledProperty());
            viewPort.setOnDragOver(getBindingManager().registerHandler(this::handleDragOverWorksheetView));
            viewPort.setOnDragDropped(getBindingManager().registerHandler(this::handleDragDroppedOnWorksheetView));

            viewPort.setOnDragEntered(getBindingManager().registerHandler(event -> {
                if (worksheet.getChartLayout() == ChartLayout.OVERLAID) {
                    ((StableTicksAxis) viewPort.getYAxis()).getSelectionMarker().pseudoClassStateChanged(HOVER_PSEUDO_CLASS, true);
                } else {
                    //FIXME WARNING: Caught 'java.lang.ClassCastException: class java.lang.String cannot be cast to class javafx.scene.paint.Paint
                    // (java.lang.String is in module java.base of loader 'bootstrap'; javafx.scene.paint.Paint is in unnamed module of loader 'app')'
                    // while converting value for '-fx-background-color' from inline style on StackedAreaChart@d8b1439[styleClass=chart]
                    viewPort.setStyle("-fx-background-color:  -fx-accent-translucide;");
                }
            }));
            viewPort.setOnDragExited(getBindingManager().registerHandler(event -> {
                if (worksheet.getChartLayout() == ChartLayout.OVERLAID) {
                    ((StableTicksAxis) viewPort.getYAxis()).getSelectionMarker().pseudoClassStateChanged(HOVER_PSEUDO_CLASS, false);
                } else {
                    viewPort.setStyle("-fx-background-color:  -binjr-pane-background-color;");
                }
            }));
            // Add buttons to chart axis
            Button closeButton = new ToolButtonBuilder<Button>(bindingManager)
                    .setText("Close")
                    .setTooltip("Remove this chart from the worksheet.")
                    .setStyleClass("exit")
                    .setIconStyleClass("cross-icon", "small-icon")
                    .setAction(event -> warnAndRemoveChart(currentChart))
                    .bind(Button::disableProperty, Bindings.createBooleanBinding(() -> worksheet.getCharts().size() > 1, worksheet.getCharts()).not())
                    .build(Button::new);
            ToggleButton editButton = new ToolButtonBuilder<ToggleButton>(bindingManager)
                    .setText("Settings")
                    .setTooltip("Edit the chart's settings")
                    .setStyleClass("dialog-button")
                    .setIconStyleClass("settings-icon", "small-icon")
                    .bindBidirectionnal(ToggleButton::selectedProperty, currentChart.showPropertiesProperty())
                    .build(ToggleButton::new);
            var toolBar = new HBox(editButton, closeButton);
            toolBar.getStyleClass().add("worksheet-tool-bar");
            toolBar.visibleProperty().bind(yAxis.getSelectionMarker().hoverProperty());
            yAxis.getSelectionMarker().getChildren().add(toolBar);
        }

        bindingManager.bind(selectChartLayout.disableProperty(),
                Bindings.createBooleanBinding(() -> worksheet.getCharts().size() > 1, worksheet.getCharts()).not());
        selectChartLayout.getItems().setAll(Arrays.stream(ChartLayout.values()).map(chartLayout -> {
            MenuItem item = new MenuItem(chartLayout.toString());
            item.setOnAction(bindingManager.registerHandler(event -> worksheet.setChartLayout(chartLayout)));
            return item;
        }).collect(Collectors.toList()));

        this.worksheetTitleBlock = buildTitleBlock();
        screenshotCanvas = new VBox();
        screenshotCanvas.getStyleClass().add("chart-viewport-parent");
        screenshotCanvas.setAlignment(Pos.TOP_LEFT);
        screenshotCanvas.getChildren().add(worksheetTitleBlock);
        switch (worksheet.getChartLayout()) {
            case OVERLAID:
                setupOverlayChartLayout(screenshotCanvas);
                break;
            case STACKED:
                setupStackedChartLayout(screenshotCanvas);
                break;
        }
        if (viewPorts.size() > 1) {
            ChangeListener<Integer> changeListener = (observable, oldValue, newValue) -> {
                ChartViewPort previousChart;
                if (oldValue > -1 && viewPorts.size() > oldValue && (previousChart = viewPorts.get(oldValue)) != null) {
                    ((StableTicksAxis) previousChart.getChart().getYAxis()).setSelected(false);
                }
                ChartViewPort selectedChart;
                if (newValue > -1 && viewPorts.size() > newValue && (selectedChart = viewPorts.get(newValue)) != null) {
                    ((StableTicksAxis) selectedChart.getChart().getYAxis()).setSelected(true);
                }
                TitledPane seriesPane;
                if (newValue > -1 && seriesTableContainer.getPanes().size() > newValue && (seriesPane = seriesTableContainer.getPanes().get(newValue)) != null) {
                    seriesPane.setExpanded(true);
                }
            };
            bindingManager.attachListener(worksheet.selectedChartProperty(), changeListener);
            changeListener.changed(worksheet.selectedChartProperty(), 0, worksheet.getSelectedChart());
        }
    }

    private void setupOverlayChartLayout(VBox vBox) {
        var pane = new AnchorPane();
        for (int i = 0; i < viewPorts.size(); i++) {
            ChartViewPort v = viewPorts.get(i);
            XYChart<ZonedDateTime, Double> chart = v.getChart();
            int nbAdditionalCharts = worksheet.getCharts().size() - 1;
            DoubleBinding n = Bindings.createDoubleBinding(
                    () -> viewPorts.stream()
                            .filter(c -> !c.getChart().equals(chart))
                            .map(c -> c.getChart().getYAxis().getWidth())
                            .reduce(Double::sum).orElse(0.0) + (Y_AXIS_SEPARATION * nbAdditionalCharts),
                    viewPorts.stream().map(c -> c.getChart().getYAxis().widthProperty()).toArray(ReadOnlyDoubleProperty[]::new)
            );
            HBox hBox = new HBox(chart);
            hBox.setPickOnBounds(false);
            chart.setPickOnBounds(false);
            chart.getChildrenUnmodifiable()
                    .stream()
                    .filter(node -> node.getStyleClass().contains("chart-content"))
                    .findFirst()
                    .ifPresent(node -> node.setPickOnBounds(false));
            hBox.setAlignment(Pos.CENTER_LEFT);
            bindingManager.bind(hBox.prefHeightProperty(), chartParent.heightProperty());
            bindingManager.bind(hBox.prefWidthProperty(), chartParent.widthProperty());
            bindingManager.bind(chart.minWidthProperty(), chartParent.widthProperty().subtract(n));
            bindingManager.bind(chart.prefWidthProperty(), chartParent.widthProperty().subtract(n));
            bindingManager.bind(chart.maxWidthProperty(), chartParent.widthProperty().subtract(n));
            if (i == 0) {
                chart.getYAxis().setSide(Side.LEFT);
            } else {
                chart.getYAxis().setSide(Side.RIGHT);
                chart.setVerticalZeroLineVisible(false);
                chart.setHorizontalZeroLineVisible(false);
                chart.setVerticalGridLinesVisible(false);
                chart.setHorizontalGridLinesVisible(false);
                bindingManager.bind(chart.translateXProperty(), viewPorts.get(0).getChart().getYAxis().widthProperty());
                bindingManager.bind(chart.getYAxis().translateXProperty(), Bindings.createDoubleBinding(
                        () -> viewPorts.stream()
                                .filter(c -> viewPorts.indexOf(c) != 0 && viewPorts.indexOf(c) < viewPorts.indexOf(v))
                                .map(c -> c.getChart().getYAxis().getWidth())
                                .reduce(Double::sum).orElse(0.0) + Y_AXIS_SEPARATION * (viewPorts.indexOf(v) - 1),
                        viewPorts.stream().map(c -> c.getChart().getYAxis().widthProperty()).toArray(ReadOnlyDoubleProperty[]::new)));
            }
            pane.getChildren().add(hBox);
        }
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        LinkedHashMap<XYChart<ZonedDateTime, Double>, Function<Double, String>> map = new LinkedHashMap<>();
        viewPorts.forEach(v -> map.put(v.getChart(), v.getPrefixFormatter()::format));
        var crossHair = new XYChartCrosshair<>(map, pane, dateTimeFormatter::format);
        viewPorts.forEach(v -> v.setCrosshair(crossHair));
        crossHair.onSelectionDone(s -> {
            logger.debug(() -> "Applying zoom selection: " + s.toString());
            currentState.setSelection(convertSelection(s), true);
        });
        hCrosshair.selectedProperty().bindBidirectional(userPrefs.horizontalMarkerOn.property());
        vCrosshair.selectedProperty().bindBidirectional(userPrefs.verticalMarkerOn.property());
        bindingManager.bind(crossHair.horizontalMarkerVisibleProperty(),
                Bindings.createBooleanBinding(() -> userPrefs.shiftPressed.get() || hCrosshair.isSelected(),
                        hCrosshair.selectedProperty(),
                        userPrefs.shiftPressed.property()));
        bindingManager.bind(crossHair.verticalMarkerVisibleProperty(),
                Bindings.createBooleanBinding(() -> userPrefs.ctrlPressed.get() || vCrosshair.isSelected(),
                        vCrosshair.selectedProperty(),
                        userPrefs.ctrlPressed.property()));
        vBox.getChildren().add(pane);
        chartParent.getChildren().add(vBox);
    }

    private void setupStackedChartLayout(VBox vBox) {
        bindingManager.bind(vBox.prefHeightProperty(), chartParent.heightProperty());
        bindingManager.bind(vBox.prefWidthProperty(), chartParent.widthProperty());
        for (int i = 0; i < viewPorts.size(); i++) {
            ChartViewPort v = viewPorts.get(i);
            XYChart<ZonedDateTime, Double> chart = v.getChart();
            vBox.getChildren().add(chart);
            chart.maxHeight(Double.MAX_VALUE);
            chart.minHeightProperty().bind(Bindings.createDoubleBinding(
                    () -> worksheet.isEditModeEnabled() ? 150.0 : 250.0,
                    worksheet.editModeEnabledProperty()
            ));
            VBox.setVgrow(chart, Priority.ALWAYS);
            chart.getYAxis().setSide(Side.LEFT);
            chart.getYAxis().setPrefWidth(60.0);
            chart.getYAxis().setMinWidth(60.0);
            chart.getYAxis().setMaxWidth(60.0);
        }
        var scrollPane = new ScrollPane(vBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("skinnable-pane-border");
        chartParent.getChildren().add(scrollPane);
        // setup crosshair
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        LinkedHashMap<XYChart<ZonedDateTime, Double>, Function<Double, String>> map = new LinkedHashMap<>();
        map.put(viewPorts.get(0).getChart(), viewPorts.get(0).getPrefixFormatter()::format);
        var crossHair = new XYChartCrosshair<>(map, chartParent, dateTimeFormatter::format);
        var nbChartObs = new SimpleIntegerProperty(viewPorts.size());
        var crosshairHeightBinding = BooleanBinding.booleanExpression(userPrefs.fullHeightCrosshairMarker.property())
                .and(Bindings.greaterThan(nbChartObs, 1));
        bindingManager.bind(crossHair.displayFullHeightMarkerProperty(), crosshairHeightBinding);
        viewPorts.get(0).setCrosshair(crossHair);
        crossHair.onSelectionDone(s -> {
            logger.debug(() -> "Applying zoom selection: " + s.toString());
            currentState.setSelection(convertSelection(s), true);
        });
        bindingManager.bindBidirectional(hCrosshair.selectedProperty(), userPrefs.horizontalMarkerOn.property());
        bindingManager.bindBidirectional(vCrosshair.selectedProperty(), userPrefs.verticalMarkerOn.property());
        bindingManager.bind(crossHair.horizontalMarkerVisibleProperty(),
                Bindings.createBooleanBinding(() ->
                                userPrefs.shiftPressed.get() || hCrosshair.isSelected(),
                        hCrosshair.selectedProperty(),
                        userPrefs.shiftPressed.property()));
        bindingManager.bind(crossHair.verticalMarkerVisibleProperty(),
                Bindings.createBooleanBinding(() ->
                                userPrefs.ctrlPressed.get() || vCrosshair.isSelected(),
                        vCrosshair.selectedProperty(),
                        userPrefs.ctrlPressed.property()));
        for (int i = 1; i < viewPorts.size(); i++) {
            LinkedHashMap<XYChart<ZonedDateTime, Double>, Function<Double, String>> m = new LinkedHashMap<>();
            m.put(viewPorts.get(i).getChart(), viewPorts.get(i).getPrefixFormatter()::format);
            XYChartCrosshair<ZonedDateTime, Double> ch = new XYChartCrosshair<>(m, chartParent, dateTimeFormatter::format);
            ch.displayFullHeightMarkerProperty().bind(crosshairHeightBinding);
            ch.onSelectionDone(s -> {
                logger.debug(() -> "Applying zoom selection: " + s.toString());
                currentState.setSelection(convertSelection(s), true);
            });
            bindingManager.bind(ch.horizontalMarkerVisibleProperty(),
                    Bindings.createBooleanBinding(() ->
                                    userPrefs.shiftPressed.get() || hCrosshair.isSelected(),
                            hCrosshair.selectedProperty(),
                            userPrefs.shiftPressed.property()));
            bindingManager.bind(ch.verticalMarkerVisibleProperty(),
                    Bindings.createBooleanBinding(() ->
                                    userPrefs.ctrlPressed.get() || vCrosshair.isSelected(),
                            vCrosshair.selectedProperty(),
                            userPrefs.ctrlPressed.property()));
            viewPorts.get(i).setCrosshair(ch);
        }
    }

    private Pane buildTitleBlock() {
        VBox titleBlock = new VBox();
        titleBlock.getStyleClass().add("worksheet-title-block");
        titleBlock.setVisible(false);
        titleBlock.setManaged(false);
        Label title = new Label();
        title.getStyleClass().add("title-text");
        title.textProperty().bind(worksheet.nameProperty());
        title.setGraphic(ToolButtonBuilder.makeIconNode(Pos.CENTER_LEFT, "chart-icon"));
        Label range = new Label();
        range.getStyleClass().add("range-text");
        range.textProperty().bind(timeRangePicker.textProperty());
        range.setGraphic(ToolButtonBuilder.makeIconNode(Pos.CENTER_LEFT, "time-icon"));
        titleBlock.getChildren().addAll(title, range);
        return titleBlock;
    }

    @Override
    public Property<TimeRange> selectedRangeProperty() {
        return this.timeRangePicker.selectedRangeProperty();
    }

    private void initNavigationPane() {
        backButton.setOnAction(bindingManager.registerHandler(this::handleHistoryBack));
        forwardButton.setOnAction(bindingManager.registerHandler(this::handleHistoryForward));
        refreshButton.setOnAction(bindingManager.registerHandler(this::handleRefresh));
        snapshotButton.setOnAction(bindingManager.registerHandler(this::handleTakeSnapshot));
        bindingManager.bind(backButton.disableProperty(), worksheet.getHistory().backward().emptyProperty());
        bindingManager.bind(forwardButton.disableProperty(), worksheet.getHistory().forward().emptyProperty());
        addChartButton.setOnAction(bindingManager.registerHandler(this::handleAddNewChart));
        currentState = new ChartViewportsState(this, worksheet.getFromDateTime(), worksheet.getToDateTime());
        timeRangePicker.timeRangeLinkedProperty().bindBidirectional(worksheet.timeRangeLinkedProperty());
        timeRangePicker.zoneIdProperty().bindBidirectional(worksheet.timeZoneProperty());
        timeRangePicker.initSelectedRange(TimeRange.of(currentState.getStartX(), currentState.getEndX()));
        timeRangePicker.setOnSelectedRangeChanged((observable, oldValue, newValue) -> {
            currentState.setSelection(currentState.selectTimeRange(newValue.getBeginning(), newValue.getEnd()), true);
        });
        timeRangePicker.setOnResetInterval(() -> {
            try {
                return worksheet.getCharts().get(0).getInitialTimeRange();
            } catch (Exception e) {
                Dialogs.notifyException("Error resetting range", e);
            }
            return TimeRange.of(ZonedDateTime.now().minusHours(24), ZonedDateTime.now());
        });

        currentState.timeRangeProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                timeRangePicker.updateSelectedRange(newValue);
            }
        });
    }

    private Map<Chart, XYChartSelection<ZonedDateTime, Double>> convertSelection(Map<XYChart<ZonedDateTime, Double>, XYChartSelection<ZonedDateTime, Double>> selection) {
        Map<Chart, XYChartSelection<ZonedDateTime, Double>> result = new HashMap<>();
        selection.forEach((xyChart, xyChartSelection) -> {
            viewPorts.stream()
                    .filter(v -> v.getChart().equals(xyChart))
                    .findFirst()
                    .ifPresent(viewPort -> result.put(viewPort.getDataStore(), xyChartSelection));
        });
        return result;
    }

    private void handleAddNewChart(ActionEvent actionEvent) {
        worksheet.getCharts().add(new Chart());
    }

    private void initTableViewPane() {
        for (ChartViewPort currentViewPort : viewPorts) {
            currentViewPort.getSeriesTable().getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            CheckBox showAllCheckBox = new CheckBox();
            TableColumn<TimeSeriesInfo<Double>, Boolean> visibleColumn = new TableColumn<>();
            visibleColumn.setGraphic(showAllCheckBox);
            visibleColumn.setSortable(false);
            visibleColumn.setResizable(false);
            visibleColumn.setPrefWidth(32);
            InvalidationListener isVisibleListener = (observable) -> {
                boolean andAll = true;
                boolean orAll = false;
                for (TimeSeriesInfo<Double> t : currentViewPort.getDataStore().getSeries()) {
                    andAll &= t.isSelected();
                    orAll |= t.isSelected();
                }
                showAllCheckBox.setIndeterminate(Boolean.logicalXor(andAll, orAll));
                showAllCheckBox.setSelected(andAll);
            };

            ChangeListener<Boolean> refreshListener = (observable, oldValue, newValue) -> {
                if (worksheet.getChartLayout() == ChartLayout.OVERLAID) {
                    invalidateAll(false, false, false);
                } else {
                    invalidate(currentViewPort, false, false);
                }
            };

            currentViewPort.getDataStore().getSeries().forEach(doubleTimeSeriesInfo -> {
                bindingManager.attachListener(doubleTimeSeriesInfo.selectedProperty(), refreshListener);
                bindingManager.attachListener(doubleTimeSeriesInfo.selectedProperty(), isVisibleListener);
                // Explicitly call the listener to initialize the proper status of the checkbox
                isVisibleListener.invalidated(null);
            });

            visibleColumn.setCellValueFactory(p -> p.getValue().selectedProperty());
            visibleColumn.setCellFactory(CheckBoxTableCell.forTableColumn(visibleColumn));

            showAllCheckBox.setOnAction(bindingManager.registerHandler(event -> {
                ChangeListener<Boolean> r = (observable, oldValue, newValue) -> {
                    if (worksheet.getChartLayout() == ChartLayout.OVERLAID) {
                        invalidateAll(false, false, false);
                    } else {
                        invalidate(currentViewPort, false, false);
                    }
                };
                boolean b = ((CheckBox) event.getSource()).isSelected();
                currentViewPort.getDataStore().getSeries().forEach(s -> bindingManager.detachAllChangeListeners(s.selectedProperty()));
                currentViewPort.getDataStore().getSeries().forEach(t -> t.setSelected(b));
                r.changed(null, null, null);
                currentViewPort.getDataStore().getSeries().forEach(s -> bindingManager.attachListener(s.selectedProperty(), r));
            }));

            DecimalFormatTableCellFactory<TimeSeriesInfo<Double>, String> alignRightCellFactory = new DecimalFormatTableCellFactory<>();
            alignRightCellFactory.setAlignment(TextAlignment.RIGHT);

            TableColumn<TimeSeriesInfo<Double>, Color> colorColumn = new TableColumn<>();
            colorColumn.setSortable(false);
            colorColumn.setResizable(false);
            colorColumn.setPrefWidth(32);

            TableColumn<TimeSeriesInfo<Double>, Boolean> nameColumn = new TableColumn<>("Name");
            nameColumn.setSortable(false);
            nameColumn.setPrefWidth(160);
            nameColumn.setCellValueFactory(new PropertyValueFactory<>("displayName"));

            TableColumn<TimeSeriesInfo<Double>, String> minColumn = new TableColumn<>("Min.");
            minColumn.setSortable(false);
            minColumn.setPrefWidth(75);
            minColumn.setCellFactory(alignRightCellFactory);

            TableColumn<TimeSeriesInfo<Double>, String> maxColumn = new TableColumn<>("Max.");
            maxColumn.setSortable(false);
            maxColumn.setPrefWidth(75);
            maxColumn.setCellFactory(alignRightCellFactory);

            TableColumn<TimeSeriesInfo<Double>, String> avgColumn = new TableColumn<>("Avg.");
            avgColumn.setSortable(false);
            avgColumn.setPrefWidth(75);
            avgColumn.setCellFactory(alignRightCellFactory);

            TableColumn<TimeSeriesInfo<Double>, String> currentColumn = new TableColumn<>("Current");
            currentColumn.setSortable(false);
            currentColumn.setPrefWidth(75);
            currentColumn.setCellFactory(alignRightCellFactory);
            currentColumn.getStyleClass().add("column-bold-text");

            TableColumn<TimeSeriesInfo<Double>, String> pathColumn = new TableColumn<>("Path");
            pathColumn.setSortable(false);
            pathColumn.setPrefWidth(400);


            currentColumn.setVisible(getSelectedViewPort().getCrosshair().isVerticalMarkerVisible());
            bindingManager.attachListener(getSelectedViewPort().getCrosshair().verticalMarkerVisibleProperty(),
                    (ChangeListener<Boolean>) (observable, oldValue, newValue) -> currentColumn.setVisible(newValue));

            pathColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getBinding().getTreeHierarchy()));
            colorColumn.setCellFactory(param -> new ColorTableCell<>(colorColumn));
            colorColumn.setCellValueFactory(p -> p.getValue().displayColorProperty());
            avgColumn.setCellValueFactory(p -> Bindings.createStringBinding(
                    () -> p.getValue().getProcessor() == null ? "NaN" : currentViewPort.getPrefixFormatter().format(p.getValue().getProcessor().getAverageValue()),
                    p.getValue().processorProperty()));

            minColumn.setCellValueFactory(p -> Bindings.createStringBinding(
                    () -> p.getValue().getProcessor() == null ? "NaN" : currentViewPort.getPrefixFormatter().format(p.getValue().getProcessor().getMinValue()),
                    p.getValue().processorProperty()));

            maxColumn.setCellValueFactory(p -> Bindings.createStringBinding(
                    () -> p.getValue().getProcessor() == null ? "NaN" : currentViewPort.getPrefixFormatter().format(p.getValue().getProcessor().getMaxValue()),
                    p.getValue().processorProperty()));

            currentColumn.setCellValueFactory(p -> Bindings.createStringBinding(
                    () -> {
                        if (p.getValue().getProcessor() == null) {
                            return "NaN";
                        }
                        return currentViewPort.getPrefixFormatter().format(p.getValue()
                                .getProcessor()
                                .tryGetNearestValue(getSelectedViewPort().getCrosshair().getCurrentXValue())
                                .orElse(Double.NaN));
                    }, getSelectedViewPort().getCrosshair().currentXValueProperty()));

            currentViewPort.getSeriesTable().setRowFactory(this::seriesTableRowFactory);
            currentViewPort.getSeriesTable().setOnKeyReleased(bindingManager.registerHandler(event -> {
                if (event.getCode().equals(KeyCode.DELETE)) {
                    removeSelectedBinding(currentViewPort.getSeriesTable());
                }
            }));

            currentViewPort.getSeriesTable().setItems(currentViewPort.getDataStore().getSeries());
            currentViewPort.getSeriesTable().getColumns().addAll(visibleColumn, colorColumn, nameColumn, minColumn, maxColumn, avgColumn, currentColumn, pathColumn);
            TableViewUtils.autoFillTableWidthWithLastColumn(currentViewPort.getSeriesTable());
            TitledPane newPane = new TitledPane(currentViewPort.getDataStore().getName(), currentViewPort.getSeriesTable());
            newPane.setMinHeight(90.0);
            newPane.setOnDragOver(bindingManager.registerHandler(this::handleDragOverWorksheetView));
            newPane.setOnDragDropped(bindingManager.registerHandler(this::handleDragDroppedOnLegendTitledPane));
            newPane.setUserData(currentViewPort);

            GridPane titleRegion = new GridPane();
            titleRegion.setHgap(5);
            titleRegion.getColumnConstraints().add(new ColumnConstraints(USE_COMPUTED_SIZE, USE_COMPUTED_SIZE, USE_COMPUTED_SIZE, Priority.ALWAYS, HPos.LEFT, true));
            titleRegion.getColumnConstraints().add(new ColumnConstraints(USE_COMPUTED_SIZE, USE_COMPUTED_SIZE, USE_COMPUTED_SIZE, Priority.NEVER, HPos.RIGHT, false));
            bindingManager.bind(titleRegion.minWidthProperty(), newPane.widthProperty().subtract(30));
            bindingManager.bind(titleRegion.maxWidthProperty(), newPane.widthProperty().subtract(30));

            Label label = new Label();
            bindingManager.bind(label.textProperty(), currentViewPort.getDataStore().nameProperty());
            bindingManager.bind(label.visibleProperty(), currentViewPort.getDataStore().showPropertiesProperty().not());
            HBox editFieldsGroup = new HBox();
            DoubleBinding db = Bindings.createDoubleBinding(() -> editFieldsGroup.isVisible() ? USE_COMPUTED_SIZE : 0.0, editFieldsGroup.visibleProperty());
            bindingManager.bind(editFieldsGroup.prefHeightProperty(), db);
            bindingManager.bind(editFieldsGroup.maxHeightProperty(), db);
            bindingManager.bind(editFieldsGroup.minHeightProperty(), db);
            bindingManager.bind(editFieldsGroup.visibleProperty(), currentViewPort.getDataStore().showPropertiesProperty());
            editFieldsGroup.setSpacing(5);
            TextField chartNameField = new TextField();
            chartNameField.textProperty().bindBidirectional(currentViewPort.getDataStore().nameProperty());
            TextField unitNameField = new TextField();
            unitNameField.textProperty().bindBidirectional(currentViewPort.getDataStore().unitProperty());
            ChoiceBox<UnitPrefixes> unitPrefixChoiceBox = new ChoiceBox<>();
            unitPrefixChoiceBox.getItems().setAll(UnitPrefixes.values());
            unitPrefixChoiceBox.getSelectionModel().select(currentViewPort.getDataStore().getUnitPrefixes());
            bindingManager.bind(currentViewPort.getDataStore().unitPrefixesProperty(), unitPrefixChoiceBox.getSelectionModel().selectedItemProperty());
            HBox.setHgrow(chartNameField, Priority.ALWAYS);
            titleRegion.setOnMouseClicked(bindingManager.registerHandler(event -> {
                if (event.getClickCount() == 2) {
                    chartNameField.selectAll();
                    chartNameField.requestFocus();
                    currentViewPort.getDataStore().setShowProperties(true);
                }
            }));
            editFieldsGroup.getChildren().addAll(chartNameField, unitNameField, unitPrefixChoiceBox);

            // *** Toolbar ***
            HBox toolbar = new HBox();
            toolbar.getStyleClass().add("title-pane-tool-bar");
            toolbar.setAlignment(Pos.CENTER);
            Button closeButton = new ToolButtonBuilder<Button>(bindingManager)
                    .setText("Close")
                    .setTooltip("Remove this chart from the worksheet.")
                    .setStyleClass("exit")
                    .setIconStyleClass("cross-icon", "small-icon")
                    .setAction(event -> warnAndRemoveChart(currentViewPort.getDataStore()))
                    .bind(Button::disableProperty, Bindings.createBooleanBinding(() -> worksheet.getCharts().size() > 1, worksheet.getCharts()).not())
                    .build(Button::new);

            ToggleButton editButton = new ToolButtonBuilder<ToggleButton>(bindingManager)
                    .setText("Settings")
                    .setTooltip("Edit the chart's settings")
                    .setStyleClass("dialog-button")
                    .setIconStyleClass("settings-icon", "small-icon")
                    .setAction(event -> newPane.setExpanded(true))
                    .bindBidirectionnal(ToggleButton::selectedProperty, currentViewPort.getDataStore().showPropertiesProperty())
                    .build(ToggleButton::new);

            editButtonsGroup.getToggles().add(editButton);

            Button moveUpButton = new ToolButtonBuilder<Button>(bindingManager)
                    .setText("Up")
                    .setTooltip("Move the chart up the list.")
                    .setStyleClass("dialog-button")
                    .setIconStyleClass("upArrow-icon")
                    .bind(Node::visibleProperty, currentViewPort.getDataStore().showPropertiesProperty())
                    .bind(Node::disableProperty, Bindings.createBooleanBinding(() -> seriesTableContainer.getPanes().indexOf(newPane) == 0, seriesTableContainer.getPanes()))
                    .setAction(event -> moveChartOrder(currentViewPort.getDataStore(), -1))
                    .build(Button::new);
            Button moveDownButton = new ToolButtonBuilder<Button>(bindingManager)
                    .setText("Down")
                    .setTooltip("Move the chart down the list.")
                    .setStyleClass("dialog-button")
                    .setIconStyleClass("downArrow-icon")
                    .bind(Node::visibleProperty, currentViewPort.getDataStore().showPropertiesProperty())
                    .bind(Node::disableProperty, Bindings.createBooleanBinding(
                            () -> seriesTableContainer.getPanes().indexOf(newPane) >= seriesTableContainer.getPanes().size() - 1, seriesTableContainer.getPanes()))
                    .setAction(event -> moveChartOrder(currentViewPort.getDataStore(), 1))
                    .build(Button::new);
            toolbar.getChildren().addAll(moveUpButton, moveDownButton, editButton, closeButton);
            titleRegion.getChildren().addAll(label, editFieldsGroup, toolbar);
            HBox hBox = new HBox();
            hBox.setAlignment(Pos.CENTER);
            GridPane.setConstraints(label, 0, 0, 1, 1, HPos.LEFT, VPos.CENTER);
            GridPane.setConstraints(toolbar, 1, 0, 1, 1, HPos.RIGHT, VPos.CENTER);
            newPane.setGraphic(titleRegion);
            newPane.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            newPane.setAnimated(false);
            seriesTableContainer.getPanes().add(newPane);
        }
        Platform.runLater(() -> seriesTableContainer.getPanes().get(worksheet.getSelectedChart()).setExpanded(true));
        bindingManager.attachListener(editButtonsGroup.selectedToggleProperty(), (ChangeListener<Toggle>) (observable, oldValue, newValue) -> {
            if (newValue != null) {
                chartProperties.expand();
            } else {
                chartProperties.collapse();
            }
        });
        chartProperties.setSibling(chartView);
        if (editButtonsGroup.getSelectedToggle() != null) {
            chartProperties.expand();
        }

        bindingManager.attachListener(seriesTableContainer.expandedPaneProperty(),
                (ObservableValue<? extends TitledPane> observable, TitledPane oldPane, TitledPane newPane) -> {
                    Boolean expandRequiered = true;
                    for (TitledPane pane : seriesTableContainer.getPanes()) {
                        if (pane.isExpanded()) {
                            expandRequiered = false;
                        }
                    }
                    getAttachedViewport(newPane).ifPresent(nv -> {
                        worksheet.setSelectedChart(viewPorts.indexOf(nv));
                        if (editButtonsGroup.getSelectedToggle() != null) {
                            nv.getDataStore().setShowProperties(true);
                        }
                    });
                    if ((expandRequiered) && (oldPane != null)) {
                        worksheet.setSelectedChart(seriesTableContainer.getPanes().indexOf(oldPane));
                        Platform.runLater(() -> {
                            seriesTableContainer.setExpandedPane(oldPane);
                        });
                    }
                });
        splitPane.setDividerPositions(worksheet.getDividerPosition());
        bindingManager.bind(worksheet.dividerPositionProperty(), splitPane.getDividers().get(0).positionProperty());
    }

    @Override
    public Optional<ChartViewPort> getAttachedViewport(TitledPane pane) {
        if (pane != null && (pane.getUserData() instanceof ChartViewPort)) {
            return Optional.of((ChartViewPort) pane.getUserData());
        }
        return Optional.empty();
    }

    private void warnAndRemoveChart(Chart chart) {
        if (Dialogs.confirmDialog(root, "Are you sure you want to remove chart \"" + chart.getName() + "\"?",
                "", ButtonType.YES, ButtonType.NO) == ButtonType.YES) {
            worksheet.getCharts().remove(chart);
        }
    }

    private void moveChartOrder(Chart chart, int pos) {
        int idx = worksheet.getCharts().indexOf(chart);
        this.preventReload = true;
        try {
            worksheet.getCharts().remove(chart);
        } finally {
            this.preventReload = false;
        }
        worksheet.getCharts().add(idx + pos, chart);
    }

    private void handleDragOverWorksheetView(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasContent(DataFormat.lookupMimeType(TimeSeriesBinding.MIME_TYPE))) {
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        }
    }

    private void handleDragOverNewChartTarget(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasContent(DataFormat.lookupMimeType(TimeSeriesBinding.MIME_TYPE))) {
            event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        }
    }

    private void handleDragDroppedOnLegendTitledPane(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasContent(DataFormat.lookupMimeType(TimeSeriesBinding.MIME_TYPE))) {
            TreeView<SourceBinding> treeView = parentController.getSelectedTreeView();
            if (treeView != null) {
                TreeItem<SourceBinding> item = treeView.getSelectionModel().getSelectedItem();
                if (item != null) {
                    Stage targetStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
                    if (targetStage != null) {
                        targetStage.requestFocus();
                    }
                    try {
                        TitledPane droppedPane = (TitledPane) event.getSource();
                        droppedPane.setExpanded(true);
                        ChartViewPort viewPort = (ChartViewPort) droppedPane.getUserData();
                        addBindings(TreeViewUtils.flattenLeaves(item, true).stream().filter(b -> b instanceof TimeSeriesBinding)
                                .map(b -> (TimeSeriesBinding) b).collect(Collectors.toList()), viewPort.getDataStore());
                    } catch (Exception e) {
                        Dialogs.notifyException("Error adding bindings to existing worksheet", e, root);
                    }
                    logger.debug("dropped to " + event.toString());
                } else {
                    logger.warn("Cannot complete drag and drop operation: selected TreeItem is null");
                }
            } else {
                logger.warn("Cannot complete drag and drop operation: selected TreeView is null");
            }
            event.consume();
        }
    }

    private void handleDragDroppedOnWorksheetView(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasContent(DataFormat.lookupMimeType(TimeSeriesBinding.MIME_TYPE))) {
            TreeView<SourceBinding> treeView = parentController.getSelectedTreeView();
            if (treeView != null) {
                TreeItem<SourceBinding> item = treeView.getSelectionModel().getSelectedItem();
                if (item != null) {
                    Stage targetStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
                    if (targetStage != null) {
                        targetStage.requestFocus();
                    }
                    Chart targetChart = null;
                    if (event.getSource() instanceof XYChart<?, ?>) {
                        for (var v : viewPorts) {
                            if (v.getChart().equals(event.getSource())) {
                                targetChart = v.getDataStore();
                            }
                        }
                    }
                    var items = treeView.getSelectionModel().getSelectedItems();
                    if (targetChart == null) {
                        getChartListContextMenu(items).show((Node) event.getTarget(), event.getScreenX(), event.getSceneY());
                    } else {
                        addToCurrentWorksheet(items, targetChart);
                    }
                } else {
                    logger.warn("Cannot complete drag and drop operation: selected TreeItem is null");
                }
            } else {
                logger.warn("Cannot complete drag and drop operation: selected TreeView is null");
            }
            event.consume();
        }
    }

    private void handleDragDroppedONewChartTarget(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasContent(DataFormat.lookupMimeType(TimeSeriesBinding.MIME_TYPE))) {
            TreeView<SourceBinding> treeView = parentController.getSelectedTreeView();
            if (treeView != null) {
                Collection<TreeItem<SourceBinding>> items = treeView.getSelectionModel().getSelectedItems();
                if (items != null && !items.isEmpty()) {
                    Stage targetStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
                    if (targetStage != null) {
                        targetStage.requestFocus();
                    }
                    // Schedule for later execution in order to let other drag and dropped event to complete before modal dialog gets displayed
                    Platform.runLater(() -> addToNewChart(items));
                } else {
                    logger.warn("Cannot complete drag and drop operation: selected TreeItem is null");
                }
            } else {
                logger.warn("Cannot complete drag and drop operation: selected TreeView is null");
            }
            event.consume();
        }
    }

    private void addToNewChart(Collection<TreeItem<SourceBinding>> treeItems) {
        try {
            treeItemsAsChartList(treeItems, root).ifPresent(charts -> {
                // Set the time range of the whole worksheet to accommodate the new bindings
                // if there are no other series present.
                if (worksheet.getTotalNumberOfSeries() == 0) {
                    try {
                        this.timeRangePicker.selectedRangeProperty().setValue(charts.get(0).getInitialTimeRange());
                    } catch (DataAdapterException e) {
                        logger.error("Failed to reset time range", e);
                    }
                }
                worksheet.getCharts().addAll(charts);
            });

        } catch (Exception e) {
            Dialogs.notifyException("Error adding bindings to new chart", e, null);
        }
    }

    private void addToCurrentWorksheet(Collection<TreeItem<SourceBinding>> treeItems, Chart targetChart) {
        try {
            // Schedule for later execution in order to let other drag and dropped event to complete before modal dialog gets displayed
            Platform.runLater(() -> {
                if (treeItems != null && !treeItems.isEmpty()) {
                    addBindings(treeItems.stream()
                            .flatMap(item -> TreeViewUtils.flattenLeaves(item, true).stream())
                            .collect(Collectors.toList()
                            ), targetChart);
                }
            });
        } catch (Exception e) {
            Dialogs.notifyException("Error adding bindings to existing worksheet", e, root);
        }
    }

    @Override
    public ContextMenu getChartListContextMenu(final Collection<TreeItem<SourceBinding>> items) {
        ContextMenu contextMenu = new ContextMenu(worksheet.getCharts()
                .stream()
                .map(c -> {
                    MenuItem m = new MenuItem(c.getName());
                    m.setOnAction(bindingManager.registerHandler(e -> addToCurrentWorksheet(items, c)));
                    return m;
                })
                .toArray(MenuItem[]::new));
        MenuItem newChart = new MenuItem("Add to new chart");
        newChart.setOnAction((bindingManager.registerHandler(event -> {
            addToNewChart(new ArrayList<>(items));
        })));
        contextMenu.getItems().addAll(new SeparatorMenuItem(), newChart);
        return contextMenu;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.debug(() -> "Closing worksheetController " + this.toString());
            bindingManager.close();
            currentState.close();
            hCrosshair.selectedProperty().unbindBidirectional(userPrefs.horizontalMarkerOn.property());
            vCrosshair.selectedProperty().unbindBidirectional(userPrefs.verticalMarkerOn.property());
            currentState = null;
            this.seriesTableContainer.getPanes().forEach(pane -> {
                pane.setUserData(null);
                pane.setContent(null);
            });
            this.seriesTableContainer.getPanes().clear();
            //Workaround JDK-8220012
            this.seriesTableContainer.getPanes().add(new TitledPane());
            IOUtils.closeAll(viewPorts);
            viewPorts = null;
            timeRangePicker.dispose();
            this.worksheet = null;
        }
    }

    @Override
    public String getView() {
        return WORKSHEET_VIEW_FXML;
    }

    @Override
    public void setReloadRequiredHandler(Consumer<WorksheetController> action) {
        ChangeListener<Object> controllerReloadListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                logger.debug(() -> "Reloading worksheet controller because property changed from: " + oldValue + " to " + newValue);
                action.accept(this);
                this.close();
            }
        };
        bindingManager.attachListener(worksheet.chartLayoutProperty(), controllerReloadListener);

        this.worksheet.getCharts().forEach(c -> {
            bindingManager.attachListener(c.unitPrefixesProperty(), controllerReloadListener);
            bindingManager.attachListener(c.chartTypeProperty(), controllerReloadListener);
        });

        ListChangeListener<Chart> chartListListener = c -> {
            while (c.next()) {
                if (c.wasPermutated()) {
                    for (int i = c.getFrom(); i < c.getTo(); ++i) {
                        // nothing for now
                    }
                } else if (c.wasUpdated()) {
                    // nothing for now
                } else {
                    if (!preventReload) {
                        if (c.wasAdded()) {
                            parentController.getWorkspace().setPresentationMode(false);
                            List<? extends Chart> added = c.getAddedSubList();
                            Chart chart = added.get(added.size() - 1);
                            int chartIndex = worksheet.getCharts().indexOf(chart);
                            worksheet.setSelectedChart(chartIndex);
                        }
                        if (c.wasRemoved()) {
                            if (worksheet.getSelectedChart() == c.getFrom()) {
                                worksheet.setSelectedChart(Math.max(0, c.getFrom() - 1));
                            } else if (worksheet.getSelectedChart() > c.getFrom()) {
                                worksheet.setSelectedChart(Math.max(0, worksheet.getSelectedChart() - 1));
                            }
                        }
                        logger.debug(() -> "Reloading worksheet controller because list changed: " + c.toString() + " in controller " + this.toString());
                        action.accept(this);
                    } else {
                        logger.debug(() -> "Reload explicitly prevented on change " + c.toString());
                    }
                }
            }
        };
        bindingManager.attachListener(worksheet.getCharts(), chartListListener);
    }

    private void addBindings(Collection<SourceBinding> sourceBindings, Chart targetChart) {
        Collection<TimeSeriesBinding> timeSeriesBindings = new ArrayList<>();
        for (var sb : sourceBindings) {
            if (sb instanceof TimeSeriesBinding) {
                timeSeriesBindings.add((TimeSeriesBinding) sb);
            }
        }
        if (timeSeriesBindings.size() >= userPrefs.maxSeriesPerChartBeforeWarning.get().intValue()) {
            if (Dialogs.confirmDialog(root,
                    "This action will add " + timeSeriesBindings.size() + " series on a single chart.",
                    "Are you sure you want to proceed?",
                    ButtonType.YES, ButtonType.NO) != ButtonType.YES) {
                return;
            }
        }
        InvalidationListener isVisibleListener = (observable) -> {
            viewPorts.stream().filter(v -> v.getDataStore().equals(targetChart)).findFirst().ifPresent(v -> {
                boolean andAll = true;
                boolean orAll = false;
                for (TimeSeriesInfo<Double> t : targetChart.getSeries()) {
                    andAll &= t.isSelected();
                    orAll |= t.isSelected();
                }
                CheckBox showAllCheckBox = (CheckBox) v.getSeriesTable().getColumns().get(0).getGraphic();
                showAllCheckBox.setIndeterminate(Boolean.logicalXor(andAll, orAll));
                showAllCheckBox.setSelected(andAll);
            });
        };
        for (TimeSeriesBinding b : timeSeriesBindings) {
            TimeSeriesInfo<Double> newSeries = TimeSeriesInfo.fromBinding(b);
            bindingManager.attachListener(newSeries.selectedProperty(),
                    (observable, oldValue, newValue) ->
                            viewPorts.stream()
                                    .filter(v -> v.getDataStore().equals(targetChart))
                                    .findFirst()
                                    .ifPresent(v -> invalidate(v, false, false))
            );
            bindingManager.attachListener(newSeries.selectedProperty(), isVisibleListener);
            targetChart.addSeries(newSeries);
            // Explicitly call the listener to initialize the proper status of the checkbox
            isVisibleListener.invalidated(null);
        }
        // Set the time range of the whole worksheet to accommodate the new bindings
        // if there are no other series present.
        if (worksheet.getTotalNumberOfSeries() == timeSeriesBindings.size()) {
            try {
                this.timeRangePicker.selectedRangeProperty().setValue(targetChart.getInitialTimeRange());
            } catch (DataAdapterException e) {
                logger.error("Failed to reset time range", e);
            }
        }
        invalidateAll(false, false, false);
    }

    private void removeSelectedBinding(TableView<TimeSeriesInfo<Double>> seriesTable) {
        List<TimeSeriesInfo<Double>> selected = new ArrayList<>(seriesTable.getSelectionModel().getSelectedItems());
        seriesTable.getItems().removeAll(selected);
        seriesTable.getSelectionModel().clearSelection();
        invalidateAll(false, false, false);
    }

    @Override
    public void refresh() {
        invalidateAll(false, false, true);
    }

    @FXML
    private void handleHistoryBack(ActionEvent actionEvent) {
        worksheet.getHistory().getPrevious().ifPresent(h -> currentState.setSelection(h, false));
    }

    @FXML
    private void handleHistoryForward(ActionEvent actionEvent) {
        worksheet.getHistory().getNext().ifPresent(h -> currentState.setSelection(h, false));
    }

    @FXML
    private void handleRefresh(ActionEvent actionEvent) {
        this.refresh();
    }

    @FXML
    private void handleTakeSnapshot(ActionEvent actionEvent) {
        saveSnapshot();
    }

    public void invalidateAll(boolean saveToHistory, boolean dontPlotChart, boolean forceRefresh) {
        worksheet.getHistory().setHead(currentState.asSelection(), saveToHistory);
        logger.debug(() -> worksheet.getHistory().backward().dump());
        for (ChartViewPort viewPort : viewPorts) {
            invalidate(viewPort, dontPlotChart, forceRefresh);
        }
    }

    public void invalidate(ChartViewPort viewPort, boolean dontPlot, boolean forceRefresh) {
        try (Profiler p = Profiler.start("Refreshing chart " + worksheet.getName() + "\\" + viewPort.getDataStore().getName() + " (dontPlot=" + dontPlot + ")", logger::perf)) {
            currentState.get(viewPort.getDataStore()).ifPresent(y -> {
                XYChartSelection<ZonedDateTime, Double> currentSelection = y.asSelection();
                logger.debug(() -> "currentSelection=" + (currentSelection == null ? "null" : currentSelection.toString()));
                if (!dontPlot) {
                    plotChart(viewPort, currentSelection, forceRefresh);
                }
            });
        }
    }

    private void plotChart(ChartViewPort viewPort, XYChartSelection<ZonedDateTime, Double> currentSelection, boolean forceRefresh) {
        try (Profiler p = Profiler.start("Adding series to chart " + viewPort.getDataStore().getName(), logger::perf)) {
            nbBusyPlotTasks.setValue(nbBusyPlotTasks.get() + 1);
            AsyncTaskManager.getInstance().submit(() -> {
                        viewPort.getDataStore().fetchDataFromSources(currentSelection.getStartX(), currentSelection.getEndX(), forceRefresh);
                        return viewPort.getDataStore().getSeries()
                                .stream()
                                .filter(series -> {
                                    if (series.getProcessor() == null) {
                                        logger.warn("Series " + series.getDisplayName() + " does not contain any data to plot");
                                        return false;
                                    }
                                    if (!series.isSelected()) {
                                        logger.debug(() -> "Series " + series.getDisplayName() + " is not selected");
                                        return false;
                                    }
                                    return true;
                                })
                                .map(ts -> makeXYChartSeries(viewPort.getDataStore(), ts))
                                .collect(Collectors.toList());
                    },
                    event -> {
                        try {
                            if (!closed.get()) {
                                nbBusyPlotTasks.setValue(nbBusyPlotTasks.get() - 1);
                                viewPort.getChart().getData().setAll((Collection<? extends XYChart.Series<ZonedDateTime, Double>>) event.getSource().getValue());
                                for (Node n : viewPort.getChart().getChildrenUnmodifiable()) {
                                    if (n instanceof Legend) {
                                        int i = 0;
                                        for (Legend.LegendItem legendItem : ((Legend) n).getItems()) {
                                            legendItem.getSymbol().setStyle("-fx-background-color: " +
                                                    colorToRgbaString(viewPort.getDataStore()
                                                            .getSeries()
                                                            .stream()
                                                            .filter(TimeSeriesInfo::isSelected)
                                                            .collect(Collectors.toList())
                                                            .get(i)
                                                            .getDisplayColor()));
                                            i++;
                                        }
                                    }
                                }
                                if (worksheet.getChartLayout() == ChartLayout.OVERLAID) {
                                    // Force a redraw of the charts and their Y Axis considering their proper width.
                                    new DelayedAction(() -> viewPort.getChart().resize(0.0, 0.0), Duration.millis(50)).submit();
                                }
                            }
                        } catch (Exception e) {
                            Dialogs.notifyException("Unexpected error while plotting data", e, root);
                        }
                    },
                    event -> {
                        if (!closed.get()) {
                            nbBusyPlotTasks.setValue(nbBusyPlotTasks.get() - 1);
                            Dialogs.notifyException("Failed to retrieve data from source", event.getSource().getException(), root);
                        }
                    });
        }
    }

    private XYChart.Series<ZonedDateTime, Double> makeXYChartSeries(Chart currentChart, TimeSeriesInfo<Double> series) {
        try (Profiler p = Profiler.start("Building  XYChart.Series data for" + series.getDisplayName(), logger::perf)) {
            XYChart.Series<ZonedDateTime, Double> newSeries = new XYChart.Series<>();
            newSeries.setName(series.getDisplayName());
            var r = new Region();
            r.setPrefSize(10, 10);
            r.setMaxSize(10, 10);
            r.setMinSize(10, 10);
            r.setBackground(new Background(new BackgroundFill(series.getDisplayColor(), null, null)));

            newSeries.setNode(r);
            newSeries.getData().setAll(series.getProcessor().getData());
            if (currentChart.getChartType() == ChartType.SCATTER) {
                for (var data : newSeries.getData()) {
                    var c = new Circle();
                    bindingManager.bind(c.radiusProperty(), currentChart.strokeWidthProperty());
                    bindingManager.bind(c.fillProperty(), series.displayColorProperty());
                    data.setNode(c);
                }
            } else {
                bindingManager.attachListener(newSeries.nodeProperty(), (ChangeListener<Node>) (node, oldNode, newNode) -> {
                    if (newNode != null) {
                        switch (currentChart.getChartType()) {
                            case AREA:
                            case STACKED:
                                ObservableList<Node> children = ((Group) newNode).getChildren();
                                if (children != null && children.size() >= 1) {
                                    Path stroke = (Path) children.get(1);
                                    Path fill = (Path) children.get(0);
                                    logger.trace(() -> "Setting color of series " + series.getBinding().getLabel() + " to " + series.getDisplayColor());
                                    stroke.visibleProperty().bind(currentChart.showAreaOutlineProperty());
                                    stroke.strokeWidthProperty().bind(currentChart.strokeWidthProperty());
                                    stroke.strokeProperty().bind(series.displayColorProperty());
                                    fill.fillProperty().bind(Bindings.createObjectBinding(
                                            () -> series.getDisplayColor().deriveColor(0.0, 1.0, 1.0, currentChart.getGraphOpacity()),
                                            series.displayColorProperty(),
                                            currentChart.graphOpacityProperty()));
                                }
                                break;
                            case LINE:
                                Path stroke = (Path) newNode;
                                logger.trace(() -> "Setting color of series " + series.getBinding().getLabel() + " to " + series.getDisplayColor());
                                stroke.strokeWidthProperty().bind(currentChart.strokeWidthProperty());
                                stroke.strokeProperty().bind(series.displayColorProperty());
                                break;
                            default:
                                break;
                        }
                    }
                });
            }
            return newSeries;
        }
    }

    @Override
    public void saveSnapshot() {
        WritableImage snapImg;
        boolean wasModeEdit = worksheet.isEditModeEnabled();
        try {
            // Invalidate chart nodes cache so that it is re-rendered when scaled up
            // and not just stretched for snapshot
            viewPorts.forEach(v -> v.getChart().setCache(false));
            worksheet.setEditModeEnabled(false);
            worksheetTitleBlock.setManaged(true);
            worksheetTitleBlock.setVisible(true);
            navigationToolbar.setManaged(false);
            navigationToolbar.setVisible(false);
            var scaleX = userPrefs.snapshotOutputScale.get() == SnapshotOutputScale.AUTO ?
                    Dialogs.getOutputScaleX(root) :
                    userPrefs.snapshotOutputScale.get().getScaleFactor();
            var scaleY = userPrefs.snapshotOutputScale.get() == SnapshotOutputScale.AUTO ?
                    Dialogs.getOutputScaleY(root) :
                    userPrefs.snapshotOutputScale.get().getScaleFactor();
            snapImg = SnapshotUtils.scaledSnapshot(screenshotCanvas, scaleX, scaleY);
        } catch (Exception e) {
            Dialogs.notifyException("Failed to create snapshot", e, root);
            return;
        } finally {
            viewPorts.forEach(v -> v.getChart().setCache(true));
            worksheet.setEditModeEnabled(wasModeEdit);
            navigationToolbar.setManaged(true);
            navigationToolbar.setVisible(true);
            worksheetTitleBlock.setManaged(false);
            worksheetTitleBlock.setVisible(false);
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save SnapShot");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Image Files", "*.png"));
        Dialogs.getInitialDir(UserHistory.getInstance().mostRecentSaveFolders).ifPresent(fileChooser::setInitialDirectory);
        fileChooser.setInitialFileName(String.format("binjr_snapshot_%s.png", worksheet.getName()));
        File selectedFile = fileChooser.showSaveDialog(Dialogs.getStage(root));
        if (selectedFile != null) {
            try {
                if (selectedFile.getParent() != null) {
                    UserHistory.getInstance().mostRecentSaveFolders.push(selectedFile.getParentFile().toPath());
                }
                ImageIO.write(
                        SwingFXUtils.fromFXImage(snapImg, null),
                        "png",
                        selectedFile);
            } catch (IOException e) {
                Dialogs.notifyException("Failed to save snapshot to disk", e, root);
            }
        }
    }

    private ChartViewPort getSelectedViewPort() {
        var v = viewPorts.get(worksheet.getSelectedChart());
        if (v != null) {
            return v;
        }
        throw new IllegalStateException("Could not retreive selected viewport on current worksheet");
    }

    private TableRow<TimeSeriesInfo<Double>> seriesTableRowFactory(TableView<TimeSeriesInfo<Double>> tv) {
        TableRow<TimeSeriesInfo<Double>> row = new TableRow<>();
        row.setOnDragDetected(bindingManager.registerHandler(event -> {
            if (!row.isEmpty()) {
                Integer index = row.getIndex();
                Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
                db.setDragView(SnapshotUtils.scaledSnapshot(row, Dialogs.getOutputScaleX(root), Dialogs.getOutputScaleY(root)));
                ClipboardContent cc = new ClipboardContent();
                cc.put(SERIALIZED_MIME_TYPE, index);
                db.setContent(cc);
                event.consume();
            }
        }));

        row.setOnDragOver(bindingManager.registerHandler(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasContent(SERIALIZED_MIME_TYPE) && row.getIndex() != (Integer) db.getContent(SERIALIZED_MIME_TYPE)) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                event.consume();
            }
        }));

        row.setOnDragDropped(bindingManager.registerHandler(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasContent(SERIALIZED_MIME_TYPE)) {
                int draggedIndex = (Integer) db.getContent(SERIALIZED_MIME_TYPE);
                TimeSeriesInfo<Double> draggedseries = tv.getItems().remove(draggedIndex);
                int dropIndex;
                if (row.isEmpty()) {
                    dropIndex = tv.getItems().size();
                } else {
                    dropIndex = row.getIndex();
                }
                tv.getItems().add(dropIndex, draggedseries);
                event.setDropCompleted(true);
                tv.getSelectionModel().clearAndSelect(dropIndex);
                invalidateAll(false, false, false);
                event.consume();
            }
        }));
        return row;
    }

    @Override
    public void toggleShowPropertiesPane() {
        ChartViewPort currentViewport = viewPorts.get(worksheet.getSelectedChart());
        if (currentViewport != null) {
            currentViewport.getDataStore().setShowProperties((editButtonsGroup.getSelectedToggle() == null));
        }
    }

    @Override
    public void setShowPropertiesPane(boolean value) {
        ChartViewPort currentViewport = viewPorts.get(worksheet.getSelectedChart());
        if (currentViewport != null) {
            currentViewport.getDataStore().setShowProperties(value);
        }
    }

    @Override
    public List<ChartViewPort> getViewPorts() {
        return viewPorts;
    }


    @Override
    public BindingManager getBindingManager() {
        return bindingManager;
    }

}
