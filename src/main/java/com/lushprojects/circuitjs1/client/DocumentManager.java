package com.lushprojects.circuitjs1.client;

import java.util.Stack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.google.gwt.storage.client.Storage;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Timer;

public class DocumentManager {

    public interface DocumentManagerListener {
        void onDocumentAdded(CircuitDocument document);
        void onDocumentRemoved(CircuitDocument document);
        void onActiveDocumentChanged(CircuitDocument oldDocument, CircuitDocument newDocument);
        void onDocumentTitleChanged(CircuitDocument document);
    }

    private final BaseCirSim cirSim;
    private final List<CircuitDocument> documents = new ArrayList<>();
    private CircuitDocument activeDocument;
    private final Stack<String> closedTabsHistory = new Stack<>();
    private final List<DocumentManagerListener> listeners = new ArrayList<>();
    private final Timer saveTimer = new Timer() {
        @Override
        public void run() {
            saveSession();
        }
    };

    DocumentManager(BaseCirSim cirSim) {
        this.cirSim = cirSim;
    }

    public void addListener(DocumentManagerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DocumentManagerListener listener) {
        listeners.remove(listener);
    }

    public CircuitDocument createDocument() {
        CircuitDocument document = new CircuitDocument(cirSim);
        documents.add(document);
        notifyDocumentAdded(document);
        return document;
    }

    public void closeDocument(CircuitDocument document) {
        if (document == null || !documents.contains(document)) {
            return;
        }

        // Save to history
        String dump;
        if (document == activeDocument) {
             dump = cirSim.actionManager.dumpCircuit();
        } else {
             // Temporarily switch to document to dump it correctly
             CircuitDocument current = activeDocument;
             setActiveDocument(document);
             dump = cirSim.actionManager.dumpCircuit();
             setActiveDocument(current);
        }
        closedTabsHistory.push(dump);

        int index = documents.indexOf(document);
        documents.remove(document);
        notifyDocumentRemoved(document);

        if (document == activeDocument) {
            if (documents.isEmpty()) {
                // If no documents left, create a new blank one
                CircuitDocument newDoc = createDocument();
                setActiveDocument(newDoc);
            } else {
                // Select the nearest document
                int newIndex = Math.min(index, documents.size() - 1);
                setActiveDocument(documents.get(newIndex));
            }
        }
    }

    public void restoreLastClosedTab() {
        if (closedTabsHistory.isEmpty()) {
            return;
        }
        String dump = closedTabsHistory.pop();
        CircuitDocument newDoc = createDocument();
        setActiveDocument(newDoc);
        newDoc.circuitLoader.readCircuit(dump);
    }

    public boolean hasClosedTabs() {
        return !closedTabsHistory.isEmpty();
    }

    public void setInitialDocument(CircuitDocument doc) {
        this.activeDocument = doc;
        cirSim.bindDocument(doc);
    }

    public void setActiveDocument(CircuitDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }

        if (document == activeDocument) {
            return;
        }

        if (!documents.contains(document)) {
            documents.add(document);
            notifyDocumentAdded(document);
        }

        if (activeDocument != null) {
            activeDocument.saveUIState(cirSim.menuManager, (CirSim)cirSim);
        }

        CircuitDocument oldDocument = activeDocument;
        activeDocument = document;
        cirSim.bindDocument(document);
        
        document.restoreUIState(cirSim.menuManager, (CirSim)cirSim);
        
        notifyActiveDocumentChanged(oldDocument, document);
        
