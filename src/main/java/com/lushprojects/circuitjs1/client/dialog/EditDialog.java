/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.dialog;


import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.i18n.client.NumberFormat;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.Adjustable;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.util.Locale;

public class EditDialog extends Dialog {
    Editable elm;
    CirSim cframe;
    Button applyButton, okButton, cancelButton;
    EditInfo einfos[];
    int einfocount;
    final int barmax = 1000;
    VerticalPanel mainPanel;
    HorizontalPanel bottomButtonPanel;
    static NumberFormat noCommaFormat = NumberFormat.getFormat("####.##########");

    public EditDialog(Editable ce, CirSim f) {
//		super(f, "Edit Component", false);
        super(); // Do we need this?
        setText(Locale.LS("Edit Component"));
        cframe = f;
        elm = ce;
//		setLayout(new EditDialogLayout());
        mainPanel = new VerticalPanel();
        setWidget(mainPanel);
        einfos = new EditInfo[10];
//		noCommaFormat = DecimalFormat.getInstance();
//		noCommaFormat.setMaximumFractionDigits(10);
//		noCommaFormat.setGroupingUsed(false);
        bottomButtonPanel = new HorizontalPanel();
        bottomButtonPanel.setWidth("100%");
        bottomButtonPanel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_LEFT);
        bottomButtonPanel.setStyleName("topSpace");
        mainPanel.add(bottomButtonPanel);
        applyButton = new Button(Locale.LS("Apply"));
        bottomButtonPanel.add(applyButton);
        applyButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                apply();
            }
        });
        bottomButtonPanel.add(okButton = new Button(Locale.LS("OK")));
        okButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                apply();
                closeDialog();
            }
        });
        bottomButtonPanel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
        bottomButtonPanel.add(cancelButton = new Button(Locale.LS("Cancel")));
        cancelButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                closeDialog();
            }
        });
        buildDialog();
        this.center();
    }

    @Override
    public void closeDialog() {
        super.closeDialog();
        resetDialog();
    }

    void buildDialog() {
        int i;
        HorizontalPanel hp = new HorizontalPanel();
        VerticalPanel vp = new VerticalPanel();
        mainPanel.insert(hp, mainPanel.getWidgetIndex(bottomButtonPanel));
        hp.add(vp);
        for (i = 0; ; i++) {
            Label l = null;
            einfos[i] = elm.getEditInfo(i);
            if (einfos[i] == null)
                break;
            final EditInfo ei = einfos[i];
            String name = Locale.LS(ei.name);
            if (ei.name.startsWith("<"))
                vp.add(l = new HTML(name));
            else
                vp.add(l = new Label(name));
            if (i != 0 && l != null)
                l.setStyleName("topSpace");
            if (ei.choice != null) {
                vp.add(ei.choice);
                ei.choice.addChangeHandler(new ChangeHandler() {
                    public void onChange(ChangeEvent e) {
                        itemStateChanged(e);
                    }
                });
            } else if (ei.checkbox != null) {
                vp.add(ei.checkbox);
                ei.checkbox.addValueChangeHandler(new ValueChangeHandler<Boolean>() {
                    public void onValueChange(ValueChangeEvent<Boolean> e) {
                        itemStateChanged(e);
                    }
                });
            } else if (ei.button != null) {
                vp.add(ei.button);
                if (ei.loadFile != null) {
                    //Open file dialog
                    vp.add(ei.loadFile);
                    ei.button.addClickHandler(new ClickHandler() {
                        public void onClick(ClickEvent event) {
                            ei.loadFile.open();
                        }
                    });
                } else {
                    //Normal button press
                    ei.button.addClickHandler(new ClickHandler() {
                        public void onClick(ClickEvent event) {
                            itemStateChanged(event);
                        }
                    });
                }
            } else if (ei.textArea != null) {
                vp.add(ei.textArea);
                closeOnEnter = false;
            } else if (ei.widget != null) {
                vp.add(ei.widget);
            } else {
                vp.add(ei.textf = new TextBox());
                if (ei.text != null) {
                    ei.textf.setText(ei.text);
                    ei.textf.setVisibleLength(50);
                }
                if (ei.text == null) {
                    ei.textf.setText(unitString(ei));
                }
                if (ei.isColor) {
                    ei.textf.getElement().setAttribute("type", "color");
                    ei.textf.getElement().setAttribute("style", "width:178px;padding:0");
                }
            }
            if (vp.getWidgetCount() > 15) {
                // start a new column
                vp = new VerticalPanel();
                hp.add(vp);
                vp.getElement().getStyle().setPaddingLeft(10, Unit.PX);
            }
        }
        einfocount = i;
    }

    static final double ROOT2 = 1.41421356237309504880;

    double diffFromInteger(double x) {
        return Math.abs(x - Math.round(x));
    }

    String unitString(EditInfo ei) {
        // for voltage elements, express values in rms if that would be shorter
        if (elm != null && elm instanceof VoltageElm &&
                Math.abs(ei.value) > 1e-4 &&
                diffFromInteger(ei.value * 1e4) > diffFromInteger(ei.value * 1e4 / ROOT2))
            return unitString(ei, ei.value / ROOT2) + "rms";
        return unitString(ei, ei.value);
    }

    static String unitString(EditInfo ei, double v) {
        double va = Math.abs(v);
        if (ei != null && ei.dimensionless)
            return noCommaFormat.format(v);
        if (Double.isInfinite(va))
            return noCommaFormat.format(v);
        if (v == 0) return "0";
        if (va < 1e-12)
            return noCommaFormat.format(v * 1e15) + "f";
        if (va < 1e-9)
            return noCommaFormat.format(v * 1e12) + "p";
        if (va < 1e-6)
            return noCommaFormat.format(v * 1e9) + "n";
        if (va < 1e-3)
            return noCommaFormat.format(v * 1e6) + "u";
        if (va < 1 /*&& !ei.forceLargeM*/)
            return noCommaFormat.format(v * 1e3) + "m";
        if (va < 1e3)
            return noCommaFormat.format(v);
        if (va < 1e6)
            return noCommaFormat.format(v * 1e-3) + "k";
        if (va < 1e9)
            return noCommaFormat.format(v * 1e-6) + "M";
        return noCommaFormat.format(v * 1e-9) + "G";
    }

    double parseUnits(EditInfo ei) throws java.text.ParseException {
        String s = ei.textf.getText();
        return parseUnits(s);
    }

    static double parseUnits(String s) throws java.text.ParseException {
        s = s.trim();
        double rmsMult = 1;
        if (s.endsWith("rms")) {
            s = s.substring(0, s.length() - 3).trim();
            rmsMult = ROOT2;
        }
        // rewrite shorthand (eg "2k2") in to normal format (eg 2.2k) using regex
        s = s.replaceAll("([0-9]+)([pPnNuUmMkKgG])([0-9]+)", "$1.$3$2");
        // rewrite meg to M
        s = s.replaceAll("[mM][eE][gG]$", "M");
        int len = s.length();
        char uc = s.charAt(len - 1);
        double mult = 1;
        switch (uc) {
            case 'f':
            case 'F':
                mult = 1e-15;
                break;
            case 'p':
            case 'P':
                mult = 1e-12;
                break;
            case 'n':
            case 'N':
                mult = 1e-9;
                break;
            case 'u':
            case 'U':
                mult = 1e-6;
                break;

            // for ohm values, we used to assume mega for lowercase m, otherwise milli
            case 'm':
                mult = /*(ei.forceLargeM) ? 1e6 : */ 1e-3;
                break;

            case 'k':
            case 'K':
                mult = 1e3;
                break;
            case 'M':
                mult = 1e6;
                break;
            case 'G':
            case 'g':
                mult = 1e9;
                break;
        }
        if (mult != 1)
            s = s.substring(0, len - 1).trim();
        return noCommaFormat.parse(s) * mult * rmsMult;
    }

    void apply() {
        int i;
        for (i = 0; i != einfocount; i++) {
            EditInfo ei = einfos[i];
            if (ei.textf != null && ei.text == null) {
                try {
                    double d = parseUnits(ei);
                    ei.value = d;
                } catch (Exception ex) { /* ignored */ }
            }
            if (ei.button != null)
                continue;
            elm.setEditValue(i, ei);

            // update slider if any
            if (elm instanceof CircuitElm) {
                Adjustable adj = cframe.adjustableManager.findAdjustable((CircuitElm) elm, i);
                if (adj != null)
                    adj.setSliderValue(ei.value);
            }
        }
        cframe.setUnsavedChanges(true);
        cframe.needAnalyze();
    }

    public void itemStateChanged(GwtEvent e) {
        Object src = e.getSource();
        int i;
        boolean changed = false;
        boolean applied = false;
        for (i = 0; i != einfocount; i++) {
            EditInfo ei = einfos[i];
            if (ei.choice == src || ei.checkbox == src || ei.button == src) {

                // if we're pressing a button, make sure to apply changes first
                if (ei.button == src && !ei.newDialog) {
                    apply();
                    applied = true;
                }

                elm.setEditValue(i, ei);
                if (ei.newDialog)
                    changed = true;
                cframe.needAnalyze();
            }
        }
        if (changed) {
            // apply changes before we reset everything
            // (need to check if we already applied changes; otherwise Diode create simple model button doesn't work)
            if (!applied)
                apply();

            clearDialog();
            buildDialog();
        }
    }

    public void resetDialog() {
        clearDialog();
        buildDialog();
    }

    public void clearDialog() {
        while (mainPanel.getWidget(0) != bottomButtonPanel)
            mainPanel.remove(0);
    }

}

