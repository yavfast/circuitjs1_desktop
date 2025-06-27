package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.i18n.client.DateTimeFormat;

import java.util.Date;
import java.util.ArrayList;

public class LogManager extends BaseCirSimDelegate {

    public static final int MIN_LOG_PANEL_WIDTH = 200;
    public static final int MAX_LOG_PANEL_WIDTH = 800;
    public static final int MIN_LOG_PANEL_HEIGHT = 150;

    public VerticalPanel logPanel;
    private HorizontalPanel controlPanel;
    private Label headerLabel;
    public ScrollPanel logScrollPanel;
    public VerticalPanel logEntriesPanel;
    public Button clearLogsButton;
    public Label logCountLabel;
    public ArrayList<String> logEntries;
    public HTML resizeHandle;
    public boolean isResizing;
    public int startX;
    public int startWidth;

    public int logPanelWidth = 600;
    public int logPanelHeight = 800;

    protected LogManager(CirSim cirSim) {
        super(cirSim);
        logEntries = new ArrayList<String>();
        isResizing = false;
        initializeLogPanel();
        setupResizeHandlers();
    }

    public void initializeLogPanel() {
        logPanel = new VerticalPanel();
        logPanel.setWidth(logPanelWidth + "px");
        // AI_TODO: Remove fixed height, let it be calculated dynamically
        // logPanel.setHeight(logPanelHeight + "px"); - Remove this line
        logPanel.getElement().addClassName("logPanel");
        GWTUtils.setStyles(logPanel,
            "position", "relative",
            "display", "flex",
            "flexDirection", "column",
            "boxSizing", "border-box"
        );
        // AI_THINK: Allow panel to fill available space dynamically
        GWTUtils.setStyles(logPanel,
            "height", "100%",
            "maxHeight", "100%"
        );

        // AI_TODO: Fix header layout and width distribution
        headerLabel = new Label("Console Logs");
        headerLabel.getElement().addClassName("logHeader");
        headerLabel.setWidth("100%");
        GWTUtils.setStyles(headerLabel,
            "flexShrink", "0",
            "padding", "8px",
            "borderBottom", "1px solid #ccc",
            "backgroundColor", "#f5f5f5",
            "fontWeight", "bold",
            "textAlign", "center"
        );
        logPanel.add(headerLabel);

        // AI_TODO: Fix control panel width distribution and layout
        controlPanel = new HorizontalPanel();
        controlPanel.setWidth("100%");
        GWTUtils.setStyles(controlPanel,
            "flexShrink", "0",
            "padding", "8px",
            "borderBottom", "1px solid #ccc",
            "boxSizing", "border-box",
            "backgroundColor", "#fafafa"
        );

        // AI_THINK: Improve button and label spacing
        GWTUtils.setStyles(controlPanel,
            "display", "flex",
            "justifyContent", "space-between",
            "alignItems", "center"
        );

        clearLogsButton = new Button("Clear Logs");
        clearLogsButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                clearLogs();
            }
        });
        GWTUtils.setPadding(clearLogsButton, "4px 12px");

        logCountLabel = new Label("0 entries");
        logCountLabel.getElement().addClassName("logCount");
        GWTUtils.setStyles(logCountLabel,
            "fontSize", "12px",
            "color", "#666",
            "fontStyle", "italic"
        );

        controlPanel.add(clearLogsButton);
        controlPanel.add(logCountLabel);
        logPanel.add(controlPanel);

        // AI_TODO: Fix entries panel width and content layout
        logEntriesPanel = new VerticalPanel();
        logEntriesPanel.setWidth("100%");
        GWTUtils.setStyles(logEntriesPanel,
            "padding", "0",
            "boxSizing", "border-box"
        );

        // AI_TODO: Configure scroll panel for full space utilization
        logScrollPanel = new ScrollPanel();
        logScrollPanel.setWidget(logEntriesPanel);
        logScrollPanel.setWidth("100%");
        logScrollPanel.getElement().addClassName("logScrollPanel");

        // AI_THINK: Setup flex properties for dynamic height filling
        GWTUtils.setStyles(logScrollPanel,
            "flex", "1 1 auto",
            "overflowY", "auto",
            "overflowX", "hidden",
            "border", "1px solid #ddd",
            "margin", "0",
            "boxSizing", "border-box",
            "backgroundColor", "#ffffff"
        );

        // AI_THINK: Remove conflicting height properties that prevent dynamic sizing
        // Remove fixed height constraints - let flex handle it
        GWTUtils.setFlexItem(logScrollPanel, "1", "1", "0");

        logPanel.add(logScrollPanel);

        resizeHandle = new HTML();
        resizeHandle.getElement().addClassName("logResizeHandle");
        GWTUtils.setStyles(resizeHandle,
            "position", "absolute",
            "right", "-5px",
            "top", "0px",
            "width", "10px",
            "height", "100%",
            "cursor", "ew-resize",
            "backgroundColor", "rgba(128, 128, 128, 0.3)",
            "borderRight", "2px solid #888",
            "zIndex", "1000"
        );
        resizeHandle.getElement().setTitle("Drag to resize log panel");

        logPanel.add(resizeHandle);
    }

    public void setupResizeHandlers() {
        resizeHandle.addMouseDownHandler(new MouseDownHandler() {
            public void onMouseDown(MouseDownEvent event) {
                isResizing = true;
                startX = event.getClientX();
                startWidth = logPanelWidth;
                event.preventDefault();

                // Додаємо обробники до document для відстеження миші за межами елемента
                addDocumentMouseHandlers();
            }
        });
    }

    public native void addDocumentMouseHandlers() /*-{
        var self = this;
        var mouseMoveHandler = function(e) {
            self.@com.lushprojects.circuitjs1.client.LogManager::handleMouseMove(II)(e.clientX, e.clientY);
        };
        var mouseUpHandler = function(e) {
            self.@com.lushprojects.circuitjs1.client.LogManager::handleMouseUp()();
            $doc.removeEventListener('mousemove', mouseMoveHandler);
            $doc.removeEventListener('mouseup', mouseUpHandler);
        };

        $doc.addEventListener('mousemove', mouseMoveHandler);
        $doc.addEventListener('mouseup', mouseUpHandler);
    }-*/;

    public void handleMouseMove(int clientX, int clientY) {
        if (isResizing) {
            int deltaX = clientX - startX;
            int newWidth = startWidth + deltaX;

            if (newWidth < MIN_LOG_PANEL_WIDTH) {
                newWidth = MIN_LOG_PANEL_WIDTH;
            } else if (newWidth > MAX_LOG_PANEL_WIDTH) {
                newWidth = MAX_LOG_PANEL_WIDTH;
            }

            logPanelWidth = newWidth;

            cirSim.updateLogPanelWidth(newWidth);
        }
    }

    public void handleMouseUp() {
        isResizing = false;
    }

    public void updatePanelWidth(int newWidth) {
        logPanelWidth = newWidth;
        logPanel.setWidth(newWidth + "px");

        updateInternalComponentsSize(newWidth);
        updateResizeHandlePosition();

        // AI_THINK: Recalculate height when width changes to prevent floating issues
        // Force height recalculation to maintain proper proportions
        int currentHeight = logPanel.getElement().getClientHeight();
        if (currentHeight > 0) {
            updatePanelHeight(currentHeight);
        }
    }

    // AI_THINK: Improved method to handle dynamic height calculation
    public void updatePanelHeight(int availableHeight) {
        if (logPanel == null) return;

        // Calculate actual component heights dynamically
        int headerHeight = getActualHeaderHeight();
        int controlPanelHeight = getActualControlPanelHeight();
        int margins = 10;

        // Use full available height with proper minimum constraints
        logPanelHeight = Math.max(MIN_LOG_PANEL_HEIGHT, availableHeight - margins);

        // AI_THINK: Use flex layout properly instead of fixed heights
        // Remove conflicting height properties that cause floating issues
        GWTUtils.setStyles(logPanel,
            "height", logPanelHeight + "px",
            "maxHeight", logPanelHeight + "px",
            "minHeight", MIN_LOG_PANEL_HEIGHT + "px",
            "display", "flex",
            "flexDirection", "column"
        );

        // AI_TODO: Fix scroll panel to use flex-grow instead of calculated height
        if (logScrollPanel != null) {
            // Remove explicit height calculations that conflict with flex layout
            GWTUtils.clearStyle(logScrollPanel, "height");
            GWTUtils.clearStyle(logScrollPanel, "maxHeight");
            GWTUtils.clearStyle(logScrollPanel, "minHeight");

            // Use flex properties for proper responsive behavior
            GWTUtils.setStyles(logScrollPanel,
                "flex", "1 1 auto",
                "overflowY", "auto",
                "overflowX", "hidden",
                "minHeight", "100px"
            );

            // AI_THINK: Ensure entries panel height adapts to content
            if (logEntriesPanel != null) {
                GWTUtils.setStyles(logEntriesPanel,
                    "minHeight", "100%",
                    "height", "auto"  // Let content determine height
                );
            }
        }
    }

    // NEW: Helper method to get actual header height dynamically
    private int getActualHeaderHeight() {
        if (headerLabel == null) return 30; // Fallback value

        // Get actual rendered height or use computed height
        int actualHeight = headerLabel.getElement().getOffsetHeight();

        // If not yet rendered, calculate based on styling
        if (actualHeight <= 0) {
            // Calculate based on font size + padding
            String padding = headerLabel.getElement().getStyle().getPadding();
            int paddingValue = parsePaddingValue(padding, 5); // Default 5px

            // Estimate text height (typically 16-20px for normal text)
            int textHeight = 18;
            actualHeight = textHeight + (paddingValue * 2);
        }

        return Math.max(actualHeight, 20); // Minimum 20px
    }

    // NEW: Helper method to get actual control panel height dynamically
    private int getActualControlPanelHeight() {
        if (controlPanel == null) return 42; // Fallback value

        // Get actual rendered height
        int actualHeight = controlPanel.getElement().getOffsetHeight();

        // If not yet rendered, calculate based on content
        if (actualHeight <= 0) {
            // Get button height + padding
            int buttonHeight = (clearLogsButton != null) ?
                Math.max(clearLogsButton.getElement().getOffsetHeight(), 24) : 24;

            String padding = controlPanel.getElement().getStyle().getPadding();
            int paddingValue = parsePaddingValue(padding, 5); // Default 5px

            actualHeight = buttonHeight + (paddingValue * 2);
        }

        return Math.max(actualHeight, 32); // Minimum 32px
    }

    // NEW: Helper method to parse CSS padding values
    private int parsePaddingValue(String padding, int defaultValue) {
        if (padding == null || padding.isEmpty()) return defaultValue;

        try {
            // Remove 'px' suffix and parse
            String numericPart = padding.replaceAll("[^0-9]", "");
            if (!numericPart.isEmpty()) {
                return Integer.parseInt(numericPart);
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors, use default
        }

        return defaultValue;
    }

    // IMPROVED: Force recalculation of heights when needed
    public void recalculateHeight(int availableHeight) {
        // Force layout update to ensure accurate measurements
        if (logPanel != null) {
            // Trigger layout recalculation
            GWTUtils.setStyle(logPanel, "visibility", "hidden");
            logPanel.getElement().getOffsetHeight(); // Force layout
            GWTUtils.clearStyle(logPanel, "visibility");
        }

        // Now update with accurate measurements
        updatePanelHeight(availableHeight);
    }

    // Combined method to update both width and height
    public void updatePanelSize() {
        updatePanelSize(logPanel.getElement().getOffsetWidth(), logPanel.getElement().getOffsetHeight());
    }

    public void updatePanelSize(int newWidth, int availableHeight) {
        updatePanelWidth(newWidth);
        updatePanelHeight(availableHeight);
    }

    public void updateInternalComponentsSize(int panelWidth) {
        if (logPanel == null) return;

        // Calculate effective width for internal components (accounting for padding/margins)
        int effectiveWidth = panelWidth - 10; // Account for panel margins

        for (int i = 0; i < logPanel.getWidgetCount(); i++) {
            Widget widget = logPanel.getWidget(i);

            if (widget == resizeHandle) continue;

            widget.setWidth("100%");

            if (widget instanceof HorizontalPanel) {
                HorizontalPanel hPanel = (HorizontalPanel) widget;
                hPanel.setWidth("100%");

                for (int j = 0; j < hPanel.getWidgetCount(); j++) {
                    Widget child = hPanel.getWidget(j);
                    if (child instanceof Button) {
                        continue;
                    } else if (child instanceof Label) {
                        GWTUtils.setStyles(child,
                            "flex", "1",
                            "textAlign", "right"
                        );
                    }
                }
            }
        }

        // Explicitly update logScrollPanel width
        if (logScrollPanel != null) {
            logScrollPanel.setWidth("100%");
            // Set explicit pixel width to ensure proper resizing
            GWTUtils.setStyle(logScrollPanel, "width", effectiveWidth + "px");

            // Update logEntriesPanel width as well
            if (logEntriesPanel != null) {
                logEntriesPanel.setWidth("100%");
                // Account for scroll panel padding and borders
                int entriesPanelWidth = effectiveWidth - 12; // Account for scroll panel margins and borders
                GWTUtils.setStyle(logEntriesPanel, "width", entriesPanelWidth + "px");

                // Update width of all log entry widgets
                for (int i = 0; i < logEntriesPanel.getWidgetCount(); i++) {
                    Widget logEntry = logEntriesPanel.getWidget(i);
                    logEntry.setWidth("100%");
                    GWTUtils.setStyle(logEntry, "width", (entriesPanelWidth - 10) + "px");
                }
            }
        }

        GWTUtils.setStyle(logPanel, "width", panelWidth + "px");
    }

    public void updateResizeHandlePosition() {
        if (resizeHandle != null) {
            GWTUtils.setStyle(resizeHandle, "right", "-5px");
        }
    }

    public void addLogEntry(String message) {
        DateTimeFormat dateFormat = DateTimeFormat.getFormat("HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;

        logEntries.add(logEntry);

        Label logLabel = new Label(logEntry);
        logLabel.getElement().addClassName("logEntry");
        logLabel.setWidth("100%");

        // AI_TODO: Improve log entry styling for better width distribution
        GWTUtils.setStyles(logLabel,
            "padding", "4px 8px",
            "borderBottom", "1px solid #eee",
            "fontSize", "11px",
            "fontFamily", "monospace",
            "lineHeight", "1.4",
            "wordWrap", "break-word",
            "overflow", "hidden",
            "textOverflow", "ellipsis",
            "whiteSpace", "nowrap"
        );

        // AI_THINK: Dynamic width calculation based on current panel width
        if (logEntriesPanel != null) {
            // Use full available width minus small margins
            int availableWidth = logPanelWidth - 20; // Account for scroll panel and borders
            GWTUtils.setStyles(logLabel,
                "width", "100%",
                "maxWidth", availableWidth + "px",
                "boxSizing", "border-box"
            );
        }

        logEntriesPanel.add(logLabel);

        updateLogCount();
        scrollToBottom();
    }

    public void clearLogs() {
        logEntries.clear();
        logEntriesPanel.clear();
        updateLogCount();
    }

    public void updateLogCount() {
        int count = logEntries.size();
        logCountLabel.setText(count + " entries");
    }

    public void scrollToBottom() {
        logScrollPanel.scrollToBottom();
    }
}
