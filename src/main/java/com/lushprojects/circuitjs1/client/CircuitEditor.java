package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.ContextMenuHandler;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.event.dom.client.DoubleClickHandler;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;
import com.lushprojects.circuitjs1.client.dialog.ScrollValuePopup;
import com.lushprojects.circuitjs1.client.element.CapacitorElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.GraphicElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.LogicInputElm;
import com.lushprojects.circuitjs1.client.element.ResistorElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.element.SwitchElm;
import com.lushprojects.circuitjs1.client.element.WireElm;

public class CircuitEditor extends BaseCirSimDelegate implements MouseDownHandler, MouseMoveHandler, MouseUpHandler,
    ClickHandler, DoubleClickHandler, ContextMenuHandler, MouseOutHandler, MouseWheelHandler {

    private static final int POST_GRAB_SQ = 25;
    private static final int MIN_POST_GRAB_SIZE = 256;
    private static final int DRAG_DELAY = 150;

    public MouseMode mouseMode = MouseMode.SELECT;
    MouseMode tempMouseMode = MouseMode.SELECT;

    String mouseModeStr = "Select";

    int dragGridX;
    int dragGridY;
    int dragScreenX;
    int dragScreenY;
    int initDragGridX;
    int initDragGridY;
    boolean dragging = false;

    long mouseDownTime;
    long zoomTime;
    int mouseCursorX = -1;
    int mouseCursorY = -1;
    Rectangle selectedArea;

    public CircuitElm dragElm;

    public CircuitElm mouseElm = null;
    public CircuitElm menuElm;

    boolean didSwitch = false;
    int mousePost = -1;
    public CircuitElm plotXElm, plotYElm;
    int draggingPost;

    public double wheelSensitivity = 1;

    private boolean mouseDragging;
    String lastCursorStyle;
    boolean mouseWasOverSplitter = false;

    public int gridSize;
    public int gridMask;
    public int gridRound;

    SwitchElm heldSwitchElm;

    int menuX = 0;
    int menuY = 0;
    int menuClientX = 0;
    int menuClientY = 0;

    protected CircuitEditor(CirSim cirSim) {
        super(cirSim);
        setWheelSensitivity();
    }

    void setWheelSensitivity() {
        wheelSensitivity = Double.parseDouble(OptionsManager.getOptionFromStorage("wheelSensitivity", "1"));
    }

    void setMouseMode(String modeString) {
        if (!modeString.isEmpty()) {
            mouseModeStr = modeString;
        }
        switch (modeString) {
            case "DragAll":
                setMouseMode(MouseMode.DRAG_ALL);
                break;
            case "DragRow":
                setMouseMode(MouseMode.DRAG_ROW);
                break;
            case "DragColumn":
                setMouseMode(MouseMode.DRAG_COLUMN);
                break;
            case "DragSelected":
                setMouseMode(MouseMode.DRAG_SELECTED);
                break;
            case "DragPost":
                setMouseMode(MouseMode.DRAG_POST);
                break;
            case "Select":
                setMouseMode(MouseMode.SELECT);
                break;
            default:
                setMouseMode(MouseMode.ADD_ELM);
                break;
        }

        tempMouseMode = mouseMode;
    }

    void setMouseMode(MouseMode mode) {
        mouseMode = mode;
        if (mode == MouseMode.ADD_ELM) {
            setCursorStyle("cursorCross");
        } else {
            setCursorStyle("cursorPointer");
        }
    }

    void setMenuSelection() {
        if (menuElm != null) {
            if (menuElm.selected) {
                return;
            }
            clearSelection();
            menuElm.setSelected(true);
        }
    }

    void setCursorStyle(String styleName) {
        if (lastCursorStyle != styleName) {
            Canvas canvas = renderer().getCanvas();
            if (lastCursorStyle != null) {
                canvas.removeStyleName(lastCursorStyle);
            }
            canvas.addStyleName(styleName);
            lastCursorStyle = styleName;
        }
    }

    public void mouseDragged(MouseMoveEvent event) {
        if (isRightMouseButton(event)) {
            return;
        }

        if (tempMouseMode == MouseMode.DRAG_SPLITTER) {
            dragSplitter(event.getX(), event.getY());
            return;
        }

        int gridX = renderer().inverseTransformX(event.getX());
        int gridY = renderer().inverseTransformY(event.getY());
        if (!renderer().circuitArea.contains(event.getX(), event.getY())) {
            return;
        }

        boolean changed = false;
        if (dragElm != null) {
            dragElm.drag(gridX, gridY);
        }

        boolean success = true;
        switch (tempMouseMode) {
            case DRAG_ALL:
                dragAll(event.getX(), event.getY());
                break;
            case DRAG_ROW:
                dragRow(snapGrid(gridX), snapGrid(gridY));
                changed = true;
                break;
            case DRAG_COLUMN:
                dragColumn(snapGrid(gridX), snapGrid(gridY));
                changed = true;
                break;
            case DRAG_POST:
                if (mouseElm != null) {
                    dragPost(snapGrid(gridX), snapGrid(gridY), event.isShiftKeyDown());
                    changed = true;
                }
                break;
            case SELECT:
                if (mouseElm == null) {
                    selectArea(gridX, gridY, event.isShiftKeyDown());
                } else if (!menuManager().noEditCheckItem.getState()) {
                    if (System.currentTimeMillis() - mouseDownTime < DRAG_DELAY) {
                        return;
                    }
                    tempMouseMode = MouseMode.DRAG_SELECTED;
                    changed = success = dragSelected(gridX, gridY);
                }
                break;
            case DRAG_SELECTED:
                changed = success = dragSelected(gridX, gridY);
                break;
            default:
                break;
        }

        dragging = true;
        if (success) {
            updateDragCoordinates(event.getX(), event.getY());
        }
        if (changed) {
            cirSim.setUnsavedChanges(true);
        }

        renderer().repaint();
    }

    private boolean isRightMouseButton(MouseMoveEvent event) {
        return event.getNativeButton() == NativeEvent.BUTTON_RIGHT &&
            !(event.isMetaKeyDown() || event.isShiftKeyDown() || event.isControlKeyDown() || event.isAltKeyDown());
    }

    private void updateDragCoordinates(int screenX, int screenY) {
        dragScreenX = screenX;
        dragScreenY = screenY;
        dragGridX = renderer().inverseTransformX(dragScreenX);
        dragGridY = renderer().inverseTransformY(dragScreenY);
        if (!(tempMouseMode == MouseMode.DRAG_SELECTED && onlyGraphicsElmsSelected())) {
            dragGridX = snapGrid(dragGridX);
            dragGridY = snapGrid(dragGridY);
        }
    }


    void dragSplitter(int x, int y) {
        double height = renderer().canvasHeight;
        if (height < 1) {
            height = 1;
        }
        double scopeHeightFraction = 1.0 - (((double) y) / height);
        if (scopeHeightFraction < 0.1) {
            scopeHeightFraction = 0.1;
        }
        if (scopeHeightFraction > 0.9) {
            scopeHeightFraction = 0.9;
        }
        renderer().scopeHeightFraction = scopeHeightFraction;
        renderer().setCircuitArea();
        cirSim.repaint();
    }

    void dragAll(int x, int y) {
        int deltaX = x - dragScreenX;
        int deltaY = y - dragScreenY;
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        renderer().transform[4] += deltaX;
        renderer().transform[5] += deltaY;
        dragScreenX = x;
        dragScreenY = y;
    }

    void dragRow(int x, int y) {
        int deltaY = y - dragGridY;
        if (deltaY == 0) {
            return;
        }
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.y == dragGridY) {
                element.movePoint(0, 0, deltaY);
            }
            if (element.y2 == dragGridY) {
                element.movePoint(1, 0, deltaY);
            }
        }
        removeZeroLengthElements();
    }

    void dragColumn(int x, int y) {
        int deltaX = x - dragGridX;
        if (deltaX == 0) {
            return;
        }
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.x == dragGridX) {
                element.movePoint(0, deltaX, 0);
            }
            if (element.x2 == dragGridX) {
                element.movePoint(1, deltaX, 0);
            }
        }
        removeZeroLengthElements();
    }

    boolean dragSelected(int x, int y) {
        boolean isMouseElmSelected = false;
        if (mouseElm != null && !mouseElm.isSelected()) {
            mouseElm.setSelected(isMouseElmSelected = true);
        }

        if (!onlyGraphicsElmsSelected()) {
            x = snapGrid(x);
            y = snapGrid(y);
        }

        int deltaX = x - dragGridX;
        int deltaY = y - dragGridY;
        if (deltaX == 0 && deltaY == 0) {
            if (isMouseElmSelected) {
                mouseElm.setSelected(false);
            }
            return false;
        }

        boolean allowed = true;
        CircuitSimulator simulator = simulator();
        for (int i = 0; allowed && i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.isSelected() && !element.allowMove(deltaX, deltaY)) {
                allowed = false;
            }
        }

        if (allowed) {
            for (CircuitElm element: simulator.elmList) {
                if (element.isSelected()) {
                    element.move(deltaX, deltaY);
                }
            }
            cirSim.needAnalyze();
        }

        if (isMouseElmSelected) {
            mouseElm.setSelected(false);
        }

        return allowed;
    }

    void dragPost(int x, int y, boolean all) {
        if (draggingPost == -1) {
            draggingPost = (Graphics.distanceSq(mouseElm.x, mouseElm.y, x, y) >
                Graphics.distanceSq(mouseElm.x2, mouseElm.y2, x, y)) ? 1 : 0;
        }
        int deltaX = x - dragGridX;
        int deltaY = y - dragGridY;
        if (deltaX == 0 && deltaY == 0) {
            return;
        }

        if (all) {
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm element = simulator.elmList.get(i);
                int postIndex = -1;
                if (element.x == dragGridX && element.y == dragGridY) {
                    postIndex = 0;
                } else if (element.x2 == dragGridX && element.y2 == dragGridY) {
                    postIndex = 1;
                }
                if (postIndex != -1) {
                    element.movePoint(postIndex, deltaX, deltaY);
                }
            }
        } else {
            mouseElm.movePoint(draggingPost, deltaX, deltaY);
        }
        cirSim.needAnalyze();
    }

    void selectArea(int x, int y, boolean add) {
        int x1 = Math.min(x, initDragGridX);
        int x2 = Math.max(x, initDragGridX);
        int y1 = Math.min(y, initDragGridY);
        int y2 = Math.max(y, initDragGridY);
        selectedArea = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        for (CircuitElm element: simulator().elmList) {
            element.selectRect(selectedArea, add);
        }
        cirSim.enableDisableMenuItems();
    }

    void setMouseElm(CircuitElm element) {
        if (element != mouseElm) {
            if (mouseElm != null) {
                mouseElm.setMouseElm(false);
            }
            if (element != null) {
                element.setMouseElm(true);
            }
            mouseElm = element;
            cirSim.adjustableManager.setMouseElm(element);

            renderer().repaint();
        }
    }

    void removeZeroLengthElements() {
        boolean needAnalyze = false;
        CircuitSimulator simulator = simulator();
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.x == element.x2 && element.y == element.y2) {
                simulator.elmList.remove(i);
                element.delete();
                needAnalyze = true;
            }
        }

        if (needAnalyze) {
            cirSim.needAnalyze();
        }
    }

    boolean mouseIsOverSplitter(int x, int y) {
        if (scopeManager().scopeCount == 0) {
            return false;
        }
        boolean isOverSplitter = (x >= 0) && (x < renderer().circuitArea.width) &&
            (y >= renderer().circuitArea.height - 5) && (y < renderer().circuitArea.height);
        if (isOverSplitter != mouseWasOverSplitter) {
            if (isOverSplitter) {
                setCursorStyle("cursorSplitter");
            } else {
                setMouseMode(mouseMode);
            }
        }
        mouseWasOverSplitter = isOverSplitter;
        return isOverSplitter;
    }

    public void onMouseMove(MouseMoveEvent event) {
        event.preventDefault();
        mouseCursorX = event.getX();
        mouseCursorY = event.getY();
        if (mouseDragging) {
            mouseDragged(event);
            return;
        }
        mouseSelect(event);
        scopeManager().scopeMenuSelected = -1;
    }

    public void mouseSelect(MouseEvent<?> event) {
        int screenX = event.getX();
        int screenY = event.getY();
        updateDragCoordinates(screenX, screenY);
        draggingPost = -1;
        mousePost = -1;
        plotXElm = plotYElm = null;

        if (mouseIsOverSplitter(screenX, screenY)) {
            setMouseElm(null);
            return;
        }

        CircuitElm newMouseElm = findElm(screenX, screenY);
        scopeManager().scopeSelected = -1;

        if (newMouseElm == null) {
            newMouseElm = findElmInScope(screenX, screenY);
        }

        if (newMouseElm == null) {
            newMouseElm = findElmByPost(screenX, screenY);
        } else {
            findPostOnElm(newMouseElm, screenX, screenY);
        }

        setMouseElm(newMouseElm);
    }

    private CircuitElm findElm(int screenX, int screenY) {
        int gridX = renderer().inverseTransformX(screenX);
        int gridY = renderer().inverseTransformY(screenY);

        if (renderer().circuitArea.contains(screenX, screenY)) {
            if (mouseElm != null && (mouseElm.getHandleGrabbedClose(gridX, gridY, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0)) {
                return mouseElm;
            }

            int bestDist = Integer.MAX_VALUE;
            CircuitElm bestElm = null;
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm element = simulator.elmList.get(i);
                if (element.boundingBox.contains(gridX, gridY)) {
                    int dist = element.getMouseDistance(gridX, gridY);
                    if (dist >= 0) {
                        int sizePenalty = (element.boundingBox.width + element.boundingBox.height) / 4;
                        int totalDist = dist + sizePenalty;
                        if (totalDist < bestDist) {
                            bestDist = totalDist;
                            bestElm = element;
                        }
                    }
                }
            }
            return bestElm;
        }
        return null;
    }

    private CircuitElm findElmInScope(int screenX, int screenY) {
        ScopeManager scopeManager = scopeManager();
        for (int i = 0; i != scopeManager.scopeCount; i++) {
            Scope scope = scopeManager.scopes[i];
            if (scope.rect.contains(screenX, screenY)) {
                if (scope.plotXY) {
                    plotXElm = scope.getXElm();
                    plotYElm = scope.getYElm();
                }
                scopeManager.scopeSelected = i;
                return scope.getElm();
            }
        }
        return null;
    }

    private CircuitElm findElmByPost(int screenX, int screenY) {
        int gridX = renderer().inverseTransformX(screenX);
        int gridY = renderer().inverseTransformY(screenY);
        int bestPostDist = 26;
        CircuitElm bestPostElm = null;
        int bestPost = -1;

        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (mouseMode == MouseMode.DRAG_POST) {
                if (element.getHandleGrabbedClose(gridX, gridY, POST_GRAB_SQ, 0) >= 0) {
                    return element;
                }
            }
            int postCount = element.getPostCount();
            for (int j = 0; j != postCount; j++) {
                Point pt = element.getPost(j);
                int dist = Graphics.distanceSq(pt.x, pt.y, gridX, gridY);
                if (dist < bestPostDist) {
                    bestPostDist = dist;
                    bestPostElm = element;
                    bestPost = j;
                }
            }
        }

        if (bestPostElm != null) {
            mousePost = bestPost;
            return bestPostElm;
        }
        return null;
    }

    private void findPostOnElm(CircuitElm elm, int screenX, int screenY) {
        int gridX = renderer().inverseTransformX(screenX);
        int gridY = renderer().inverseTransformY(screenY);
        int bestPostDist = 26;
        int bestPost = -1;

        for (int i = 0; i != elm.getPostCount(); i++) {
            Point pt = elm.getPost(i);
            int dist = Graphics.distanceSq(pt.x, pt.y, gridX, gridY);
            if (dist < bestPostDist) {
                bestPostDist = dist;
                bestPost = i;
            }
        }
        if (bestPost != -1) {
            mousePost = bestPost;
        }
    }


    void twoFingerTouch(int x, int y) {
        tempMouseMode = MouseMode.DRAG_ALL;
        dragScreenX = x;
        dragScreenY = y;
    }

    void clearMouseElm() {
        scopeManager().scopeSelected = -1;
        setMouseElm(null);
        plotXElm = plotYElm = null;
    }

    public void onMouseDown(MouseDownEvent event) {
        event.preventDefault();
        renderer().getCanvas().setFocus(true);
        simulator().stopElm = null;
        menuX = menuClientX = event.getX();
        menuY = menuClientY = event.getY();
        mouseDownTime = System.currentTimeMillis();
        cirSim.enablePaste();

        if (event.getNativeButton() != NativeEvent.BUTTON_LEFT && event.getNativeButton() != NativeEvent.BUTTON_MIDDLE) {
            return;
        }

        mouseSelect(event);
        mouseDragging = true;
        didSwitch = false;

        if (mouseWasOverSplitter) {
            tempMouseMode = MouseMode.DRAG_SPLITTER;
            return;
        }

        setTemporaryMouseMode(event);

        if (menuManager().noEditCheckItem.getState()) {
            tempMouseMode = MouseMode.SELECT;
        }

        if (handleScopeSettings()) {
            return;
        }

        int gridX = renderer().inverseTransformX(event.getX());
        int gridY = renderer().inverseTransformY(event.getY());
        if (doSwitch(gridX, gridY)) {
            didSwitch = true;
            return;
        }

        if (tempMouseMode == MouseMode.SELECT && mouseElm != null && !menuManager().noEditCheckItem.getState() &&
            mouseElm.getHandleGrabbedClose(gridX, gridY, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0 &&
            !anySelectedButMouse()) {
            tempMouseMode = MouseMode.DRAG_POST;
        }

        if (tempMouseMode != MouseMode.SELECT && tempMouseMode != MouseMode.DRAG_SELECTED) {
            clearSelection();
        }

        pushUndo();
        initDragGridX = gridX;
        initDragGridY = gridY;
        dragging = true;

        if (tempMouseMode == MouseMode.ADD_ELM) {
            int x0 = snapGrid(gridX);
            int y0 = snapGrid(gridY);
            if (renderer().circuitArea.contains(event.getX(), event.getY())) {
                try {
                    dragElm = CircuitElmCreator.constructElement(mouseModeStr, x0, y0);
                } catch (Exception ex) {
                    CirSim.debugger();
                }
            }
        }
    }

    private void setTemporaryMouseMode(MouseDownEvent event) {
        if (event.getNativeButton() == NativeEvent.BUTTON_LEFT) {
            tempMouseMode = mouseMode;
            if (event.isAltKeyDown() && event.isMetaKeyDown()) {
                tempMouseMode = MouseMode.DRAG_COLUMN;
            } else if (event.isAltKeyDown() && event.isShiftKeyDown()) {
                tempMouseMode = MouseMode.DRAG_ROW;
            } else if (event.isShiftKeyDown()) {
                tempMouseMode = MouseMode.SELECT;
            } else if (event.isAltKeyDown()) {
                tempMouseMode = MouseMode.DRAG_ALL;
            } else if (event.isControlKeyDown() || event.isMetaKeyDown()) {
                tempMouseMode = MouseMode.DRAG_POST;
            }
        } else {
            tempMouseMode = MouseMode.DRAG_ALL;
        }
    }

    private boolean handleScopeSettings() {
        ScopeManager scopeManager = scopeManager();
        if (!(cirSim.dialogIsShowing()) &&
            ((scopeManager.scopeSelected != -1 && scopeManager.scopes[scopeManager.scopeSelected].cursorInSettingsWheel()) ||
                (scopeManager.scopeSelected == -1 && mouseElm instanceof ScopeElm && ((ScopeElm) mouseElm).elmScope.cursorInSettingsWheel()))) {
            if (menuManager().noEditCheckItem.getState()) {
                return true;
            }
            Scope scope;
            if (scopeManager.scopeSelected != -1) {
                scope = scopeManager.scopes[scopeManager.scopeSelected];
            } else {
                scope = ((ScopeElm) mouseElm).elmScope;
            }
            scope.properties();
            clearSelection();
            mouseDragging = false;
            return true;
        }
        return false;
    }


    public void onMouseUp(MouseUpEvent event) {
        event.preventDefault();
        mouseDragging = false;

        if (tempMouseMode == MouseMode.SELECT && selectedArea == null) {
            clearSelection();
        }

        if (tempMouseMode == MouseMode.DRAG_POST && draggingPost == -1) {
            doSplit(mouseElm);
        }

        tempMouseMode = mouseMode;
        selectedArea = null;
        dragging = false;
        boolean circuitChanged = false;

        if (heldSwitchElm != null) {
            heldSwitchElm.mouseUp();
            heldSwitchElm = null;
            circuitChanged = true;
        }

        if (dragElm != null) {
            if (dragElm.creationFailed()) {
                dragElm.delete();
                if (mouseMode == MouseMode.SELECT || mouseMode == MouseMode.DRAG_SELECTED) {
                    clearSelection();
                }
            } else {
                simulator().elmList.add(dragElm);
                dragElm.draggingDone();
                circuitChanged = true;
            }
            dragElm = null;
        }

        if (circuitChanged) {
            cirSim.needAnalyze();
            pushUndo();
            cirSim.setUnsavedChanges(true);
        }

        if (dragElm != null) {
            dragElm.delete();
        }
        dragElm = null;
        renderer().repaint();
    }

    public void onMouseWheel(MouseWheelEvent event) {
        event.preventDefault();
        mouseCursorX = event.getX();
        mouseCursorY = event.getY();

        boolean zoomOnly = System.currentTimeMillis() < zoomTime + 1000;
        if (menuManager().noEditCheckItem.getState() || !menuManager().mouseWheelEditCheckItem.getState()) {
            zoomOnly = true;
        }

        if (!zoomOnly) {
            scrollValues(event.getNativeEvent().getClientX(), event.getNativeEvent().getClientY(), event.getDeltaY());
        }

        if (mouseElm instanceof MouseWheelHandler && !zoomOnly) {
            ((MouseWheelHandler) mouseElm).onMouseWheel(event);
        } else if (scopeManager().scopeSelected != -1 && !zoomOnly) {
            scopeManager().scopes[scopeManager().scopeSelected].onMouseWheel(event);
        } else if (!cirSim.dialogIsShowing()) {
            double zoomDelta = -event.getDeltaY() * wheelSensitivity;
            zoomDelta = Math.max(-50, Math.min(50, zoomDelta));
            renderer().zoomCircuit(zoomDelta, false);
            zoomTime = System.currentTimeMillis();
        }
        renderer().repaint();
    }

    public void onMouseOut(MouseOutEvent event) {
        mouseCursorX = -1;
        clearMouseElm();
    }

    public void onClick(ClickEvent event) {
        event.preventDefault();
        if ((event.getNativeButton() == NativeEvent.BUTTON_MIDDLE)) {
            scrollValues(event.getNativeEvent().getClientX(), event.getNativeEvent().getClientY(), 0);
        }
    }

    public void onContextMenu(ContextMenuEvent event) {
        event.preventDefault();
        menuX = event.getNativeEvent().getClientX();
        menuY = event.getNativeEvent().getClientY();
        menuClientX = menuX;
        menuClientY = menuY;
        cirSim.menuManager.doPopupMenu();
    }

    public void onDoubleClick(DoubleClickEvent event) {
        event.preventDefault();
        if (mouseElm != null && !(mouseElm instanceof SwitchElm) && !menuManager().noEditCheckItem.getState()) {
            doEditElementOptions(mouseElm);
        }
    }

    ScrollValuePopup scrollValuePopup;

    void scrollValues(int x, int y, int deltay) {
        if (mouseElm != null && !cirSim.dialogIsShowing() && scopeManager().scopeSelected == -1) {
            if (mouseElm instanceof ResistorElm || mouseElm instanceof CapacitorElm || mouseElm instanceof InductorElm) {
                scrollValuePopup = new ScrollValuePopup(x, y, deltay, mouseElm, cirSim);
                cirSim.setUnsavedChanges(true);
            }
        }
    }

    public int snapGrid(int value) {
        return (value + gridRound) & gridMask;
    }

    void setGrid() {
        gridSize = (menuManager().smallGridCheckItem.getState()) ? 8 : 16;
        gridMask = -gridSize;
        gridRound = gridSize / 2 - 1;
    }

    boolean doSwitch(int x, int y) {
        if (mouseElm == null || !(mouseElm instanceof SwitchElm)) {
            return false;
        }
        SwitchElm switchElm = (SwitchElm) mouseElm;
        if (!switchElm.getSwitchRect().contains(x, y)) {
            return false;
        }
        switchElm.toggle();
        if (switchElm.momentary) {
            heldSwitchElm = switchElm;
        }
        if (!(switchElm instanceof LogicInputElm)) {
            cirSim.needAnalyze();
        }
        cirSim.setUnsavedChanges(true);
        return true;
    }

    boolean onlyGraphicsElmsSelected() {
        if (mouseElm != null && !(mouseElm instanceof GraphicElm)) {
            return false;
        }
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.isSelected() && !(element instanceof GraphicElm)) {
                return false;
            }
        }
        return true;
    }

    void doFlip() {
        circuitEditor().menuElm.flipPosts();
        cirSim.needAnalyze();
    }

    void doSplit(CircuitElm element) {
        if (!(element instanceof WireElm)) {
            return;
        }
        int x = snapGrid(renderer().inverseTransformX(menuX));
        int y = snapGrid(renderer().inverseTransformY(menuY));
        if (element.x == element.x2) {
            x = element.x;
        } else {
            y = element.y;
        }

        if (x == element.x && y == element.y || x == element.x2 && y == element.y2) {
            return;
        }

        WireElm newWire = new WireElm(x, y);
        newWire.drag(element.x2, element.y2);
        element.drag(x, y);
        simulator().elmList.add(newWire);
        cirSim.needAnalyze();
    }

    static class FlipInfo {
        public int centerX, centerY, count;
    }

    FlipInfo prepareFlip() {
        pushUndo();
        circuitEditor().setMenuSelection();
        int minX = 30000, maxX = -30000;
        int minY = 30000, maxY = -30000;
        CircuitSimulator simulator = simulator();
        int count = simulator.countSelected();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            if (element.isSelected() || count == 0) {
                minX = Math.min(element.x, Math.min(element.x2, minX));
                maxX = Math.max(element.x, Math.max(element.x2, maxX));
                minY = Math.min(element.y, Math.min(element.y2, minY));
                maxY = Math.max(element.y, Math.max(element.y2, maxY));
            }
        }
        FlipInfo flipInfo = new FlipInfo();
        flipInfo.centerX = (minX + maxX) / 2;
        flipInfo.centerY = (minY + maxY) / 2;
        flipInfo.count = count;
        return flipInfo;
    }

    void flipX() {
        FlipInfo flipInfo = prepareFlip();
        int center2 = flipInfo.centerX * 2;
        CircuitSimulator simulator = simulator();
        for (CircuitElm element : simulator.elmList) {
            if (element.isSelected() || flipInfo.count == 0) {
                element.flipX(center2, flipInfo.count);
            }
        }
        cirSim.needAnalyze();
    }

    void flipY() {
        FlipInfo flipInfo = prepareFlip();
        int center2 = flipInfo.centerY * 2;
        CircuitSimulator simulator = simulator();
        for (CircuitElm element : simulator.elmList) {
            if (element.isSelected() || flipInfo.count == 0) {
                element.flipY(center2, flipInfo.count);
            }
        }
        cirSim.needAnalyze();
    }

    void flipXY() {
        FlipInfo flipInfo = prepareFlip();
        int xmy = snapGrid(flipInfo.centerX - flipInfo.centerY);
        CirSim.console("xmy " + xmy + " grid " + gridSize + " " + flipInfo.centerX + " " + flipInfo.centerY);
        CircuitSimulator simulator = simulator();
        for (CircuitElm element : simulator.elmList) {
            if (element.isSelected() || flipInfo.count == 0) {
                element.flipXY(xmy, flipInfo.count);
            }
        }
        cirSim.needAnalyze();
    }

    public void pushUndo() {
        undoManager().pushUndo();
        cirSim.enableUndoRedo();
        cirSim.circuitInfo.savedFlag = false;
    }

    void doUndo() {
        undoManager().doUndo();
        cirSim.enableUndoRedo();
        cirSim.setUnsavedChanges(true);
    }

    void doRedo() {
        undoManager().doRedo();
        cirSim.enableUndoRedo();
        cirSim.setUnsavedChanges(true);
    }

    void doRecover() {
        pushUndo();
        cirSim.circuitLoader.readCircuit(undoManager().recovery);
        cirSim.allowSave(false);
        menuManager().recoverItem.setEnabled(false);
        cirSim.circuitInfo.filePath = null;
        cirSim.circuitInfo.fileName = null;
        CirSim.changeWindowTitle(cirSim.circuitInfo.unsavedChanges);
    }

    void doCut() {
        pushUndo();
        circuitEditor().setMenuSelection();
        cirSim.clipboardManager.doCut();
        doDelete(true);
        cirSim.enablePaste();
    }

    void doDelete(boolean pushUndoFlag) {
        if (pushUndoFlag) {
            pushUndo();
        }
        boolean hasDeleted = false;
        CircuitSimulator simulator = simulator();
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm element = simulator.elmList.get(i);
            if (willDelete(element)) {
                if (element.isMouseElm()) {
                    setMouseElm(null);
                }
                element.delete();
                simulator.elmList.remove(i);
                hasDeleted = true;
            }
        }
        if (hasDeleted) {
            simulator.deleteUnusedScopeElms();
            cirSim.needAnalyze();
            undoManager().writeRecoveryToStorage();
            cirSim.setUnsavedChanges(true);
        }
    }

    boolean willDelete(CircuitElm element) {
        return element.isSelected() || element.isMouseElm();
    }

    String copyOfSelectedElms() {
        String resultString = actionManager().dumpOptions();
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();
        resultString += simulator().dumpSelectedItems();
        return resultString;
    }

    void doCopy() {
        boolean clearSelection = (menuElm != null && !menuElm.selected);
        setMenuSelection();
        cirSim.clipboardManager.doCopy();
        if (clearSelection) {
            clearSelection();
        }
        cirSim.enablePaste();
    }

    void doDuplicate() {
        setMenuSelection();
        String dumpString = copyOfSelectedElms();
        doPaste(dumpString);
    }

    void doPaste(String dump) {
        if (dump == null || dump.isEmpty()) {
            dump = cirSim.clipboardManager.getClipboard();
            if (dump == null || dump.isEmpty()) {
                return;
            }
        }

        pushUndo();
        clearSelection();
        Rectangle oldBoundingBox = getBoundingBoxOfCircuit();
        int oldSize = simulator().elmList.size();
        int flags = CircuitConst.RC_RETAIN;
        if (oldSize > 0) {
            flags |= CircuitConst.RC_NO_CENTER;
        }
        cirSim.circuitLoader.readCircuit(dump, flags);

        Rectangle newBoundingBox = selectNewItems(oldSize);

        if (oldBoundingBox != null && newBoundingBox != null) {
            int deltaX = 0, deltaY = 0;
            if (!oldBoundingBox.intersects(newBoundingBox)) {
                deltaX = snapGrid(oldBoundingBox.x - newBoundingBox.x);
                deltaY = snapGrid(oldBoundingBox.y - newBoundingBox.y);
            } else {
                int spaceW = renderer().circuitArea.width - oldBoundingBox.width - newBoundingBox.width;
                int spaceH = renderer().circuitArea.height - oldBoundingBox.height - newBoundingBox.height;
                if (spaceW > spaceH) {
                    deltaX = snapGrid(oldBoundingBox.x + oldBoundingBox.width - newBoundingBox.x + gridSize);
                } else {
                    deltaY = snapGrid(oldBoundingBox.y + oldBoundingBox.height - newBoundingBox.y + gridSize);
                }
            }

            if (mouseCursorX > 0 && renderer().circuitArea.contains(mouseCursorX, mouseCursorY)) {
                int gridX = renderer().inverseTransformX(mouseCursorX);
                int gridY = renderer().inverseTransformY(mouseCursorY);
                int mouseDeltaX = snapGrid(gridX - (newBoundingBox.x + newBoundingBox.width / 2));
                int mouseDeltaY = snapGrid(gridY - (newBoundingBox.y + newBoundingBox.height / 2));
                if (canMoveNewItems(oldSize, mouseDeltaX, mouseDeltaY)) {
                    deltaX = mouseDeltaX;
                    deltaY = mouseDeltaY;
                }
            }

            moveNewItems(oldSize, deltaX, deltaY);
        }
        cirSim.needAnalyze();
        undoManager().writeRecoveryToStorage();
        cirSim.setUnsavedChanges(true);
    }

    private Rectangle getBoundingBoxOfCircuit() {
        Rectangle boundingBox = null;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            Rectangle bb = element.getBoundingBox();
            if (boundingBox != null) {
                boundingBox = boundingBox.union(bb);
            } else {
                boundingBox = bb;
            }
        }
        return boundingBox;
    }

    private Rectangle selectNewItems(int oldSize) {
        Rectangle newBoundingBox = null;
        CircuitSimulator simulator = simulator();
        for (int i = oldSize; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.setSelected(true);
            Rectangle bb = element.getBoundingBox();
            if (newBoundingBox != null) {
                newBoundingBox = newBoundingBox.union(bb);
            } else {
                newBoundingBox = bb;
            }
        }
        return newBoundingBox;
    }

    private boolean canMoveNewItems(int oldSize, int deltaX, int deltaY) {
        CircuitSimulator simulator = simulator();
        for (int i = oldSize; i != simulator.elmList.size(); i++) {
            if (!simulator.elmList.get(i).allowMove(deltaX, deltaY)) {
                return false;
            }
        }
        return true;
    }

    private void moveNewItems(int oldSize, int deltaX, int deltaY) {
        CircuitSimulator simulator = simulator();
        for (int i = oldSize; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.move(deltaX, deltaY);
        }
    }

    void clearSelection() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.setSelected(false);
        }
        cirSim.enableDisableMenuItems();
    }

    void doSelectAll() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.setSelected(true);
        }
        cirSim.enableDisableMenuItems();
    }

    boolean anySelectedButMouse() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            if (simulator.elmList.get(i) != mouseElm && simulator.elmList.get(i).selected) {
                return true;
            }
        }
        return false;
    }

    void doEditOptions() {
        clearSelection();
        pushUndo();
        cirSim.dialogManager.showEditOptionsDialog();
    }

    void doEditElementOptions(CircuitElm elm) {
        clearSelection();
        pushUndo();
        cirSim.dialogManager.showEditElementDialog(elm);
    }

    void doSliders(CircuitElm ce) {
        clearSelection();
        pushUndo();
        cirSim.dialogManager.showSliderDialog(ce);
    }
}
