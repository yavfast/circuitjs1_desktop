package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.DialogBox;

public class DialogManager extends BaseCirSimDelegate {

    DialogBox activeDialog;

    protected DialogManager(CirSim cirSim) {
        super(cirSim);
    }

    boolean dialogIsShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }

    Dialog getShowingDialog() {
        if (activeDialog instanceof Dialog) {
            return (Dialog) activeDialog;
        }
        return null;
    }

    void closeDialog() {
        Dialog dialog = getShowingDialog();
        if (dialog != null && dialog.isShowing()) {
            dialog.closeDialog();
        }
        activeDialog = null;
    }

    void resetEditDialog() {
        DialogBox dialog = getShowingDialog();
        if (dialog instanceof EditDialog) {
            ((EditDialog)dialog).resetDialog();
        }
    }

    void showHelpDialog() {
        activeDialog = new HelpDialog();
    }

    void showLicenseDialog() {
        activeDialog = new LicenseDialog();
    }

    void showAboutBox() {
        new AboutBox(circuitjs1.versionString);
    }

    void showModDialog() {
        activeDialog = new ModDialog();
    }

    void showImportFromTextDialog() {
        activeDialog = new ImportFromTextDialog(cirSim);
    }

    void showShortcutsDialog() {
        activeDialog = new ShortcutsDialog(cirSim);
        activeDialog.show();
    }

    void showSubcircuitDialog() {
        activeDialog = new SubcircuitDialog(cirSim);
        activeDialog.show();
    }

    void showSearchDialog() {
        activeDialog = new SearchDialog(cirSim);
        activeDialog.show();
    }

    void showEditOptionsDialog() {
        activeDialog = new EditDialog(new EditOptions(cirSim), cirSim);
        activeDialog.show();
    }

    void showEditElementDialog(Editable editable) {
        activeDialog = new EditDialog(editable, cirSim);
        activeDialog.show();
    }

    void showSliderDialog(CircuitElm ce) {
        activeDialog = new SliderDialog(ce, cirSim);
        activeDialog.show();
    }

    void showExportAsUrlDialog(String dump) {
        activeDialog = new ExportAsUrlDialog(dump);
        activeDialog.show();
    }

    void showExportAsTextDialog(String dump) {
        activeDialog = new ExportAsTextDialog(cirSim, dump);
        activeDialog.show();
    }

    void showExportAsImageDialog(int type) {
        activeDialog = new ExportAsImageDialog(type);
        activeDialog.show();
    }

    void showEditCompositeModelDialog(CustomCompositeModel model) {
        EditCompositeModelDialog dlg = new EditCompositeModelDialog(cirSim);
        if (model != null) {
            dlg.setModel(model);
        } else if (!dlg.createModel()) {
            return;
        }
        dlg.createDialog();
        activeDialog = dlg;
        activeDialog.show();
    }

    ScopePropertiesDialog showScopePropertiesDialog(Scope scope) {
        ScopePropertiesDialog properties = new ScopePropertiesDialog(cirSim, scope);
        activeDialog = properties;
        return properties;
    }

    void showEditDiodeModelDialog(DiodeModel dm, DiodeElm de) {
        EditDialog editDialog = new EditDiodeModelDialog(dm, cirSim, de);
        editDialog.show();
    }

    void showEditTransistorModelDialog(TransistorModel dm, TransistorElm te) {
        EditDialog editDialog = new EditTransistorModelDialog(dm, cirSim, te);
        editDialog.show();
    }
}
