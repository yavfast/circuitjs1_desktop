package com.lushprojects.circuitjs1.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DocumentManager {

    private final CirSim cirSim;
    private final List<CircuitDocument> documents = new ArrayList<>();
    private CircuitDocument activeDocument;

    DocumentManager(CirSim cirSim) {
        this.cirSim = cirSim;
    }

    CircuitDocument createDocument() {
        CircuitDocument document = new CircuitDocument(cirSim);
        documents.add(document);
        return document;
    }

    void setActiveDocument(CircuitDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }

        if (document == activeDocument) {
            return;
        }

        if (!documents.contains(document)) {
            documents.add(document);
        }

        activeDocument = document;
        cirSim.bindDocument(document);
    }

    CircuitDocument getActiveDocument() {
        return activeDocument;
    }

    List<CircuitDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }
}
