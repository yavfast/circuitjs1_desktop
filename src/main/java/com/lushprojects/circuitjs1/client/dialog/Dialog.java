package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.OptionsManager;

import java.util.HashSet;
import java.util.Set;

public class Dialog extends DialogBox {

    private enum HorizontalAnchor {
        LEFT,
        RIGHT
    }

    private enum VerticalAnchor {
        TOP,
        BOTTOM
    }

    private static boolean resizeHandlerInstalled;
    private static final Set<Dialog> showingDialogs = new HashSet<>();

    boolean closeOnEnter;
    boolean positionRestored = false;

    private boolean positionExplicitlySet = false;
    private HorizontalAnchor horizontalAnchor = HorizontalAnchor.LEFT;
    private VerticalAnchor verticalAnchor = VerticalAnchor.TOP;
    private int horizontalAnchorOffsetPx = 0;
    private int verticalAnchorOffsetPx = 0;

    private boolean collapsed = false;
    private Element collapseToggleElement;

    public Dialog() {
        this(false, true);
    }

    public Dialog(boolean autoHide, boolean modal) {
        super(autoHide, modal);
        closeOnEnter = true;
        loadPosition();
        loadCollapsedState();
    }

    @Override
    public void setPopupPosition(int left, int top) {
        positionExplicitlySet = true;
        super.setPopupPosition(left, top);
    }

    @Override
    public void show() {
        super.show();
        registerShowingDialog(this);

        // Wait until it is attached and has measured size.
        Scheduler.get().scheduleDeferred(() -> {
            ensureInitialPosition();
            clampIntoViewport();
            updateAnchorsFromCurrentPosition();
            ensureCollapseToggle();
            applyCollapsedState();
        });
    }

    @Override
    protected void endDragging(MouseUpEvent event) {
        super.endDragging(event);
        clampIntoViewport();
        updateAnchorsFromCurrentPosition();
        savePosition();
    }

    public void closeDialog() {
        hide();
    }

    @Override
    public void hide(boolean autoClosed) {
        savePosition();
        saveCollapsedState();
        unregisterShowingDialog(this);
        super.hide(autoClosed);
    }

    public void enterPressed() {
        if (closeOnEnter) {
            apply();
            closeDialog();
        }
    }

    void apply() {
        // Can be overridden by subclasses
    }

    protected String getOptionPrefix() {
        return null;
    }

    public boolean isPositionRestored() {
        return positionRestored;
    }

    private void savePosition() {
        String optionPrefix = getOptionPrefix();
        if (optionPrefix != null) {
            int left = getPopupLeft();
            int top = getPopupTop();
            String posKey = OptionsManager.getPrefixedKey(optionPrefix, "pos");
            OptionsManager.setOptionInStorage(posKey, left + "," + top);
        }
    }

    private void loadPosition() {
        positionRestored = false;
        String optionPrefix = getOptionPrefix();
        if (optionPrefix != null) {
            String posKey = OptionsManager.getPrefixedKey(optionPrefix, "pos");
            String posStr = OptionsManager.getOptionFromStorage(posKey, null);
            if (posStr != null) {
                try {
                    String[] parts = posStr.split(",");
                    if (parts.length == 2) {
                        int left = Integer.parseInt(parts[0]);
                        int top = Integer.parseInt(parts[1]);
                        // Don't treat restored position as an explicit caller-set position.
                        super.setPopupPosition(left, top);
                        positionRestored = true;
//                        Log.log("Restore position: ", posStr);
                    }
                } catch (NumberFormatException e) {
                    // Ignore if the position is not in the correct format
                }
            }
        }
    }

    private void ensureInitialPosition() {
        // If we don't have a persisted position and nobody positioned us explicitly,
        // default to centered instead of top-left.
        if (positionRestored || positionExplicitlySet) {
            return;
        }

        int dialogWidth = getOffsetWidth();
        int dialogHeight = getOffsetHeight();
        int clientWidth = Window.getClientWidth();
        int clientHeight = Window.getClientHeight();

        int left = Math.max(0, (clientWidth - dialogWidth) / 2);
        int top = Math.max(0, (clientHeight - dialogHeight) / 2);
        super.setPopupPosition(left, top);
    }

    private void clampIntoViewport() {
        int dialogWidth = getOffsetWidth();
        int dialogHeight = getOffsetHeight();
        int clientWidth = Window.getClientWidth();
        int clientHeight = Window.getClientHeight();

        if (dialogWidth <= 0 || dialogHeight <= 0 || clientWidth <= 0 || clientHeight <= 0) {
            return;
        }

        int maxLeft = Math.max(0, clientWidth - dialogWidth);
        int maxTop = Math.max(0, clientHeight - dialogHeight);

        int clampedLeft = Math.max(0, Math.min(getPopupLeft(), maxLeft));
        int clampedTop = Math.max(0, Math.min(getPopupTop(), maxTop));

        if (clampedLeft != getPopupLeft() || clampedTop != getPopupTop()) {
            super.setPopupPosition(clampedLeft, clampedTop);
        }
    }

