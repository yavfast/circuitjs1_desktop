package com.lushprojects.circuitjs1.client;

import com.google.gwt.storage.client.Storage;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;

public class ScopeManager extends BaseCirSimDelegate {

    int scopeCount;
    Scope[] scopes;
    int[] scopeColCount;

    int scopeSelected = -1;
    int scopeMenuSelected = -1;
    int menuScope = -1;


    public ScopeManager(CirSim cirSim) {
        super(cirSim);

        scopes = new Scope[20];
        scopeColCount = new int[20];
        scopeCount = 0;
    }

    void updateScopes() {
        int i;
        for (i = 0; i < scopeCount; i++)
            scopes[i].setRect(scopes[i].rect);
    }

    int oldScopeCount = -1;

    boolean scopeMenuIsSelected(Scope s) {
        if (scopeMenuSelected < 0)
            return false;
        if (scopeMenuSelected < scopeCount)
            return scopes[scopeMenuSelected] == s;
        return simulator().getNthScopeElm(scopeMenuSelected - scopeCount).elmScope == s;
    }

    // we need to calculate wire currents for every iteration if someone is viewing a wire in the
    // scope.  Otherwise we can do it only once per frame.
    boolean canDelayWireProcessing() {
        for (int i = 0; i != scopeCount; i++)
            if (scopes[i].viewingWire())
                return false;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm elm = simulator.elmList.get(i);
            if (elm instanceof ScopeElm && ((ScopeElm) elm).elmScope.viewingWire())
                return false;
        }
        return true;
    }

    void dockScope(CircuitElm ce) {
        if (scopeCount == scopes.length)
            return;
        scopes[scopeCount] = ((ScopeElm) ce).elmScope;
        ((ScopeElm) ce).clearElmScope();
        scopes[scopeCount].position = scopeCount;
        scopeCount++;
    }

    void undockScope(ScopeElm newScope) {
        simulator().elmList.addElement(newScope);
        newScope.setElmScope(scopes[menuScope]);

        int i;
        // remove scope from list.  setupScopes() will fix the positions
        for (i = menuScope; i < scopeCount; i++)
            scopes[i] = scopes[i + 1];
        scopeCount--;
    }

    boolean canStackScope(int s) {
        if (scopeCount < 2)
            return false;
        if (s == 0)
            s = 1;
        if (scopes[s].position == scopes[s - 1].position)
            return false;
        return true;
    }

    boolean canCombineScope(int s) {
        return scopeCount >= 2;
    }

    boolean canUnstackScope(int s) {
        if (scopeCount < 2)
            return false;
        if (s == 0)
            s = 1;
        if (scopes[s].position != scopes[s - 1].position) {
            if (s + 1 < scopeCount && scopes[s + 1].position == scopes[s].position) // Allow you to unstack by selecting the top scope in the stack
                return true;
            else
                return false;
        }
        return true;
    }

    void stackScope(int s) {
        if (!canStackScope(s))
            return;
        if (s == 0) {
            s = 1;
        }
        scopes[s].position = scopes[s - 1].position;
        for (s++; s < scopeCount; s++)
            scopes[s].position--;

        cirSim.setUnsavedChanges(true);
    }

    void unstackScope(int s) {
        if (!canUnstackScope(s))
            return;
        if (s == 0) {
            s = 1;
        }
        if (scopes[s].position != scopes[s - 1].position) // Allow you to unstack by selecting the top scope in the stack
            s++;
        for (; s < scopeCount; s++)
            scopes[s].position++;

        cirSim.setUnsavedChanges(true);
    }

    void combineScope(int s) {
        if (!canCombineScope(s))
            return;
        if (s == 0) {
            s = 1;
        }
        scopes[s - 1].combine(scopes[s]);
        scopes[s].setElm(null);

        cirSim.setUnsavedChanges(true);
    }


    void stackAll() {
        int i;
        for (i = 0; i != scopeCount; i++) {
            scopes[i].position = 0;
            scopes[i].showMax = scopes[i].showMin = false;
        }

        cirSim.setUnsavedChanges(true);
    }

    void unstackAll() {
        int i;
        for (i = 0; i != scopeCount; i++) {
            scopes[i].position = i;
            scopes[i].showMax = true;
        }

        cirSim.setUnsavedChanges(true);
    }

    void combineAll() {
        int i;
        for (i = scopeCount - 2; i >= 0; i--) {
            scopes[i].combine(scopes[i + 1]);
            scopes[i + 1].setElm(null);
        }

        cirSim.setUnsavedChanges(true);
    }

    void separateAll() {
        int i;
        Scope newscopes[] = new Scope[20];
        int ct = 0;
        for (i = 0; i < scopeCount; i++)
            ct = scopes[i].separate(newscopes, ct);
        scopes = newscopes;
        scopeCount = ct;

        cirSim.setUnsavedChanges(true);
    }

    void addScope(CircuitElm ce) {
        int i;
        for (i = 0; i != scopeCount; i++)
            if (scopes[i].getElm() == null)
                break;
        if (i == scopeCount) {
            if (scopeCount == scopes.length)
                return;
            scopeCount++;
            scopes[i] = new Scope(cirSim);
            scopes[i].position = i;
            //handleResize();
        }
        scopes[i].setElm(ce);
        if (i > 0)
            scopes[i].speed = scopes[i - 1].speed;
    }

    void addToScope(int n, CircuitElm ce) {
        CircuitSimulator simulator = simulator();
        if (n < scopeCount + simulator.countScopeElms()) {
            if (n < scopeCount)
                scopes[n].addElm(ce);
            else
                simulator.getNthScopeElm(n - scopeCount).elmScope.addElm(ce);
        }
    }

    void setupScopes() {
        int i;
        Storage lstor = Storage.getLocalStorageIfSupported();
        // check scopes to make sure the elements still exist, and remove
        // unused scopes/columns
        int pos = -1;
        for (i = 0; i < scopeCount; i++) {
            if (scopes[i].needToRemove()) {
                int j;
                for (j = i; j != scopeCount; j++)
                    scopes[j] = scopes[j + 1];
                scopeCount--;
                i--;
                continue;
            }
            if (scopes[i].position > pos + 1)
                scopes[i].position = pos + 1;
            pos = scopes[i].position;
        }
        while (scopeCount > 0 && scopes[scopeCount - 1].getElm() == null)
            scopeCount--;
        CircuitRenderer renderer = renderer();
        int h = renderer.canvasHeight - renderer.circuitArea.height;
        pos = 0;
        for (i = 0; i != scopeCount; i++)
            scopeColCount[i] = 0;
        for (i = 0; i != scopeCount; i++) {
            pos = Math.max(scopes[i].position, pos);
            scopeColCount[scopes[i].position]++;
        }
        int colct = pos + 1;
        int iw = CirSim.INFO_WIDTH;
        if (colct <= 2)
            iw = iw * 3 / 2;
        int w = (renderer.canvasWidth - iw) / colct;
        if (cirSim.isSidePanelCheckboxChecked() && lstor.getItem("MOD_overlayingSidebar") == "true")
            w = (renderer.canvasWidth - iw - CirSim.VERTICAL_PANEL_WIDTH) / colct;
        int marg = 10;
        if (w < marg * 2)
            w = marg * 2;
        pos = -1;
        int colh = 0;
        int row = 0;
        int speed = 0;
        for (i = 0; i != scopeCount; i++) {
            Scope s = scopes[i];
            if (s.position > pos) {
                pos = s.position;
                colh = h / scopeColCount[pos];
                row = 0;
                speed = s.speed;
            }
            s.stackCount = scopeColCount[pos];
            if (s.speed != speed) {
                s.speed = speed;
                s.resetGraph();
            }
            Rectangle r = new Rectangle(pos * w, renderer.canvasHeight - h + colh * row, w - marg, colh);
            row++;
            if (!r.equals(s.rect))
                s.setRect(r);
        }
        if (oldScopeCount != scopeCount) {
            renderer.setCircuitArea();
            oldScopeCount = scopeCount;
        }
        cirSim.repaint();
    }


}
