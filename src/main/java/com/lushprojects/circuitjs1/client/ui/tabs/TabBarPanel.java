package com.lushprojects.circuitjs1.client.ui.tabs;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentManager;

import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.Command;

import java.util.HashMap;
import java.util.Map;

public class TabBarPanel extends Composite implements DocumentManager.DocumentManagerListener, TabWidget.TabListener {

    private final DocumentManager documentManager;
    private final FlowPanel mainPanel;
    private final FlowPanel tabsContainer;
    private final Label addTabButton;
    private final Label listTabsButton;
    private final Map<CircuitDocument, TabWidget> tabMap = new HashMap<>();

    public TabBarPanel(DocumentManager documentManager) {
        this.documentManager = documentManager;
        this.documentManager.addListener(this);

        mainPanel = new FlowPanel();
        mainPanel.setStyleName("tabBarPanel");

        addTabButton = new Label("+");
        addTabButton.setStyleName("addTabButton");
        addTabButton.setTitle("New Tab");
        addTabButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                // Create a new blank document
                CircuitDocument newDoc = documentManager.createDocument(); // This triggers onDocumentAdded
                documentManager.setActiveDocument(newDoc);
            }
        });
        mainPanel.add(addTabButton);

        listTabsButton = new Label("v");
        listTabsButton.setStyleName("addTabButton"); // Reuse style
        listTabsButton.setTitle("List Tabs");
        listTabsButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                showTabsListPopup(event.getClientX(), event.getClientY());
            }
        });
        mainPanel.add(listTabsButton);

        tabsContainer = new FlowPanel();
        tabsContainer.setStyleName("tabsContainer");
        mainPanel.add(tabsContainer);

        initWidget(mainPanel);
        
        // Initialize with existing documents
        for (CircuitDocument doc : documentManager.getDocuments()) {
            onDocumentAdded(doc);
        }
        
        // Set active tab
        if (documentManager.getActiveDocument() != null) {
            onActiveDocumentChanged(null, documentManager.getActiveDocument());
        }
    }

    private void showTabsListPopup(int x, int y) {
        PopupPanel popup = new PopupPanel(true);
        MenuBar menu = new MenuBar(true);
        
        for (CircuitDocument doc : documentManager.getDocuments()) {
            String title = documentManager.getTabTitle(doc);
            if (doc == documentManager.getActiveDocument()) {
                title = "<b>" + title + "</b>";
            }
            
            final CircuitDocument targetDoc = doc;
            MenuItem item = new MenuItem(com.google.gwt.safehtml.shared.SafeHtmlUtils.fromTrustedString(title), new Command() {
                @Override
                public void execute() {
                    documentManager.setActiveDocument(targetDoc);
                    popup.hide();
                }
            });
            menu.addItem(item);
        }
        
        popup.setWidget(menu);
        popup.setPopupPosition(x, y);
        popup.show();
    }

    @Override
    public void onDocumentAdded(CircuitDocument document) {
        TabWidget tab = new TabWidget(document, this);
        tab.setTitle(documentManager.getTabTitle(document));
        tabsContainer.add(tab);
        tabMap.put(document, tab);
    }

    @Override
    public void onDocumentRemoved(CircuitDocument document) {
        TabWidget tab = tabMap.remove(document);
        if (tab != null) {
            tabsContainer.remove(tab);
        }
    }

    @Override
    public void onActiveDocumentChanged(CircuitDocument oldDocument, CircuitDocument newDocument) {
        if (oldDocument != null) {
            TabWidget oldTab = tabMap.get(oldDocument);
            if (oldTab != null) {
                oldTab.setActive(false);
            }
        }
        if (newDocument != null) {
            TabWidget newTab = tabMap.get(newDocument);
            if (newTab != null) {
                newTab.setActive(true);
                // Ensure the active tab is visible (scroll into view if needed)
                newTab.getElement().scrollIntoView();
            }
        }
    }

    @Override
    public void onDocumentTitleChanged(CircuitDocument document) {
        TabWidget tab = tabMap.get(document);
        if (tab != null) {
            tab.setTitle(documentManager.getTabTitle(document));
        }
    }

    @Override
    public void onTabSelected(CircuitDocument document) {
        documentManager.setActiveDocument(document);
    }

    @Override
    public void onTabClosed(CircuitDocument document) {
        documentManager.closeDocument(document);
    }
}
