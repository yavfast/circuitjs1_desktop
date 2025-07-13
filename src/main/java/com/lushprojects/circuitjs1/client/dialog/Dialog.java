package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.user.client.ui.DialogBox;
import com.lushprojects.circuitjs1.client.OptionsManager;

public class Dialog extends DialogBox {

    boolean closeOnEnter;
    boolean positionRestored = false;

    public Dialog() {
        this(false, true);
    }

    public Dialog(boolean autoHide, boolean modal) {
        super(autoHide, modal);
        closeOnEnter = true;
        loadPosition();
    }

    @Override
    protected void endDragging(MouseUpEvent event) {
        super.endDragging(event);
        savePosition();
    }

    public void closeDialog() {
        hide();
    }

    @Override
    public void hide(boolean autoClosed) {
        savePosition();
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
                        setPopupPosition(left, top);
                        positionRestored = true;
//                        Log.log("Restore position: ", posStr);
                    }
                } catch (NumberFormatException e) {
                    // Ignore if the position is not in the correct format
                }
            }
        }
    }
}

