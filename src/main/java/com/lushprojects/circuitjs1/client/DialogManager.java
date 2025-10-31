package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.DialogBox;
import com.lushprojects.circuitjs1.client.dialog.AboutBox;
import com.lushprojects.circuitjs1.client.dialog.Dialog;
import com.lushprojects.circuitjs1.client.dialog.EditCompositeModelDialog;
import com.lushprojects.circuitjs1.client.dialog.EditDialog;
import com.lushprojects.circuitjs1.client.dialog.EditDiodeModelDialog;
import com.lushprojects.circuitjs1.client.dialog.EditOptions;
import com.lushprojects.circuitjs1.client.dialog.EditTransistorModelDialog;
import com.lushprojects.circuitjs1.client.dialog.Editable;
import com.lushprojects.circuitjs1.client.dialog.ExportAsImageDialog;
import com.lushprojects.circuitjs1.client.dialog.ExportAsTextDialog;
import com.lushprojects.circuitjs1.client.dialog.ExportAsUrlDialog;
import com.lushprojects.circuitjs1.client.dialog.HelpDialog;
import com.lushprojects.circuitjs1.client.dialog.ImportFromTextDialog;
import com.lushprojects.circuitjs1.client.dialog.LicenseDialog;
import com.lushprojects.circuitjs1.client.dialog.ModDialog;
import com.lushprojects.circuitjs1.client.dialog.ScopePropertiesDialog;
import com.lushprojects.circuitjs1.client.dialog.SearchDialog;
import com.lushprojects.circuitjs1.client.dialog.ShortcutsDialog;
import com.lushprojects.circuitjs1.client.dialog.SliderDialog;
import com.lushprojects.circuitjs1.client.dialog.SubcircuitDialog;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.DiodeElm;
import com.lushprojects.circuitjs1.client.element.TransistorElm;

public class DialogManager extends BaseCirSimDelegate {

    DialogBox activeDialog;

    protected DialogManager(BaseCirSim cirSim) {
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

    public void closeDialog() {
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
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new ModDialog(cirSim);
    }

    void showImportFromTextDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new ImportFromTextDialog(cirSim);
    }

    void showShortcutsDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new ShortcutsDialog(cirSim);
        activeDialog.show();
    }

    void showSubcircuitDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new SubcircuitDialog(cirSim);
        activeDialog.show();
    }

    void showSearchDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new SearchDialog(cirSim);
        activeDialog.show();
    }

    void showEditOptionsDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new EditDialog(new EditOptions(cirSim), cirSim);
        activeDialog.show();
    }

    public void showEditElementDialog(Editable editable) {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new EditDialog(editable, cirSim);
        activeDialog.show();
    }

    void showSliderDialog(CircuitElm ce) {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new SliderDialog(ce, cirSim);
        activeDialog.show();
    }

    void showExportAsUrlDialog(String dump) {
        activeDialog = new ExportAsUrlDialog(dump);
        activeDialog.show();
    }

    void showExportAsTextDialog(String dump) {
        CirSim cirSim = (CirSim) this.cirSim;
        activeDialog = new ExportAsTextDialog(cirSim, dump);
        activeDialog.show();
    }

    void showExportAsImageDialog(int type) {
        activeDialog = new ExportAsImageDialog(type);
        activeDialog.show();
    }

    public void showEditCompositeModelDialog(CustomCompositeModel model) {
        CirSim cirSim = (CirSim) this.cirSim;
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
        CirSim cirSim = (CirSim) this.cirSim;
        ScopePropertiesDialog properties = new ScopePropertiesDialog(cirSim, scope);
        activeDialog = properties;
        return properties;
    }

    public void showEditDiodeModelDialog(DiodeModel dm, DiodeElm de) {
        CirSim cirSim = (CirSim) this.cirSim;
        EditDialog editDialog = new EditDiodeModelDialog(dm, cirSim, de);
        editDialog.show();
    }

    public void showEditTransistorModelDialog(TransistorModel dm, TransistorElm te) {
        CirSim cirSim = (CirSim) this.cirSim;
        EditDialog editDialog = new EditTransistorModelDialog(dm, cirSim, te);
        editDialog.show();
    }
}
