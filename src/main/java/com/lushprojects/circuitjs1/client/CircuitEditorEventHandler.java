package com.lushprojects.circuitjs1.client;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.ContextMenuHandler;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.event.dom.client.DoubleClickHandler;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;

/**
 * Event handler that delegates all mouse/keyboard events to the active document's CircuitEditor.
 * This allows proper multi-tab support where each tab has its own CircuitEditor,
 * but only one set of event handlers is registered on the Canvas.
 */
public class CircuitEditorEventHandler implements MouseDownHandler, MouseMoveHandler, MouseUpHandler,
        ClickHandler, DoubleClickHandler, ContextMenuHandler, MouseOutHandler, MouseWheelHandler {

    private final BaseCirSim cirSim;

    public CircuitEditorEventHandler(BaseCirSim cirSim) {
        this.cirSim = cirSim;
    }

    private CircuitEditor getActiveEditor() {
        return cirSim.getActiveDocument().circuitEditor;
    }

    @Override
    public void onMouseDown(MouseDownEvent event) {
        getActiveEditor().onMouseDown(event);
    }

    @Override
    public void onMouseMove(MouseMoveEvent event) {
        getActiveEditor().onMouseMove(event);
    }

    @Override
    public void onMouseUp(MouseUpEvent event) {
        getActiveEditor().onMouseUp(event);
    }

    @Override
    public void onClick(ClickEvent event) {
        getActiveEditor().onClick(event);
    }

    @Override
    public void onDoubleClick(DoubleClickEvent event) {
        getActiveEditor().onDoubleClick(event);
    }

    @Override
    public void onContextMenu(ContextMenuEvent event) {
        getActiveEditor().onContextMenu(event);
    }

    @Override
    public void onMouseOut(MouseOutEvent event) {
        getActiveEditor().onMouseOut(event);
    }

    @Override
    public void onMouseWheel(MouseWheelEvent event) {
        getActiveEditor().onMouseWheel(event);
    }
}
