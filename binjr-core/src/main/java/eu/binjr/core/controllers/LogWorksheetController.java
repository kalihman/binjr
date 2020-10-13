/*
 *    Copyright 2020 Frederic Thevenet
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

/*
 *    Copyright 2020 Frederic Thevenet
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


import com.google.gson.Gson;
import eu.binjr.common.colors.ColorUtils;
import eu.binjr.common.javafx.controls.*;
import eu.binjr.common.javafx.richtext.CodeAreaHighlighter;
import eu.binjr.common.logging.Logger;
import eu.binjr.common.logging.Profiler;
import eu.binjr.common.navigation.RingIterator;
import eu.binjr.core.data.adapters.DataAdapter;
import eu.binjr.core.data.adapters.LogFilter;
import eu.binjr.core.data.adapters.SourceBinding;
import eu.binjr.core.data.async.AsyncTaskManager;
import eu.binjr.core.data.exceptions.DataAdapterException;
import eu.binjr.core.data.exceptions.NoAdapterFoundException;
import eu.binjr.core.data.timeseries.FacetEntry;
import eu.binjr.core.data.timeseries.LogEvent;
import eu.binjr.core.data.timeseries.LogEventsProcessor;
import eu.binjr.core.data.timeseries.TimeSeriesProcessor;
import eu.binjr.core.data.workspace.LogWorksheet;
import eu.binjr.core.data.workspace.Syncable;
import eu.binjr.core.data.workspace.TimeSeriesInfo;
import eu.binjr.core.data.workspace.Worksheet;
import eu.binjr.core.dialogs.Dialogs;
import eu.binjr.core.preferences.UserPreferences;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.Property;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import org.controlsfx.control.CheckListView;
import org.controlsfx.control.MaskerPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.ReadOnlyStyledDocumentBuilder;
import org.fxmisc.richtext.model.SegmentOps;
import org.fxmisc.richtext.model.StyleSpans;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;

public class LogWorksheetController extends WorksheetController implements Syncable {
    public static final String WORKSHEET_VIEW_FXML = "/eu/binjr/views/LogWorksheetView.fxml";
    private static final Logger logger = Logger.create(LogWorksheetController.class);
    private static final Gson gson = new Gson();
    private final LogWorksheet worksheet;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReadOnlyObjectWrapper<TimeRange> timeRange = new ReadOnlyObjectWrapper<>();
    //  private final Property<TimeRange> timeRangeProperty = new SimpleObjectProperty<>(TimeRange.of(ZonedDateTime.now().minusHours(1), ZonedDateTime.now()));
    private StyleSpans<Collection<String>> syntaxHilightStyleSpans;
    private RingIterator<CodeAreaHighlighter.SearchHitRange> searchHitIterator = RingIterator.of(Collections.emptyList());
    @FXML
    private AnchorPane root;

    @FXML
    private CodeArea textOutput;

    @FXML
    private ToggleButton wordWrapButton;

    @FXML
    private Button refreshButton;
    @FXML
    private Button backButton;
    @FXML
    private Button forwardButton;

    @FXML
    private TimeRangePicker timeRangePicker;

    @FXML
    private Button searchHistoryButton;

    @FXML
    private TextField searchTextField;

    @FXML
    private Button clearSearchButton;

    @FXML
    private ToggleButton searchMatchCaseToggle;

    @FXML
    private ToggleButton searchRegExToggle;

    @FXML
    private Label searchResultsLabel;

    @FXML
    private Button prevOccurrenceButton;

    @FXML
    private Button nextOccurrenceButton;

    @FXML
    private ToggleButton filterToggleButton;

    @FXML
    private Pagination pager;
    @FXML
    private Button querySyntaxButton;
    @FXML
    private MaskerPane busyIndicator;
    @FXML
    private CheckListView<FacetEntry> severityListView;
    @FXML
    private TextField filterTextField;
    @FXML
    private Button clearFilterButton;
    @FXML
    private Button applyFilterButton;
    @FXML
    private VBox filteringBar;
    @FXML
    private HBox paginationBar;
    @FXML
    private TableView<TimeSeriesInfo<LogEvent>> fileTable;

    public LogWorksheetController(MainViewController parent, LogWorksheet worksheet, Collection<DataAdapter<LogEvent>> adapters)
            throws NoAdapterFoundException {
        super(parent);
        this.worksheet = worksheet;
        for (TimeSeriesInfo<LogEvent> d : worksheet.getSeriesInfo()) {
            UUID id = d.getBinding().getAdapterId();
            DataAdapter<LogEvent> da = adapters
                    .stream()
                    .filter(a -> (id != null && a != null && a.getId() != null) && id.equals(a.getId()))
                    .findAny()
                    .orElseThrow(() -> new NoAdapterFoundException("Failed to find a valid adapter with id " +
                            (id != null ? id.toString() : "null")));
            d.getBinding().setAdapter(da);
        }
    }

    @Override
    public Worksheet getWorksheet() {
        return worksheet;
    }

    @Override
    public Property<TimeRange> selectedRangeProperty() {
        return this.timeRangePicker.selectedRangeProperty();
    }

    @Override
    public Optional<ChartViewPort> getAttachedViewport(TitledPane pane) {
        return Optional.empty();
    }

    @Override
    public ContextMenu getChartListContextMenu(Collection<TreeItem<SourceBinding>> treeView) {
        return null;
    }

    @Override
    public void setReloadRequiredHandler(Consumer<WorksheetController> action) {
    }

    @Override
    public void refresh() {
        invalidate(true, true);
    }

    public void invalidate(boolean saveToHistory, boolean retrieveFacets) {
        //TODO handle history
        queryLogIndex(worksheet.getFilter());
    }

    public void queryLogIndex(LogFilter filter) {
        try {
            AsyncTaskManager.getInstance().submit(() -> {
                        busyIndicator.setVisible(true);
                        return (LogEventsProcessor) fetchDataFromSources(filter);
                    },
                    event -> {
                        bindingManager.suspend();
                        try {
                            // Reset page number
                            var res = (LogEventsProcessor) event.getSource().getValue();
                            pager.setPageCount((int) Math.ceil((double) res.getTotalHits() / res.getHitsPerPage()));
                            pager.setCurrentPageIndex(filter.getPage());
                            // Update severity facet view
                            severityListView.getCheckModel().clearChecks();
                            severityListView.getItems().setAll(res.getFacetResults().get("severity"));
                            severityListView.getItems()
                                    .stream()
                                    .filter(f -> filter.getSeverities().contains(f.getLabel()))
                                    .forEach(f -> severityListView.getCheckModel().check(f));
                            // Color and display message text
                            try (var p = Profiler.start("Set text", logger::perf)) {
                                Random r = new Random();
                                var docBuilder = new ReadOnlyStyledDocumentBuilder<Collection<String>, String, Collection<String>>(
                                        SegmentOps.styledTextOps(),
                                        Collections.emptyList());
                                for (var data : res.getData()) {
                                    var hit = data.getYValue();
                                    docBuilder.addParagraph(
                                            hit.getMessage().stripTrailing(),
                                            List.of(hit.getFacets().get("severity").getLabel()),
                                            List.of("para" + (r.nextBoolean() ? "1" : "2")));
                                }
                                textOutput.replace(docBuilder.build());
                                // Reset search highlight
                                doSearchHighlight(searchTextField.getText(),
                                        searchMatchCaseToggle.isSelected(),
                                        searchRegExToggle.isSelected());
                            }
                        } finally {
                            bindingManager.resume();
                            busyIndicator.setVisible(false);
                        }
                    }, event -> {
                        busyIndicator.setVisible(false);
                        Dialogs.notifyException("An error occurred while loading text file: " +
                                        event.getSource().getException().getMessage(),
                                event.getSource().getException(),
                                root);
                    });
        } catch (Exception e) {
            Dialogs.notifyException(e);
        }
    }

    @Override
    public void saveSnapshot() {

    }

    @Override
    public void toggleShowPropertiesPane() {

    }

    @Override
    public void setShowPropertiesPane(boolean value) {

    }

    @Override
    public List<ChartViewPort> getViewPorts() {
        return new ArrayList<>();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            timeRangePicker.dispose();

            bindingManager.close();
        }
    }

    @Override
    public String getView() {
        return WORKSHEET_VIEW_FXML;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        getBindingManager().attachListener(worksheet.textViewFontSizeProperty(),
                (ChangeListener<Integer>) (obs, oldVal, newVal) -> textOutput.setStyle("-fx-font-size: " + newVal + "pt;"));
        //textOutput.setParagraphGraphicFactory(LineNumberFactory.get(textOutput));
        textOutput.setEditable(false);
        getBindingManager().bind(textOutput.wrapTextProperty(), wordWrapButton.selectedProperty());
        refreshButton.setOnAction(getBindingManager().registerHandler(event -> refresh()));
        // TimeRange Picker initialization
        timeRange.set(TimeRange.of(worksheet.getFromDateTime(), worksheet.getToDateTime()));
        timeRangePicker.timeRangeLinkedProperty().bindBidirectional(worksheet.timeRangeLinkedProperty());
        timeRangePicker.zoneIdProperty().bindBidirectional(worksheet.timeZoneProperty());
        timeRangePicker.initSelectedRange(timeRange.get());
        timeRangePicker.setOnSelectedRangeChanged((observable, oldValue, newValue) -> {
            timeRange.set(TimeRange.of(newValue.getBeginning(), newValue.getEnd()));
            refresh();
        });

        timeRange.getReadOnlyProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                worksheet.setFromDateTime(newValue.getBeginning());
                worksheet.setToDateTime(newValue.getEnd());
                timeRangePicker.updateSelectedRange(newValue);
            }
        });

        // Query syntax help
        var syntaxPopupRoot = new StackPane();
        syntaxPopupRoot.getStyleClass().addAll("syntax-help-popup");
        syntaxPopupRoot.setPadding(new Insets(10.0, 10.0, 10.0, 10.0));
        syntaxPopupRoot.setPrefSize(600, 700);
        var syntaxCheatSheet = new StyleClassedTextArea();
        syntaxCheatSheet.setEditable(false);
        syntaxCheatSheet.append("Query Syntax\n\n", "syntax-help-title");
        syntaxCheatSheet.append("Search for word \"foo\":\n", "syntax-help-text");
        syntaxCheatSheet.append(" foo \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for word \"foo\" OR for word \"bar\":\n", "syntax-help-text");
        syntaxCheatSheet.append(" foo bar ", "syntax-help-code");
        syntaxCheatSheet.append(" or ", "syntax-help-text");
        syntaxCheatSheet.append(" foo OR bar \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for phrase \"foo bar\":\n", "syntax-help-text");
        syntaxCheatSheet.append(" \"foo bar\" \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for phrase \"foo bar\"  AND the phrase \"quick fox\":\n", "syntax-help-text");
        syntaxCheatSheet.append(" \"foo bar\" AND \"quick fox\" ", "syntax-help-code");
        syntaxCheatSheet.append(" or ", "syntax-help-text");
        syntaxCheatSheet.append(" \"foo bar\" +\"quick fox\" \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for either the phrase \"foo bar\" AND the phrase \"quick fox\", or the phrase \"hello world\":  \n", "syntax-help-text");
        syntaxCheatSheet.append(" (\"foo bar\" AND \"quick fox\") OR \"hello world\" \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for word \"foo\" and not \"bar\": (Note: The NOT operator cannot be used with just one term)\n", "syntax-help-text");
        syntaxCheatSheet.append(" foo NOT bar ", "syntax-help-code");
        syntaxCheatSheet.append(" or ", "syntax-help-text");
        syntaxCheatSheet.append(" foo -bar \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for any word that starts with \"foo\":\n", "syntax-help-text");
        syntaxCheatSheet.append(" foo* \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for any word that starts with \"foo\" and ends with bar:\n", "syntax-help-text");
        syntaxCheatSheet.append(" foo*bar \n\n", "syntax-help-code");
        syntaxCheatSheet.append(" Search for a term similar in spelling to \"foobar\" (e.g. \"fuzzy search\"): \n", "syntax-help-text");
        syntaxCheatSheet.append(" foobar~ \n\n", "syntax-help-code");
        syntaxCheatSheet.append("Search for \"foo bar\" within 4 words from each other:\n", "syntax-help-text");
        syntaxCheatSheet.append(" \"foo bar\"~4 \n", "syntax-help-code");
        syntaxPopupRoot.getChildren().add(syntaxCheatSheet);
        var popup = new PopupControl();
        popup.setAutoHide(true);
        popup.getScene().setRoot(syntaxPopupRoot);
        querySyntaxButton.setOnAction(bindingManager.registerHandler(actionEvent -> {
            Node owner = (Node) actionEvent.getSource();
            Bounds bounds = owner.localToScreen(owner.getBoundsInLocal());
            popup.show(owner.getScene().getWindow(), bounds.getMaxX() - 600, bounds.getMaxY());
        }));

        // init filter controls
        filterTextField.setText(worksheet.getFilter().getFilterQuery());
        pager.setCurrentPageIndex(worksheet.getFilter().getPage());

        bindingManager.bind(paginationBar.managedProperty(), paginationBar.visibleProperty());
        bindingManager.bind(paginationBar.visibleProperty(), pager.pageCountProperty().greaterThan(1));

        bindingManager.attachListener(worksheet.filterProperty(), (o, oldVal, newVal) -> {
            if (!oldVal.equals(newVal)) {
                refresh();
            }
        });

        // Filter selection
        bindingManager.bind(filteringBar.managedProperty(), filteringBar.visibleProperty());
        bindingManager.bind(filteringBar.visibleProperty(), filterToggleButton.selectedProperty());
        bindingManager.attachListener(severityListView.getCheckModel().getCheckedItems(),
                (ListChangeListener<FacetEntry>) l -> invalidateFilter(true));
        bindingManager.attachListener(pager.currentPageIndexProperty(), (o, oldVal, newVal) -> invalidateFilter(false));
        filterTextField.setOnAction(bindingManager.registerHandler(event -> invalidateFilter(true)));
        clearFilterButton.setOnAction(bindingManager.registerHandler(actionEvent -> {
            filterTextField.clear();
            invalidateFilter(true);
        }));
        applyFilterButton.setOnAction(bindingManager.registerHandler(event -> invalidateFilter(true)));

        //Search bar initialization
        prevOccurrenceButton.setOnAction(getBindingManager().registerHandler(event -> {
            if (searchHitIterator.hasPrevious()) {
                focusOnSearchHit(searchHitIterator.previous());
            }
        }));
        nextOccurrenceButton.setOnAction(getBindingManager().registerHandler(event -> {
            if (searchHitIterator.hasNext()) {
                focusOnSearchHit(searchHitIterator.next());
            }
        }));
        clearSearchButton.setOnAction(getBindingManager().registerHandler(event -> searchTextField.clear()));
        // Delay the search until at least the following amount of time elapsed since the last character was entered
        var delay = new PauseTransition(Duration.millis(UserPreferences.getInstance().searchFieldInputDelayMs.get().intValue()));
        getBindingManager().attachListener(searchTextField.textProperty(),
                (ChangeListener<String>) (obs, oldText, newText) -> {
                    delay.setOnFinished(event -> doSearchHighlight(newText,
                            searchMatchCaseToggle.isSelected(),
                            searchRegExToggle.isSelected()));
                    delay.playFromStart();
                });

        getBindingManager().attachListener(searchMatchCaseToggle.selectedProperty(),
                (ChangeListener<Boolean>) (obs, oldVal, newVal) ->
                        doSearchHighlight(searchTextField.getText(), newVal, searchRegExToggle.isSelected()));
        getBindingManager().attachListener(searchRegExToggle.selectedProperty(),
                (ChangeListener<Boolean>) (obs, oldVal, newVal) ->
                        doSearchHighlight(searchTextField.getText(), searchMatchCaseToggle.isSelected(), newVal));

        // Init log files table view
        intiLogFileTable();


        refresh();
        super.initialize(location, resources);
    }

    private void intiLogFileTable() {
        DecimalFormatTableCellFactory<TimeSeriesInfo<LogEvent>, String> alignRightCellFactory = new DecimalFormatTableCellFactory<>();
        alignRightCellFactory.setAlignment(TextAlignment.RIGHT);
        // alignRightCellFactory.setPattern("###");

        TableColumn<TimeSeriesInfo<LogEvent>, Boolean> visibleColumn = new TableColumn<>();
        visibleColumn.setSortable(false);
        visibleColumn.setResizable(false);
        visibleColumn.setPrefWidth(32);

        visibleColumn.setCellValueFactory(p -> p.getValue().selectedProperty());
        visibleColumn.setCellFactory(CheckBoxTableCell.forTableColumn(visibleColumn));

        TableColumn<TimeSeriesInfo<LogEvent>, Color> colorColumn = new TableColumn<>();
        colorColumn.setSortable(false);
        colorColumn.setResizable(false);
        colorColumn.setPrefWidth(32);
        colorColumn.setCellFactory(param -> new ColorTableCell<>(colorColumn));
        colorColumn.setCellValueFactory(p -> p.getValue().displayColorProperty());

        TableColumn<TimeSeriesInfo<LogEvent>, String> eventNumColumn = new TableColumn<>("#");
        eventNumColumn.setSortable(false);
        eventNumColumn.setPrefWidth(75);
        eventNumColumn.setCellFactory(alignRightCellFactory);
        eventNumColumn.setCellValueFactory(p -> Bindings.createStringBinding(
                () -> p.getValue().getProcessor() == null ? "NaN" :
                        Integer.toString(((LogEventsProcessor) p.getValue().getProcessor()).getTotalHits()),
                p.getValue().processorProperty()));


        TableColumn<TimeSeriesInfo<LogEvent>, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setSortable(false);
        nameColumn.setPrefWidth(260);
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("displayName"));

        TableColumn<TimeSeriesInfo<LogEvent>, String> pathColumn = new TableColumn<>("Path");
        pathColumn.setSortable(false);
        pathColumn.setPrefWidth(400);
        pathColumn.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getBinding().getTreeHierarchy()));

        fileTable.setItems(worksheet.getSeriesInfo());
        fileTable.getColumns().addAll(
                visibleColumn,
                colorColumn,
                eventNumColumn,
                nameColumn,
                pathColumn);
        TableViewUtils.autoFillTableWidthWithLastColumn(fileTable);
    }

    private void invalidateFilter(boolean resetPage) {
        worksheet.setFilter(
                new LogFilter(filterTextField.getText(),
                        severityListView.getCheckModel().getCheckedItems()
                                .stream()
                                .filter(Objects::nonNull)
                                .map(FacetEntry::getLabel)
                                .collect(Collectors.toSet()),
                        resetPage ? 0 : pager.getCurrentPageIndex()));
    }

    private void focusOnSearchHit(CodeAreaHighlighter.SearchHitRange hit) {
        if (hit == null) {
            textOutput.selectRange(0, 0);
            searchResultsLabel.setText("No results");
        } else {
            textOutput.selectRange(hit.getStart(), hit.getEnd());
            textOutput.requestFollowCaret();
            searchResultsLabel.setText(String.format("%d/%d",
                    searchHitIterator.peekCurrentIndex() + 1,
                    searchHitIterator.peekLastIndex() + 1));
        }
    }

    private void doSearchHighlight(String searchText, boolean matchCase, boolean regEx) {
        var searchResults =
                CodeAreaHighlighter.computeSearchHitsHighlighting(textOutput.getText(), searchText, matchCase, regEx);
        prevOccurrenceButton.setDisable(searchResults.getSearchHitRanges().isEmpty());
        nextOccurrenceButton.setDisable(searchResults.getSearchHitRanges().isEmpty());
        searchHitIterator = RingIterator.of(searchResults.getSearchHitRanges());
        searchResultsLabel.setText(searchResults.getSearchHitRanges().size() + " results");
        if (syntaxHilightStyleSpans != null) {
            textOutput.setStyleSpans(0, syntaxHilightStyleSpans.overlay(searchResults.getStyleSpans(),
                    (strings, strings2) -> Stream.concat(strings.stream(),
                            strings2.stream()).collect(Collectors.toCollection(ArrayList<String>::new))));
        } else {
            textOutput.setStyleSpans(0, searchResults.getStyleSpans());
        }
        if (searchHitIterator.hasNext()) {
            focusOnSearchHit(searchHitIterator.next());
        } else {
            focusOnSearchHit(null);
        }
    }

    private TimeSeriesProcessor<LogEvent> fetchDataFromSources(LogFilter filter) throws DataAdapterException {
        // prune series from closed adapters
        worksheet.getSeriesInfo().removeIf(seriesInfo -> {
            if (seriesInfo.getBinding().getAdapter().isClosed()) {
                logger.debug(() -> seriesInfo.getDisplayName() + " will be pruned because attached adapter " +
                        seriesInfo.getBinding().getAdapter().getId() + " is closed.");
                return true;
            }
            return false;
        });

        if (worksheet.getFromDateTime().toInstant().equals(Instant.EPOCH) &&
                worksheet.getFromDateTime().equals(worksheet.getToDateTime())) {
            timeRange.set(worksheet.getInitialTimeRange());
        }
        var queryString = gson.toJson(filter);
        var bindingsByAdapters =
                worksheet.getSeriesInfo().stream().collect(groupingBy(o -> o.getBinding().getAdapter()));
        for (var byAdapterEntry : bindingsByAdapters.entrySet()) {
            // Define the transforms to apply
            var adapter = (DataAdapter<LogEvent>) byAdapterEntry.getKey();
            // Group all queries with the same adapter and path
            var bindingsByPath =
                    byAdapterEntry.getValue().stream().collect(groupingBy(o -> o.getBinding().getPath()));
            var data = adapter.fetchData(
                    queryString,
                    worksheet.getFromDateTime().toInstant(),
                    worksheet.getToDateTime().toInstant(),
                    bindingsByPath.values().stream().flatMap(Collection::stream).collect(Collectors.toList()),
                    true);
            return data.values().stream().findFirst().orElse(new LogEventsProcessor());
        }
        return new LogEventsProcessor();
    }

    private void setThemeColors(Pane previewPane, Color backgroundColor, Color textColor, Color controlColor) {
        try {
            Path cssPath = Files.createTempFile("fx-theme-", ".css");
            Files.writeString(
                    cssPath,
                    ".themed{-fx-background-color:" + ColorUtils.toHex(backgroundColor) + ";}" +
                            ".label{-fx-text-fill:" + ColorUtils.toHex(textColor) + ";}" +
                            ".button{-fx-base:" + ColorUtils.toHex(controlColor) + ";}"
            );
            cssPath.toFile().deleteOnExit();

            System.out.println("Wrote " + cssPath);
            System.out.println("URL " + cssPath.toUri().toURL().toExternalForm());

            previewPane.getStyleClass().setAll("themed");
            previewPane.getStylesheets().setAll(
                    cssPath.toUri().toURL().toExternalForm()
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Boolean isTimeRangeLinked() {
        return null;
    }

    @Override
    public Property<Boolean> timeRangeLinkedProperty() {
        return null;
    }

    @Override
    public void setTimeRangeLinked(Boolean timeRangeLinked) {

    }

}