        // Update window title and UI
        if (cirSim instanceof CirSim) {
            ((CirSim)cirSim).setUnsavedChanges(document.circuitInfo.unsavedChanges);
            ((CirSim)cirSim).needAnalyze(); // Re-analyze/repaint
        }
    }

    public CircuitDocument getActiveDocument() {
        return activeDocument;
    }

    public List<CircuitDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public String getTabTitle(CircuitDocument doc) {
        String name = doc.circuitInfo.fileName;
        if (name == null || name.isEmpty()) {
            name = "Untitled";
        }
        if (doc.circuitInfo.unsavedChanges) {
            name += "*";
        }
        return name;
    }

    public void notifyTitleChanged(CircuitDocument doc) {
        notifyDocumentTitleChanged(doc);
        scheduleSave();
    }

    private void notifyDocumentAdded(CircuitDocument doc) {
        for (DocumentManagerListener listener : listeners) {
            listener.onDocumentAdded(doc);
        }
        scheduleSave();
    }

    private void notifyDocumentRemoved(CircuitDocument doc) {
        for (DocumentManagerListener listener : listeners) {
            listener.onDocumentRemoved(doc);
        }
        scheduleSave();
    }

    private void notifyActiveDocumentChanged(CircuitDocument oldDoc, CircuitDocument newDoc) {
        for (DocumentManagerListener listener : listeners) {
            listener.onActiveDocumentChanged(oldDoc, newDoc);
        }
        scheduleSave();
    }

    private void notifyDocumentTitleChanged(CircuitDocument doc) {
        for (DocumentManagerListener listener : listeners) {
            listener.onDocumentTitleChanged(doc);
        }
    }

    private void scheduleSave() {
        saveTimer.cancel();
        saveTimer.schedule(1000); // Debounce 1s
    }

    public void saveSession() {
        Storage storage = Storage.getLocalStorageIfSupported();
        if (storage == null) return;

        JSONArray docsArray = new JSONArray();
        for (int i = 0; i < documents.size(); i++) {
            CircuitDocument doc = documents.get(i);
            JSONObject docObj = new JSONObject();
            docObj.put("title", new JSONString(getTabTitle(doc)));
            
            String dump;
            if (doc == activeDocument) {
                dump = cirSim.actionManager.dumpCircuit();
            } else {
                // Temporarily switch to document to dump it
                // We avoid full UI update by not calling setActiveDocument if possible, 
                // but setActiveDocument does a lot of binding.
                // Let's just use setActiveDocument for now, but we need to be careful about recursion or events.
                // Actually, we can just swap the lists in CirSim without triggering full UI refresh if we are careful.
                // But safe way is:
                CircuitDocument current = activeDocument;
                activeDocument = doc;
                cirSim.bindDocument(doc);
                dump = cirSim.actionManager.dumpCircuit();
                activeDocument = current;
                cirSim.bindDocument(current);
            }
            
            docObj.put("data", new JSONString(dump));
            if (doc == activeDocument) {
                docObj.put("active", new JSONString("true"));
            }
            docsArray.set(i, docObj);
        }
        
        try {
            storage.setItem("circuitjs_tabs_session", docsArray.toString());
        } catch (Exception e) {
            CirSim.console("Failed to save session: " + e.getMessage());
        }
    }

    public boolean restoreSession() {
        Storage storage = Storage.getLocalStorageIfSupported();
        if (storage == null) return false;

        String sessionData = storage.getItem("circuitjs_tabs_session");
        if (sessionData == null || sessionData.isEmpty()) return false;

        try {
            JSONValue jsonVal = JSONParser.parseStrict(sessionData);
            JSONArray docsArray = jsonVal.isArray();
            if (docsArray == null || docsArray.size() == 0) return false;

            // Clear existing documents if any (though usually empty at start)
            // But we might have created one default document.
            // If we have only one empty document, we can reuse it or remove it.
            
            boolean first = true;
            CircuitDocument activeDocToSet = null;

            for (int i = 0; i < docsArray.size(); i++) {
                JSONObject docObj = docsArray.get(i).isObject();
                if (docObj == null) continue;

                String data = docObj.get("data").isString().stringValue();
                // String title = docObj.get("title").isString().stringValue(); // Title is derived from data/filename usually

                CircuitDocument doc;
                if (first && documents.size() == 1) {
                    // Reuse the initial empty document
                    doc = documents.get(0);
                    first = false;
                } else {
                    doc = createDocument();
                }
                
                // We must make the document active to load it correctly because CircuitLoader interacts with global UI
                setActiveDocument(doc);
                doc.circuitLoader.readCircuit(data);
                
                if (docObj.containsKey("active")) {
                    activeDocToSet = doc;
                }
            }

            if (activeDocToSet != null) {
                setActiveDocument(activeDocToSet);
            } else if (!documents.isEmpty()) {
                setActiveDocument(documents.get(0));
            }
            
            return true;

        } catch (Exception e) {
            CirSim.console("Failed to restore session: " + e.getMessage());
            return false;
        }
    }
}
