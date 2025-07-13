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

    public int gridSize, gridMask, gridRound;
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

    void setMouseMode(String s) {
        if (!s.isEmpty()) {
            mouseModeStr = s;
        }
        switch (s) {
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

    void setCursorStyle(String s) {
        if (lastCursorStyle != s) {
            Canvas canvas = renderer().getCanvas();
            if (lastCursorStyle != null) {
                canvas.removeStyleName(lastCursorStyle);
            }
            canvas.addStyleName(s);
            lastCursorStyle = s;
        }
    }

    public void mouseDragged(MouseMoveEvent e) {
        if (isRightMouseButton(e)) {
            return;
        }

        if (tempMouseMode == MouseMode.DRAG_SPLITTER) {
            dragSplitter(e.getX(), e.getY());
            return;
        }

        int gx = renderer().inverseTransformX(e.getX());
        int gy = renderer().inverseTransformY(e.getY());
        if (!renderer().circuitArea.contains(e.getX(), e.getY())) {
            return;
        }

        boolean changed = false;
        if (dragElm != null) {
            dragElm.drag(gx, gy);
        }

        boolean success = true;
        switch (tempMouseMode) {
            case DRAG_ALL:
                dragAll(e.getX(), e.getY());
                break;
            case DRAG_ROW:
                dragRow(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case DRAG_COLUMN:
                dragColumn(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case DRAG_POST:
                if (mouseElm != null) {
                    dragPost(snapGrid(gx), snapGrid(gy), e.isShiftKeyDown());
                    changed = true;
                }
                break;
            case SELECT:
                if (mouseElm == null) {
                    selectArea(gx, gy, e.isShiftKeyDown());
                } else if (!menuManager().noEditCheckItem.getState()) {
                    if (System.currentTimeMillis() - mouseDownTime < DRAG_DELAY) {
                        return;
                    }
                    tempMouseMode = MouseMode.DRAG_SELECTED;
                    changed = success = dragSelected(gx, gy);
                }
                break;
            case DRAG_SELECTED:
                changed = success = dragSelected(gx, gy);
                break;
            default:
                break;
        }

        dragging = true;
        if (success) {
            updateDragCoordinates(e.getX(), e.getY());
        }
        if (changed) {
            cirSim.setUnsavedChanges(true);
        }

        renderer().repaint();
    }

    private boolean isRightMouseButton(MouseMoveEvent e) {
        return e.getNativeButton() == NativeEvent.BUTTON_RIGHT &&
            !(e.isMetaKeyDown() || e.isShiftKeyDown() || e.isControlKeyDown() || e.isAltKeyDown());
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
        double h = renderer().canvasHeight;
        if (h < 1) {
            h = 1;
        }
        double scopeHeightFraction = 1.0 - (((double) y) / h);
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
        int dx = x - dragScreenX;
        int dy = y - dragScreenY;
        if (dx == 0 && dy == 0) {
            return;
        }
        renderer().transform[4] += dx;
        renderer().transform[5] += dy;
        dragScreenX = x;
        dragScreenY = y;
    }

    void dragRow(int x, int y) {
        int dy = y - dragGridY;
        if (dy == 0) {
            return;
        }
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.y == dragGridY) {
                ce.movePoint(0, 0, dy);
            }
            if (ce.y2 == dragGridY) {
                ce.movePoint(1, 0, dy);
            }
        }
        removeZeroLengthElements();
    }

    void dragColumn(int x, int y) {
        int dx = x - dragGridX;
        if (dx == 0) {
            return;
        }
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.x == dragGridX) {
                ce.movePoint(0, dx, 0);
            }
            if (ce.x2 == dragGridX) {
                ce.movePoint(1, dx, 0);
            }
        }
        removeZeroLengthElements();
    }

    boolean dragSelected(int x, int y) {
        boolean me = false;
        if (mouseElm != null && !mouseElm.isSelected()) {
            mouseElm.setSelected(me = true);
        }

        if (!onlyGraphicsElmsSelected()) {
            x = snapGrid(x);
            y = snapGrid(y);
        }

        int dx = x - dragGridX;
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0) {
            if (me) {
                mouseElm.setSelected(false);
            }
            return false;
        }

        boolean allowed = true;
        CircuitSimulator simulator = simulator();
        for (int i = 0; allowed && i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.isSelected() && !ce.allowMove(dx, dy)) {
                allowed = false;
            }
        }

        if (allowed) {
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                if (ce.isSelected()) {
                    ce.move(dx, dy);
                }
            }
            cirSim.needAnalyze();
        }

        if (me) {
            mouseElm.setSelected(false);
        }

        return allowed;
    }

    void dragPost(int x, int y, boolean all) {
        if (draggingPost == -1) {
            draggingPost = (Graphics.distanceSq(mouseElm.x, mouseElm.y, x, y) >
                Graphics.distanceSq(mouseElm.x2, mouseElm.y2, x, y)) ? 1 : 0;
        }
        int dx = x - dragGridX;
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0) {
            return;
        }

        if (all) {
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm e = simulator.elmList.get(i);
                int p = -1;
                if (e.x == dragGridX && e.y == dragGridY) {
                    p = 0;
                } else if (e.x2 == dragGridX && e.y2 == dragGridY) {
                    p = 1;
                }
                if (p != -1) {
                    e.movePoint(p, dx, dy);
                }
            }
        } else {
            mouseElm.movePoint(draggingPost, dx, dy);
        }
        cirSim.needAnalyze();
    }

    void selectArea(int x, int y, boolean add) {
        int x1 = Math.min(x, initDragGridX);
        int x2 = Math.max(x, initDragGridX);
        int y1 = Math.min(y, initDragGridY);
        int y2 = Math.max(y, initDragGridY);
        selectedArea = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.selectRect(selectedArea, add);
        }
        cirSim.enableDisableMenuItems();
    }

    void setMouseElm(CircuitElm ce) {
        if (ce != mouseElm) {
            if (mouseElm != null) {
                mouseElm.setMouseElm(false);
            }
            if (ce != null) {
                ce.setMouseElm(true);
            }
            mouseElm = ce;
            cirSim.adjustableManager.setMouseElm(ce);
        }
        renderer().repaint();
    }

    void removeZeroLengthElements() {
        CircuitSimulator simulator = simulator();
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.x == ce.x2 && ce.y == ce.y2) {
                simulator.elmList.remove(i);
                ce.delete();
            }
        }
        cirSim.needAnalyze();
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

    public void onMouseMove(MouseMoveEvent e) {
        e.preventDefault();
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();
        if (mouseDragging) {
            mouseDragged(e);
            return;
        }
        mouseSelect(e);
        scopeManager().scopeMenuSelected = -1;
    }

    public void mouseSelect(MouseEvent<?> e) {
        int sx = e.getX();
        int sy = e.getY();
        updateDragCoordinates(sx, sy);
        draggingPost = -1;
        mousePost = -1;
        plotXElm = plotYElm = null;

        if (mouseIsOverSplitter(sx, sy)) {
            setMouseElm(null);
            return;
        }

        CircuitElm newMouseElm = findElm(sx, sy);
        scopeManager().scopeSelected = -1;

        if (newMouseElm == null) {
            newMouseElm = findElmInScope(sx, sy);
        }

        if (newMouseElm == null) {
            newMouseElm = findElmByPost(sx, sy);
        } else {
            findPostOnElm(newMouseElm, sx, sy);
        }

        setMouseElm(newMouseElm);
    }

    private CircuitElm findElm(int sx, int sy) {
        int gx = renderer().inverseTransformX(sx);
        int gy = renderer().inverseTransformY(sy);

        if (renderer().circuitArea.contains(sx, sy)) {
            if (mouseElm != null && (mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0)) {
                return mouseElm;
            }

            int bestDist = Integer.MAX_VALUE;
            CircuitElm bestElm = null;
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                if (ce.boundingBox.contains(gx, gy)) {
                    int dist = ce.getMouseDistance(gx, gy);
                    if (dist >= 0) {
                        int sizePenalty = (ce.boundingBox.width + ce.boundingBox.height) / 4;
                        int totalDist = dist + sizePenalty;
                        if (totalDist < bestDist) {
                            bestDist = totalDist;
                            bestElm = ce;
                        }
                    }
                }
            }
            return bestElm;
        }
        return null;
    }

    private CircuitElm findElmInScope(int sx, int sy) {
        ScopeManager scopeManager = scopeManager();
        for (int i = 0; i != scopeManager.scopeCount; i++) {
            Scope s = scopeManager.scopes[i];
            if (s.rect.contains(sx, sy)) {
                if (s.plotXY) {
                    plotXElm = s.getXElm();
                    plotYElm = s.getYElm();
                }
                scopeManager.scopeSelected = i;
                return s.getElm();
            }
        }
        return null;
    }

    private CircuitElm findElmByPost(int sx, int sy) {
        int gx = renderer().inverseTransformX(sx);
        int gy = renderer().inverseTransformY(sy);
        int bestPostDist = 26;
        CircuitElm bestPostElm = null;
        int bestPost = -1;

        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (mouseMode == MouseMode.DRAG_POST) {
                if (ce.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, 0) > 0) {
                    return ce;
                }
            }
            int jn = ce.getPostCount();
            for (int j = 0; j != jn; j++) {
                Point pt = ce.getPost(j);
                int dist = Graphics.distanceSq(pt.x, pt.y, gx, gy);
                if (dist < bestPostDist) {
                    bestPostDist = dist;
                    bestPostElm = ce;
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

    private void findPostOnElm(CircuitElm elm, int sx, int sy) {
        int gx = renderer().inverseTransformX(sx);
        int gy = renderer().inverseTransformY(sy);
        int bestPostDist = 26;
        int bestPost = -1;

        for (int i = 0; i != elm.getPostCount(); i++) {
            Point pt = elm.getPost(i);
            int dist = Graphics.distanceSq(pt.x, pt.y, gx, gy);
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

    public void onMouseDown(MouseDownEvent e) {
        e.preventDefault();
        renderer().getCanvas().setFocus(true);
        simulator().stopElm = null;
        menuX = menuClientX = e.getX();
        menuY = menuClientY = e.getY();
        mouseDownTime = System.currentTimeMillis();
        cirSim.enablePaste();

        if (e.getNativeButton() != NativeEvent.BUTTON_LEFT && e.getNativeButton() != NativeEvent.BUTTON_MIDDLE) {
            return;
        }

        mouseSelect(e);
        mouseDragging = true;
        didSwitch = false;

        if (mouseWasOverSplitter) {
            tempMouseMode = MouseMode.DRAG_SPLITTER;
            return;
        }

        setTemporaryMouseMode(e);

        if (menuManager().noEditCheckItem.getState()) {
            tempMouseMode = MouseMode.SELECT;
        }

        if (handleScopeSettings()) {
            return;
        }

        int gx = renderer().inverseTransformX(e.getX());
        int gy = renderer().inverseTransformY(e.getY());
        if (doSwitch(gx, gy)) {
            didSwitch = true;
            return;
        }

        if (tempMouseMode == MouseMode.SELECT && mouseElm != null && !menuManager().noEditCheckItem.getState() &&
            mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0 &&
            !anySelectedButMouse()) {
            tempMouseMode = MouseMode.DRAG_POST;
        }

        if (tempMouseMode != MouseMode.SELECT && tempMouseMode != MouseMode.DRAG_SELECTED) {
            clearSelection();
        }

        pushUndo();
        initDragGridX = gx;
        initDragGridY = gy;
        dragging = true;

        if (tempMouseMode == MouseMode.ADD_ELM) {
            int x0 = snapGrid(gx);
            int y0 = snapGrid(gy);
            if (renderer().circuitArea.contains(e.getX(), e.getY())) {
                try {
                    dragElm = CircuitElmCreator.constructElement(mouseModeStr, x0, y0);
                } catch (Exception ex) {
                    CirSim.debugger();
                }
            }
        }
    }

    private void setTemporaryMouseMode(MouseDownEvent e) {
        if (e.getNativeButton() == NativeEvent.BUTTON_LEFT) {
            tempMouseMode = mouseMode;
            if (e.isAltKeyDown() && e.isMetaKeyDown()) {
                tempMouseMode = MouseMode.DRAG_COLUMN;
            } else if (e.isAltKeyDown() && e.isShiftKeyDown()) {
                tempMouseMode = MouseMode.DRAG_ROW;
            } else if (e.isShiftKeyDown()) {
                tempMouseMode = MouseMode.SELECT;
            } else if (e.isAltKeyDown()) {
                tempMouseMode = MouseMode.DRAG_ALL;
            } else if (e.isControlKeyDown() || e.isMetaKeyDown()) {
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
            Scope s;
            if (scopeManager.scopeSelected != -1) {
                s = scopeManager.scopes[scopeManager.scopeSelected];
            } else {
                s = ((ScopeElm) mouseElm).elmScope;
            }
            s.properties();
            clearSelection();
            mouseDragging = false;
            return true;
        }
        return false;
    }


    public void onMouseUp(MouseUpEvent e) {
        e.preventDefault();
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

    public void onMouseWheel(MouseWheelEvent e) {
        e.preventDefault();
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();

        boolean zoomOnly = System.currentTimeMillis() < zoomTime + 1000;
        if (menuManager().noEditCheckItem.getState() || !menuManager().mouseWheelEditCheckItem.getState()) {
            zoomOnly = true;
        }

        if (!zoomOnly) {
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), e.getDeltaY());
        }

        if (mouseElm instanceof MouseWheelHandler && !zoomOnly) {
            ((MouseWheelHandler) mouseElm).onMouseWheel(e);
        } else if (scopeManager().scopeSelected != -1 && !zoomOnly) {
            scopeManager().scopes[scopeManager().scopeSelected].onMouseWheel(e);
        } else if (!cirSim.dialogIsShowing()) {
            double zoomDelta = -e.getDeltaY() * wheelSensitivity;
            zoomDelta = Math.max(-50, Math.min(50, zoomDelta));
            renderer().zoomCircuit(zoomDelta, false);
            zoomTime = System.currentTimeMillis();
        }
        renderer().repaint();
    }

    public void onMouseOut(MouseOutEvent e) {
        mouseCursorX = -1;
    }

    public void onClick(ClickEvent e) {
        e.preventDefault();
        if ((e.getNativeButton() == NativeEvent.BUTTON_MIDDLE)) {
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), 0);
        }
    }

    public void onContextMenu(ContextMenuEvent e) {
        e.preventDefault();
        menuX = e.getNativeEvent().getClientX();
        menuY = e.getNativeEvent().getClientY();
        menuClientX = menuX;
        menuClientY = menuY;
        cirSim.menuManager.doPopupMenu();
    }

    public void onDoubleClick(DoubleClickEvent e) {
        e.preventDefault();
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

    public int snapGrid(int x) {
        return (x + gridRound) & gridMask;
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
        SwitchElm se = (SwitchElm) mouseElm;
        if (!se.getSwitchRect().contains(x, y)) {
            return false;
        }
        se.toggle();
        if (se.momentary) {
            heldSwitchElm = se;
        }
        if (!(se instanceof LogicInputElm)) {
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
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.isSelected() && !(ce instanceof GraphicElm)) {
                return false;
            }
        }
        return true;
    }

    void doFlip() {
        circuitEditor().menuElm.flipPosts();
        cirSim.needAnalyze();
    }

    void doSplit(CircuitElm ce) {
        if (!(ce instanceof WireElm)) {
            return;
        }
        int x = snapGrid(renderer().inverseTransformX(menuX));
        int y = snapGrid(renderer().inverseTransformY(menuY));
        if (ce.x == ce.x2) {
            x = ce.x;
        } else {
            y = ce.y;
        }

        if (x == ce.x && y == ce.y || x == ce.x2 && y == ce.y2) {
            return;
        }

        WireElm newWire = new WireElm(x, y);
        newWire.drag(ce.x2, ce.y2);
        ce.drag(x, y);
        simulator().elmList.add(newWire);
        cirSim.needAnalyze();
    }

    static class FlipInfo {
        public int cx, cy, count;
    }

    FlipInfo prepareFlip() {
        pushUndo();
        circuitEditor().setMenuSelection();
        int minx = 30000, maxx = -30000;
        int miny = 30000, maxy = -30000;
        CircuitSimulator simulator = simulator();
        int count = simulator.countSelected();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.isSelected() || count == 0) {
                minx = Math.min(ce.x, Math.min(ce.x2, minx));
                maxx = Math.max(ce.x, Math.max(ce.x2, maxx));
                miny = Math.min(ce.y, Math.min(ce.y2, miny));
                maxy = Math.max(ce.y, Math.max(ce.y2, maxy));
            }
        }
        FlipInfo fi = new FlipInfo();
        fi.cx = (minx + maxx) / 2;
        fi.cy = (miny + maxy) / 2;
        fi.count = count;
        return fi;
    }

    void flipX() {
        FlipInfo fi = prepareFlip();
        int center2 = fi.cx * 2;
        CircuitSimulator simulator = simulator();
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0) {
                ce.flipX(center2, fi.count);
            }
        }
        cirSim.needAnalyze();
    }

    void flipY() {
        FlipInfo fi = prepareFlip();
        int center2 = fi.cy * 2;
        CircuitSimulator simulator = simulator();
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0) {
                ce.flipY(center2, fi.count);
            }
        }
        cirSim.needAnalyze();
    }

    void flipXY() {
        FlipInfo fi = prepareFlip();
        int xmy = snapGrid(fi.cx - fi.cy);
        CirSim.console("xmy " + xmy + " grid " + gridSize + " " + fi.cx + " " + fi.cy);
        CircuitSimulator simulator = simulator();
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0) {
                ce.flipXY(xmy, fi.count);
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
            CircuitElm ce = simulator.elmList.get(i);
            if (willDelete(ce)) {
                if (ce.isMouseElm()) {
                    setMouseElm(null);
                }
                ce.delete();
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

    boolean willDelete(CircuitElm ce) {
        return ce.isSelected() || ce.isMouseElm();
    }

    String copyOfSelectedElms() {
        String r = actionManager().dumpOptions();
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();
        r += simulator().dumpSelectedItems();
        return r;
    }

    void doCopy() {
        boolean clearSel = (menuElm != null && !menuElm.selected);
        setMenuSelection();
        cirSim.clipboardManager.doCopy();
        if (clearSel) {
            clearSelection();
        }
        cirSim.enablePaste();
    }

    void doDuplicate() {
        setMenuSelection();
        String s = copyOfSelectedElms();
        doPaste(s);
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
        Rectangle oldbb = getBoundingBoxOfCircuit();
        int oldsz = simulator().elmList.size();
        int flags = CircuitConst.RC_RETAIN;
        if (oldsz > 0) {
            flags |= CircuitConst.RC_NO_CENTER;
        }
        cirSim.circuitLoader.readCircuit(dump, flags);

        Rectangle newbb = selectNewItems(oldsz);

        if (oldbb != null && newbb != null) {
            int dx = 0, dy = 0;
            if (!oldbb.intersects(newbb)) {
                dx = snapGrid(oldbb.x - newbb.x);
                dy = snapGrid(oldbb.y - newbb.y);
            } else {
                int spacew = renderer().circuitArea.width - oldbb.width - newbb.width;
                int spaceh = renderer().circuitArea.height - oldbb.height - newbb.height;
                if (spacew > spaceh) {
                    dx = snapGrid(oldbb.x + oldbb.width - newbb.x + gridSize);
                } else {
                    dy = snapGrid(oldbb.y + oldbb.height - newbb.y + gridSize);
                }
            }

            if (mouseCursorX > 0 && renderer().circuitArea.contains(mouseCursorX, mouseCursorY)) {
                int gx = renderer().inverseTransformX(mouseCursorX);
                int gy = renderer().inverseTransformY(mouseCursorY);
                int mdx = snapGrid(gx - (newbb.x + newbb.width / 2));
                int mdy = snapGrid(gy - (newbb.y + newbb.height / 2));
                if (canMoveNewItems(oldsz, mdx, mdy)) {
                    dx = mdx;
                    dy = mdy;
                }
            }

            moveNewItems(oldsz, dx, dy);
        }
        cirSim.needAnalyze();
        undoManager().writeRecoveryToStorage();
        cirSim.setUnsavedChanges(true);
    }

    private Rectangle getBoundingBoxOfCircuit() {
        Rectangle oldbb = null;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            Rectangle bb = ce.getBoundingBox();
            if (oldbb != null) {
                oldbb = oldbb.union(bb);
            } else {
                oldbb = bb;
            }
        }
        return oldbb;
    }

    private Rectangle selectNewItems(int oldsz) {
        Rectangle newbb = null;
        CircuitSimulator simulator = simulator();
        for (int i = oldsz; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.setSelected(true);
            Rectangle bb = ce.getBoundingBox();
            if (newbb != null) {
                newbb = newbb.union(bb);
            } else {
                newbb = bb;
            }
        }
        return newbb;
    }

    private boolean canMoveNewItems(int oldsz, int dx, int dy) {
        CircuitSimulator simulator = simulator();
        for (int i = oldsz; i != simulator.elmList.size(); i++) {
            if (!simulator.elmList.get(i).allowMove(dx, dy)) {
                return false;
            }
        }
        return true;
    }

    private void moveNewItems(int oldsz, int dx, int dy) {
        CircuitSimulator simulator = simulator();
        for (int i = oldsz; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.move(dx, dy);
        }
    }

    void clearSelection() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.setSelected(false);
        }
        cirSim.enableDisableMenuItems();
    }

    void doSelectAll() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.setSelected(true);
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