    private void updateAnchorsFromCurrentPosition() {
        int dialogWidth = getOffsetWidth();
        int dialogHeight = getOffsetHeight();
        int clientWidth = Window.getClientWidth();
        int clientHeight = Window.getClientHeight();

        if (dialogWidth <= 0 || dialogHeight <= 0 || clientWidth <= 0 || clientHeight <= 0) {
            return;
        }

        int left = getPopupLeft();
        int top = getPopupTop();

        int right = Math.max(0, clientWidth - (left + dialogWidth));
        int bottom = Math.max(0, clientHeight - (top + dialogHeight));

        if (left <= right) {
            horizontalAnchor = HorizontalAnchor.LEFT;
            horizontalAnchorOffsetPx = Math.max(0, left);
        } else {
            horizontalAnchor = HorizontalAnchor.RIGHT;
            horizontalAnchorOffsetPx = right;
        }

        if (top <= bottom) {
            verticalAnchor = VerticalAnchor.TOP;
            verticalAnchorOffsetPx = Math.max(0, top);
        } else {
            verticalAnchor = VerticalAnchor.BOTTOM;
            verticalAnchorOffsetPx = bottom;
        }
    }

    private void applyAnchorsAfterResize() {
        int dialogWidth = getOffsetWidth();
        int dialogHeight = getOffsetHeight();
        int clientWidth = Window.getClientWidth();
        int clientHeight = Window.getClientHeight();

        if (dialogWidth <= 0 || dialogHeight <= 0 || clientWidth <= 0 || clientHeight <= 0) {
            return;
        }

        int left;
        if (horizontalAnchor == HorizontalAnchor.LEFT) {
            left = horizontalAnchorOffsetPx;
        } else {
            left = clientWidth - dialogWidth - horizontalAnchorOffsetPx;
        }

        int top;
        if (verticalAnchor == VerticalAnchor.TOP) {
            top = verticalAnchorOffsetPx;
        } else {
            top = clientHeight - dialogHeight - verticalAnchorOffsetPx;
        }

        super.setPopupPosition(left, top);
        clampIntoViewport();
    }

    private static void registerShowingDialog(Dialog dialog) {
        showingDialogs.add(dialog);
        ensureResizeHandler();
    }

    private static void unregisterShowingDialog(Dialog dialog) {
        showingDialogs.remove(dialog);
    }

    private static void ensureResizeHandler() {
        if (resizeHandlerInstalled) {
            return;
        }
        resizeHandlerInstalled = true;
        Window.addResizeHandler(new ResizeHandler() {
            @Override
            public void onResize(ResizeEvent event) {
                // Defer to let layout settle and dialog sizes update.
                Scheduler.get().scheduleDeferred(() -> {
                    for (Dialog dlg : showingDialogs) {
                        if (dlg != null && dlg.isShowing()) {
                            dlg.applyAnchorsAfterResize();
                        }
                    }
                });
            }
        });
    }

    private void ensureCollapseToggle() {
        if (collapseToggleElement != null) {
            updateCollapseToggleText();
            return;
        }

        // DialogBox caption is usually the first child with class containing "Caption".
        Element caption = findCaptionElement();
        if (caption == null) {
            return;
        }

        Element toggle = Document.get().createSpanElement();
        toggle.setInnerText("-");
        toggle.getStyle().setProperty("cursor", "pointer");
        toggle.getStyle().setProperty("float", "right");
        toggle.getStyle().setProperty("paddingLeft", "8px");

        caption.appendChild(toggle);
        collapseToggleElement = toggle;

        Event.sinkEvents(toggle, Event.ONCLICK);
        Event.setEventListener(toggle, evt -> {
            if (evt.getTypeInt() == Event.ONCLICK) {
                setCollapsed(!collapsed);
            }
        });

        updateCollapseToggleText();
    }

    private Element findCaptionElement() {
        Element root = getElement();
        for (int i = 0; i < root.getChildCount(); i++) {
            com.google.gwt.dom.client.Node node = root.getChild(i);
            if (node instanceof Element) {
                Element el = (Element) node;
                String className = el.getClassName();
                if (className != null && className.contains("Caption")) {
                    return el;
                }
            }
        }
        return null;
    }

    private void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        applyCollapsedState();
        updateCollapseToggleText();
        saveCollapsedState();

        Scheduler.get().scheduleDeferred(() -> {
            clampIntoViewport();
            updateAnchorsFromCurrentPosition();
        });
    }

    private void applyCollapsedState() {
        Widget content = getWidget();
        if (content != null) {
            content.setVisible(!collapsed);
        }
    }

    private void updateCollapseToggleText() {
        if (collapseToggleElement == null) {
            return;
        }
        collapseToggleElement.setInnerText(collapsed ? "+" : "-");
    }

    private void loadCollapsedState() {
        collapsed = false;
        String optionPrefix = getOptionPrefix();
        if (optionPrefix == null) {
            return;
        }
        String key = OptionsManager.getPrefixedKey(optionPrefix, "collapsed");
        String val = OptionsManager.getOptionFromStorage(key, null);
        if (val == null) {
            return;
        }
        collapsed = "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    private void saveCollapsedState() {
        String optionPrefix = getOptionPrefix();
        if (optionPrefix == null) {
            return;
        }
        String key = OptionsManager.getPrefixedKey(optionPrefix, "collapsed");
        OptionsManager.setOptionInStorage(key, collapsed ? "1" : "0");
    }
}

