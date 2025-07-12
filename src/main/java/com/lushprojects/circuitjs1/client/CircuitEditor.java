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

    public static final int MODE_ADD_ELM = 0;
    public static final int MODE_DRAG_ALL = 1;
    public static final int MODE_DRAG_ROW = 2;
    public static final int MODE_DRAG_COLUMN = 3;
    public static final int MODE_DRAG_SELECTED = 4;
    public static final int MODE_DRAG_POST = 5;
    public static final int MODE_SELECT = 6;
    public static final int MODE_DRAG_SPLITTER = 7;

    static final int POST_GRAB_SQ = 25;
    static final int MIN_POST_GRAB_SIZE = 256;


    public int mouseMode = MODE_SELECT;
    int tempMouseMode = MODE_SELECT;

    String mouseModeStr = "Select";

    int dragGridX, dragGridY, dragScreenX, dragScreenY, initDragGridX, initDragGridY;
    boolean dragging;

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
        setMouseMode(MODE_ADD_ELM);
        if (s.length() > 0)
            mouseModeStr = s;
        if (s.compareTo("DragAll") == 0)
            setMouseMode(MODE_DRAG_ALL);
        else if (s.compareTo("DragRow") == 0)
            setMouseMode(MODE_DRAG_ROW);
        else if (s.compareTo("DragColumn") == 0)
            setMouseMode(MODE_DRAG_COLUMN);
        else if (s.compareTo("DragSelected") == 0)
            setMouseMode(MODE_DRAG_SELECTED);
        else if (s.compareTo("DragPost") == 0)
            setMouseMode(MODE_DRAG_POST);
        else if (s.compareTo("Select") == 0)
            setMouseMode(MODE_SELECT);

        tempMouseMode = mouseMode;
    }

    void setMouseMode(int mode) {
        mouseMode = mode;
        if (mode == MODE_ADD_ELM) {
            setCursorStyle("cursorCross");
        } else {
            setCursorStyle("cursorPointer");
        }
    }

    void setMenuSelection() {
        if (menuElm != null) {
            if (menuElm.selected) return;
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
        // ignore right mouse button with no modifiers (needed on PC)
        if (e.getNativeButton() == NativeEvent.BUTTON_RIGHT) {
            if (!(e.isMetaKeyDown() ||
                    e.isShiftKeyDown() ||
                    e.isControlKeyDown() ||
                    e.isAltKeyDown()))
                return;
        }

        if (tempMouseMode == MODE_DRAG_SPLITTER) {
            dragSplitter(e.getX(), e.getY());
            return;
        }
        int gx = renderer().inverseTransformX(e.getX());
        int gy = renderer().inverseTransformY(e.getY());
        if (!renderer().circuitArea.contains(e.getX(), e.getY()))
            return;
        boolean changed = false;
        if (dragElm != null)
            dragElm.drag(gx, gy);
        boolean success = true;
        switch (tempMouseMode) {
            case MODE_DRAG_ALL:
                dragAll(e.getX(), e.getY());
                break;
            case MODE_DRAG_ROW:
                dragRow(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case MODE_DRAG_COLUMN:
                dragColumn(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case MODE_DRAG_POST:
                if (mouseElm != null) {
                    dragPost(snapGrid(gx), snapGrid(gy), e.isShiftKeyDown());
                    changed = true;
                }
                break;
            case MODE_SELECT:
                if (mouseElm == null) {
                    selectArea(gx, gy, e.isShiftKeyDown());
                } else if (!menuManager().noEditCheckItem.getState()) {
                    // wait short delay before dragging.  This is to fix problem where switches were accidentally getting
                    // dragged when tapped on mobile devices
                    if (System.currentTimeMillis() - mouseDownTime < 150)
                        return;

                    tempMouseMode = MODE_DRAG_SELECTED;
                    changed = success = dragSelected(gx, gy);
                }
                break;
            case MODE_DRAG_SELECTED:
                changed = success = dragSelected(gx, gy);
                break;

        }
        dragging = true;
        if (success) {
            dragScreenX = e.getX();
            dragScreenY = e.getY();
            //	    console("setting dragGridx in mousedragged");
            dragGridX = renderer().inverseTransformX(dragScreenX);
            dragGridY = renderer().inverseTransformY(dragScreenY);
            if (!(tempMouseMode == MODE_DRAG_SELECTED && onlyGraphicsElmsSelected())) {
                dragGridX = snapGrid(dragGridX);
                dragGridY = snapGrid(dragGridY);
            }
        }
        if (changed) {
            cirSim.setUnsavedChanges(true);
        }

        renderer().repaint();
    }

    void dragSplitter(int x, int y) {
        double h = (double) renderer().canvasHeight;
        if (h < 1)
            h = 1;
        double scopeHeightFraction = 1.0 - (((double) y) / h);
        if (scopeHeightFraction < 0.1)
            scopeHeightFraction = 0.1;
        if (scopeHeightFraction > 0.9)
            scopeHeightFraction = 0.9;
        renderer().scopeHeightFraction = scopeHeightFraction;
        renderer().setCircuitArea();
        cirSim.repaint();
    }

    void dragAll(int x, int y) {
        int dx = x - dragScreenX;
        int dy = y - dragScreenY;
        if (dx == 0 && dy == 0)
            return;
        renderer().transform[4] += dx;
        renderer().transform[5] += dy;
        dragScreenX = x;
        dragScreenY = y;
    }

    void dragRow(int x, int y) {
        int dy = y - dragGridY;
        if (dy == 0)
            return;
        int i;
        CircuitSimulator simulator = simulator();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.y == dragGridY)
                ce.movePoint(0, 0, dy);
            if (ce.y2 == dragGridY)
                ce.movePoint(1, 0, dy);
        }
        removeZeroLengthElements();
    }

    void dragColumn(int x, int y) {
        int dx = x - dragGridX;
        if (dx == 0)
            return;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.x == dragGridX)
                ce.movePoint(0, dx, 0);
            if (ce.x2 == dragGridX)
                ce.movePoint(1, dx, 0);
        }
        removeZeroLengthElements();
    }

    boolean dragSelected(int x, int y) {
        boolean me = false;
        int i;
        if (mouseElm != null && !mouseElm.isSelected())
            mouseElm.setSelected(me = true);

        if (!onlyGraphicsElmsSelected()) {
            //	    console("Snapping x and y");
            x = snapGrid(x);
            y = snapGrid(y);
        }

        int dx = x - dragGridX;
        //  	console("dx="+dx+"dragGridx="+dragGridX);
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0) {
            // don't leave mouseElm selected if we selected it above
            if (me)
                mouseElm.setSelected(false);
            return false;
        }
        boolean allowed = true;

        // check if moves are allowed
        CircuitSimulator simulator = simulator();
        for (i = 0; allowed && i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.isSelected() && !ce.allowMove(dx, dy))
                allowed = false;
        }

        if (allowed) {
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                if (ce.isSelected())
                    ce.move(dx, dy);
            }
            cirSim.needAnalyze();
        }

        // don't leave mouseElm selected if we selected it above
        if (me)
            mouseElm.setSelected(false);

        return allowed;
    }

    void dragPost(int x, int y, boolean all) {
        if (draggingPost == -1) {
            draggingPost =
                    (Graphics.distanceSq(mouseElm.x, mouseElm.y, x, y) >
                            Graphics.distanceSq(mouseElm.x2, mouseElm.y2, x, y)) ? 1 : 0;
        }
        int dx = x - dragGridX;
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0)
            return;

        if (all) {
            // go through all elms
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm e = simulator.elmList.get(i);

                // which post do we move?
                int p = 0;
                if (e.x == dragGridX && e.y == dragGridY)
                    p = 0;
                else if (e.x2 == dragGridX && e.y2 == dragGridY)
                    p = 1;
                else
                    continue;
                e.movePoint(p, dx, dy);
            }
        } else
            mouseElm.movePoint(draggingPost, dx, dy);
        cirSim.needAnalyze();
    }

    void selectArea(int x, int y, boolean add) {
        int x1 = Math.min(x, initDragGridX);
        int x2 = Math.max(x, initDragGridX);
        int y1 = Math.min(y, initDragGridY);
        int y2 = Math.max(y, initDragGridY);
        selectedArea = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        int i;
        CircuitSimulator simulator = simulator();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.selectRect(selectedArea, add);
        }
        cirSim.enableDisableMenuItems();
    }

    void setMouseElm(CircuitElm ce) {
        if (ce != mouseElm) {
            if (mouseElm != null)
                mouseElm.setMouseElm(false);
            if (ce != null)
                ce.setMouseElm(true);
            mouseElm = ce;

            cirSim.adjustableManager.setMouseElm(ce);
        }
        // Ensure the display is updated even when simulation is stopped
        renderer().repaint();
    }

    void removeZeroLengthElements() {
        boolean changed = false;
        CircuitSimulator simulator = simulator();
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.x == ce.x2 && ce.y == ce.y2) {
                simulator.elmList.remove(i);
                ce.delete();
                changed = true;
            }
        }
        cirSim.needAnalyze();
    }

    boolean mouseIsOverSplitter(int x, int y) {
        boolean isOverSplitter;
        if (scopeManager().scopeCount == 0)
            return false;
        isOverSplitter = ((x >= 0) && (x < renderer().circuitArea.width) &&
                (y >= renderer().circuitArea.height - 5) && (y < renderer().circuitArea.height));
        if (isOverSplitter != mouseWasOverSplitter) {
            if (isOverSplitter)
                setCursorStyle("cursorSplitter");
            else
                setMouseMode(mouseMode);
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

    // need to break this out into a separate routine to handle selection,
    // since we don't get mouse move events on mobile
    public void mouseSelect(MouseEvent<?> e) {
        //	The following is in the original, but seems not to work/be needed for GWT
        //    	if (e.getNativeButton()==NativeEvent.BUTTON_LEFT)
        //	    return;
        CircuitElm newMouseElm = null;
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();
        int sx = e.getX();
        int sy = e.getY();
        int gx = renderer().inverseTransformX(sx);
        int gy = renderer().inverseTransformY(sy);
        // 	console("Settingd draggridx in mouseEvent");
        dragGridX = snapGrid(gx);
        dragGridY = snapGrid(gy);
        dragScreenX = sx;
        dragScreenY = sy;
        draggingPost = -1;
        //	CircuitElm origMouse = mouseElm;

        mousePost = -1;
        plotXElm = plotYElm = null;

        if (mouseIsOverSplitter(sx, sy)) {
            setMouseElm(null);
            return;
        }

        if (renderer().circuitArea.contains(sx, sy)) {
            // First check if we're near a handle of the current mouse element
            if (mouseElm != null && (mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0)) {
                newMouseElm = mouseElm;
            } else {
                // Look for the closest element, prioritizing smaller elements
                int bestDist = 100000000;
                CircuitElm bestElm = null;

                CircuitSimulator simulator = simulator();
                for (int i = 0; i != simulator.elmList.size(); i++) {
                    CircuitElm ce = simulator.elmList.get(i);
                    if (ce.boundingBox.contains(gx, gy)) {
                        int dist = ce.getMouseDistance(gx, gy);
                        if (dist >= 0) {
                            // Prefer smaller elements by adding a size penalty
                            int sizePenalty = (ce.boundingBox.width + ce.boundingBox.height) / 4;
                            int totalDist = dist + sizePenalty;

                            if (totalDist < bestDist) {
                                bestDist = totalDist;
                                bestElm = ce;
                            }
                        }
                    }
                }
                newMouseElm = bestElm;
            }
        }

        scopeManager().scopeSelected = -1;
        if (newMouseElm == null) {
            // Check scopes
            ScopeManager scopeManager = scopeManager();
            for (int i = 0; i != scopeManager.scopeCount; i++) {
                Scope s = scopeManager.scopes[i];
                if (s.rect.contains(sx, sy)) {
                    newMouseElm = s.getElm();
                    if (s.plotXY) {
                        plotXElm = s.getXElm();
                        plotYElm = s.getYElm();
                    }
                    scopeManager.scopeSelected = i;
                    break;
                }
            }

            // If no element found in bounding boxes, check for nearby posts
            if (newMouseElm == null) {
                int bestPostDist = 26; // Maximum distance for post detection
                CircuitElm bestPostElm = null;
                int bestPost = -1;

                CircuitSimulator simulator = simulator();
                for (int i = 0; i != simulator.elmList.size(); i++) {
                    CircuitElm ce = simulator.elmList.get(i);

                    // Check for drag post mode
                    if (mouseMode == MODE_DRAG_POST) {
                        if (ce.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, 0) > 0) {
                            newMouseElm = ce;
                            break;
                        }
                    }

                    // Check individual posts
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
                    newMouseElm = bestPostElm;
                    mousePost = bestPost;
                }
            }
        } else {
            mousePost = -1;
            // look for post close to the mouse pointer on the selected element
            int bestPostDist = 26;
            int bestPost = -1;

            for (int i = 0; i != newMouseElm.getPostCount(); i++) {
                Point pt = newMouseElm.getPost(i);
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

        setMouseElm(newMouseElm);
    }

    void twoFingerTouch(int x, int y) {
        tempMouseMode = MODE_DRAG_ALL;
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

        // make sure canvas has focus, not stop button or something else, so all shortcuts work
        renderer().getCanvas().setFocus(true);

        simulator().stopElm = null; // if stopped, allow user to select other elements to fix circuit
        menuX = menuClientX = e.getX();
        menuY = menuClientY = e.getY();
        mouseDownTime = System.currentTimeMillis();

        // maybe someone did copy in another window?  should really do this when
        // window receives focus
        cirSim.enablePaste();

        if (e.getNativeButton() != NativeEvent.BUTTON_LEFT && e.getNativeButton() != NativeEvent.BUTTON_MIDDLE)
            return;

        // set mouseElm in case we are on mobile
        mouseSelect(e);

        mouseDragging = true;
        didSwitch = false;

        if (mouseWasOverSplitter) {
            tempMouseMode = MODE_DRAG_SPLITTER;
            return;
        }
        if (e.getNativeButton() == NativeEvent.BUTTON_LEFT) {
//	    // left mouse
            tempMouseMode = mouseMode;
            if (e.isAltKeyDown() && e.isMetaKeyDown())
                tempMouseMode = MODE_DRAG_COLUMN;
            else if (e.isAltKeyDown() && e.isShiftKeyDown())
                tempMouseMode = MODE_DRAG_ROW;
            else if (e.isShiftKeyDown())
                tempMouseMode = MODE_SELECT;
            else if (e.isAltKeyDown())
                tempMouseMode = MODE_DRAG_ALL;
            else if (e.isControlKeyDown() || e.isMetaKeyDown())
                tempMouseMode = MODE_DRAG_POST;
        } else
            tempMouseMode = MODE_DRAG_ALL;


        if (menuManager().noEditCheckItem.getState())
            tempMouseMode = MODE_SELECT;

        ScopeManager scopeManager = scopeManager();
        if (!(cirSim.dialogIsShowing()) && ((scopeManager.scopeSelected != -1 && scopeManager.scopes[scopeManager.scopeSelected].cursorInSettingsWheel()) ||
                (scopeManager.scopeSelected == -1 && mouseElm instanceof ScopeElm && ((ScopeElm) mouseElm).elmScope.cursorInSettingsWheel()))) {
            if (menuManager().noEditCheckItem.getState())
                return;
            Scope s;
            if (scopeManager.scopeSelected != -1)
                s = scopeManager.scopes[scopeManager.scopeSelected];
            else
                s = ((ScopeElm) mouseElm).elmScope;
            s.properties();
            clearSelection();
            mouseDragging = false;
            return;
        }

        int gx = renderer().inverseTransformX(e.getX());
        int gy = renderer().inverseTransformY(e.getY());
        if (doSwitch(gx, gy)) {
            // do this BEFORE we change the mouse mode to MODE_DRAG_POST!  Or else logic inputs
            // will add dots to the whole circuit when we click on them!
            didSwitch = true;
            return;
        }

        // IES - Grab resize handles in select mode if they are far enough apart and you are on top of them
        if (tempMouseMode == MODE_SELECT && mouseElm != null && !menuManager().noEditCheckItem.getState() &&
                mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0 &&
                !anySelectedButMouse())
            tempMouseMode = MODE_DRAG_POST;

        if (tempMouseMode != MODE_SELECT && tempMouseMode != MODE_DRAG_SELECTED)
            clearSelection();

        pushUndo();
        initDragGridX = gx;
        initDragGridY = gy;
        dragging = true;
        if (tempMouseMode != MODE_ADD_ELM)
            return;
//
        int x0 = snapGrid(gx);
        int y0 = snapGrid(gy);
        if (!renderer().circuitArea.contains(e.getX(), e.getY()))
            return;

        try {
            dragElm = CircuitElmCreator.constructElement(mouseModeStr, x0, y0);
        } catch (Exception ex) {
            CirSim.debugger();
        }
    }

    public void onMouseUp(MouseUpEvent e) {
        e.preventDefault();
        mouseDragging = false;

        // click to clear selection
        if (tempMouseMode == MODE_SELECT && selectedArea == null)
            clearSelection();

        // cmd-click = split wire
        if (tempMouseMode == MODE_DRAG_POST && draggingPost == -1)
            doSplit(mouseElm);

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
            // if the element is zero size then don't create it
            // IES - and disable any previous selection
            if (dragElm.creationFailed()) {
                dragElm.delete();
                if (mouseMode == MODE_SELECT || mouseMode == MODE_DRAG_SELECTED)
                    clearSelection();
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
        if (dragElm != null)
            dragElm.delete();
        dragElm = null;
        renderer().repaint();
    }

    public void onMouseWheel(MouseWheelEvent e) {
        e.preventDefault();

        // Update mouse cursor position for proper zoom centering
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();

        // once we start zooming, don't allow other uses of mouse wheel for a while
        // so we don't accidentally edit a resistor value while zooming
        boolean zoomOnly = System.currentTimeMillis() < zoomTime + 1000;

        if (menuManager().noEditCheckItem.getState() || !menuManager().mouseWheelEditCheckItem.getState())
            zoomOnly = true;

        if (!zoomOnly)
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), e.getDeltaY());

        if (mouseElm instanceof MouseWheelHandler && !zoomOnly)
            ((MouseWheelHandler) mouseElm).onMouseWheel(e);
        else if (scopeManager().scopeSelected != -1 && !zoomOnly)
            scopeManager().scopes[scopeManager().scopeSelected].onMouseWheel(e);
        else if (!cirSim.dialogIsShowing()) {
            // Ensure we're zooming around the correct mouse position
            double zoomDelta = -e.getDeltaY() * wheelSensitivity;

            // Clamp zoom delta to prevent too aggressive zooming
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
        if ((e.getNativeButton() == NativeEvent.BUTTON_MIDDLE))
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), 0);
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
        if (mouseElm != null && !(mouseElm instanceof SwitchElm) && !menuManager().noEditCheckItem.getState())
            doEditElementOptions(mouseElm);
    }

    ScrollValuePopup scrollValuePopup;

    void scrollValues(int x, int y, int deltay) {
        if (mouseElm != null && !cirSim.dialogIsShowing() && scopeManager().scopeSelected == -1)
            if (mouseElm instanceof ResistorElm || mouseElm instanceof CapacitorElm || mouseElm instanceof InductorElm) {
                scrollValuePopup = new ScrollValuePopup(x, y, deltay, mouseElm, cirSim);
                cirSim.setUnsavedChanges(true);
            }
    }

    public int snapGrid(int x) {
        return (x + gridRound) & gridMask;
    }

    void setGrid() {
        gridSize = (menuManager().smallGridCheckItem.getState()) ? 8 : 16;
        gridMask = ~(gridSize - 1);
        gridRound = gridSize / 2 - 1;
    }

    boolean doSwitch(int x, int y) {
        if (mouseElm == null || !(mouseElm instanceof SwitchElm))
            return false;
        SwitchElm se = (SwitchElm) mouseElm;
        if (!se.getSwitchRect().contains(x, y))
            return false;
        se.toggle();
        if (se.momentary)
            heldSwitchElm = se;
        if (!(se instanceof LogicInputElm))
            cirSim.needAnalyze();
        cirSim.setUnsavedChanges(true);
        return true;
    }

    boolean onlyGraphicsElmsSelected() {
        if (mouseElm != null && !(mouseElm instanceof GraphicElm))
            return false;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.isSelected() && !(ce instanceof GraphicElm))
                return false;
        }
        return true;
    }

    void doFlip() {
        circuitEditor().menuElm.flipPosts();
        cirSim.needAnalyze();
    }

    void doSplit(CircuitElm ce) {
        int x = snapGrid(renderer().inverseTransformX(menuX));
        int y = snapGrid(renderer().inverseTransformY(menuY));
        if (ce == null || !(ce instanceof WireElm))
            return;
        if (ce.x == ce.x2)
            x = ce.x;
        else
            y = ce.y;

        // don't create zero-length wire
        if (x == ce.x && y == ce.y || x == ce.x2 && y == ce.y2)
            return;

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
            if (ce.isSelected() || fi.count == 0)
                ce.flipX(center2, fi.count);
        }
        cirSim.needAnalyze();
    }

    void flipY() {
        FlipInfo fi = prepareFlip();
        int center2 = fi.cy * 2;
        CircuitSimulator simulator = simulator();
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0)
                ce.flipY(center2, fi.count);
        }
        cirSim.needAnalyze();
    }

    void flipXY() {
        FlipInfo fi = prepareFlip();
        int xmy = snapGrid(fi.cx - fi.cy);
        CirSim.console("xmy " + xmy + " grid " + gridSize + " " + fi.cx + " " + fi.cy);
        CircuitSimulator simulator = simulator();
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0)
                ce.flipXY(xmy, fi.count);
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
        int i;
        pushUndo();
        circuitEditor().setMenuSelection();

        cirSim.clipboardManager.doCut();

        doDelete(true);
        cirSim.enablePaste();
    }

    void doDelete(boolean pushUndoFlag) {
        int i;
        if (pushUndoFlag)
            pushUndo();
        boolean hasDeleted = false;

        CircuitSimulator simulator = simulator();
        for (i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = simulator.elmList.get(i);
            if (willDelete(ce)) {
                if (ce.isMouseElm())
                    setMouseElm(null);
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
        // Is this element in the list to be deleted.
        // This changes the logic from the previous version which would initially only
        // delete selected elements (which could include the mouseElm) and then delete the
        // mouseElm if there were no selected elements. Not really sure this added anything useful
        // to the user experience.
        //
        // BTW, the old logic could also leave mouseElm pointing to a deleted element.
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
        // clear selection when we're done if we're copying a single element using the context menu
        boolean clearSel = (menuElm != null && !menuElm.selected);

        setMenuSelection();

        cirSim.clipboardManager.doCopy();

        if (clearSel)
            clearSelection();

        cirSim.enablePaste();
    }

    void doDuplicate() {
        String s;
        setMenuSelection();
        s = copyOfSelectedElms();
        doPaste(s);
    }

    void doPaste(String dump) {
        if (dump == null || dump.isEmpty()) {
            // Try to get from system clipboard first, then internal
            dump = cirSim.clipboardManager.getClipboard();
            if (dump == null || dump.isEmpty()) {
                return;
            }
        }


        pushUndo();
        clearSelection();
        int i;
        Rectangle oldbb = null;

        // get old bounding box
        CircuitSimulator simulator = simulator();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            Rectangle bb = ce.getBoundingBox();
            if (oldbb != null)
                oldbb = oldbb.union(bb);
            else
                oldbb = bb;
        }

        // add new items
        int oldsz = simulator.elmList.size();
        int flags = CircuitConst.RC_RETAIN;

        // don't recenter circuit if we're going to paste in place because that will change the transform
//	if (mouseCursorX > 0 && circuitArea.contains(mouseCursorX, mouseCursorY))

        // in fact, don't ever recenter circuit, unless old circuit was empty
        if (oldsz > 0)
            flags |= CircuitConst.RC_NO_CENTER;

        cirSim.circuitLoader.readCircuit(dump, flags);

        // select new items and get their bounding box
        Rectangle newbb = null;
        for (i = oldsz; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.setSelected(true);
            Rectangle bb = ce.getBoundingBox();
            if (newbb != null)
                newbb = newbb.union(bb);
            else
                newbb = bb;
        }

        if (oldbb != null && newbb != null /*&& oldbb.intersects(newbb)*/) {
            // find a place on the edge for new items
            int dx = 0, dy = 0;
            int spacew = renderer().circuitArea.width - oldbb.width - newbb.width;
            int spaceh = renderer().circuitArea.height - oldbb.height - newbb.height;

            if (!oldbb.intersects(newbb)) {
                // old coordinates may be really far away so move them to same origin as current circuit
                dx = snapGrid(oldbb.x - newbb.x);
                dy = snapGrid(oldbb.y - newbb.y);
            }

            if (spacew > spaceh) {
                dx = snapGrid(oldbb.x + oldbb.width - newbb.x + gridSize);
            } else {
                dy = snapGrid(oldbb.y + oldbb.height - newbb.y + gridSize);
            }

            // move new items near the mouse if possible
            if (mouseCursorX > 0 && renderer().circuitArea.contains(mouseCursorX, mouseCursorY)) {
                int gx = renderer().inverseTransformX(mouseCursorX);
                int gy = renderer().inverseTransformY(mouseCursorY);
                int mdx = snapGrid(gx - (newbb.x + newbb.width / 2));
                int mdy = snapGrid(gy - (newbb.y + newbb.height / 2));
                for (i = oldsz; i != simulator.elmList.size(); i++) {
                    if (!simulator.elmList.get(i).allowMove(mdx, mdy))
                        break;
                }
                if (i == simulator.elmList.size()) {
                    dx = mdx;
                    dy = mdy;
                }
            }

            // move the new items
            for (i = oldsz; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                ce.move(dx, dy);
            }

            // center circuit
            //	handleResize();
        }
        cirSim.needAnalyze();
        undoManager().writeRecoveryToStorage();
        cirSim.setUnsavedChanges(true);
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
        for (int i = 0; i != simulator.elmList.size(); i++)
            if (simulator.elmList.get(i) != mouseElm && simulator.elmList.get(i).selected)
                return true;
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
