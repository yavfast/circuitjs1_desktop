package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.HashMap;
import java.util.Map;

/**
 * [SP_AGA_01_12] Open marks: the per-document set of PostRefs (PinName form) the agent declared
 * intentionally unconnected. The set itself is a field of {@link CircuitDocument} (so undo
 * entries capture it without importing the agent package); this class holds the PostRef rules.
 * <ul>
 * <li>A PostRef is {@code <ElementId>.<PinName>} or {@code <ElementId>.#<index>} (0-based post
 *     index); marks are always stored in the PinName form.</li>
 * <li>Deleting an element removes its marks; replacing the content clears the set.</li>
 * </ul>
 */
final class OpenMarks {

    private OpenMarks() {
    }

    /** @return the ElementId part of a PostRef, or null when it has no {@code .} separator */
    static String elementOf(String postRef) {
        int dot = postRef == null ? -1 : postRef.indexOf('.');
        return dot <= 0 ? null : postRef.substring(0, dot);
    }

    /**
     * Resolves the pin part of a PostRef against a pin-name list.
     *
     * @return the PinName, or null when the pin is not one of {@code pins}
     */
    static String resolvePin(String postRef, String[] pins) {
        int dot = postRef.indexOf('.');
        String pin = postRef.substring(dot + 1);
        if (pin.startsWith("#")) {
            try {
                int index = Integer.parseInt(pin.substring(1));
                return index >= 0 && index < pins.length ? pins[index] : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (String p : pins) {
            if (p.equals(pin)) {
                return p;
            }
        }
        return null;
    }

    /** Removes every mark of element {@code id} (the element is being deleted). */
    static void removeElement(CircuitDocument doc, String id) {
        for (String mark : doc.getOpenMarks()) {
            if (id.equals(elementOf(mark))) {
                doc.setOpenMark(mark, false);
            }
        }
    }

    /**
     * Drops marks whose element or pin no longer exists (an element deleted by a user path, or a
     * pin removed by a configuration change), so a mark never attaches to a later element.
     */
    static void prune(CircuitDocument doc) {
        String[] marks = doc.getOpenMarks();
        if (marks.length == 0) {
            return;
        }
        Map<String, String[]> pins = new HashMap<>();
        for (CircuitElm elm : doc.simulator.elmList) {
            pins.put(elm.getElementId(), PinNames.of(elm));
        }
        for (String mark : marks) {
            String[] p = pins.get(elementOf(mark));
            if (p == null || resolvePin(mark, p) == null) {
                doc.setOpenMark(mark, false);
            }
        }
    }
}
