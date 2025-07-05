/*    
    Copyright (C) Paul Falstad and Usevalad Khatkevich
    
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

import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.KeyPressEvent;
import com.google.gwt.event.dom.client.KeyPressHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HasVerticalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.ValueBoxBase.TextAlignment;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.OptionsManager;

public class ModDialog extends DialogBox {

    final CirSim cirSim;
    VerticalPanel vp;

    //for "UI scale:"
    HorizontalPanel scaleButtons;
    Button setScale100Button;
    Button setDefaultScaleButton;
    Button setScaleButton;
    HTML scaleScrollbarElm;

    native float getRealScale() /*-{
		try {
			// Method 1: Try to get zoom level using devicePixelRatio and screen measurements
			var devicePixelRatio = $wnd.devicePixelRatio || 1.0;
			var screenWidth = $wnd.screen.width;
			var windowWidth = $wnd.outerWidth;

			// Calculate zoom based on viewport vs screen ratio
			var zoomFromViewport = devicePixelRatio;

			// Method 2: Try to get zoom from CSS transform if available
			var body = $doc.body;
			if (body) {
				var computedStyle = $wnd.getComputedStyle(body);
				// Check for CSS zoom property (non-standard but supported in some browsers)
				if (computedStyle.zoom && computedStyle.zoom !== 'normal') {
					var cssZoom = parseFloat(computedStyle.zoom);
					if (!isNaN(cssZoom) && cssZoom > 0) {
						return cssZoom;
					}
				}

				// Check for CSS transform scale
				var transform = computedStyle.transform;
				if (transform && transform !== 'none') {
					var matrix = transform.match(/matrix\(([^)]+)\)/);
					if (matrix) {
						var values = matrix[1].split(',');
						if (values.length >= 6) {
							var scaleX = parseFloat(values[0]);
							if (!isNaN(scaleX) && scaleX > 0) {
								return scaleX;
							}
						}
					}
				}
			}

			// Method 3: Try to detect zoom using stored scale from localStorage
			var storage = $wnd.localStorage;
			if (storage) {
				var storedScale = storage.getItem('MOD_UIScale');
				if (storedScale && storedScale !== 'null') {
					var scale = parseFloat(storedScale);
					if (!isNaN(scale)) {
						return scale;
					}
				}
			}

			// Method 4: Use devicePixelRatio as fallback
			// Convert devicePixelRatio to our scale format
			// devicePixelRatio 1.0 = 100% = scale 1.0
			// devicePixelRatio 1.25 = 125% = scale 1.25
			return Math.max(0.5, Math.min(3.0, devicePixelRatio));

		} catch (e) {
			console.warn('Failed to get zoom level:', e);
			return 1.0; // Default scale (100%)
		}
	}-*/;

    String getScaleScrollbar(float value, int scale) {
        return "<input type=\"range\" id=\"scaleUI\" oninput=\"getScaleInfo()\"" +
                "min=\"0.5\" max=\"3\" step=\"0.1\" value=\"" + value + "\"" +
                "style=\"width:355px\"><b><span class=\"scaleInfo\"" +
                "style=\"vertical-align:super\">" + scale + "%</span></b>";
    }

    //for "Top menu bar:"
    HorizontalPanel topMenuBarVars;
    CheckBox setStandartTopMenu;
    CheckBox setSmallTopMenu;

    //for "Start/Stop and Reset buttons:"
    HorizontalPanel btnsPreview;
    HTML previewText;
    Button resetPrevBtn;
    Button stopPrevBtn;
    HorizontalPanel SRCheckBoxes;
    VerticalPanel vp1;
    CheckBox setDefaultSRBtns;
    CheckBox setClassicSRBtns;
    VerticalPanel vp2;
    CheckBox setStopIcon;
    CheckBox setPauseIcon;
    VerticalPanel vp3;
    CheckBox hideSRBtns;

    native boolean CirSimIsRunning()/*-{
		return $wnd.CircuitJS1.isRunning();
	}-*/;

    CheckBox setShowSidebaronStartup;
    CheckBox setOverlayingSidebar;

    HorizontalPanel SBAnimationSettings;
    TextBox DurationSB;
    CheckBox setAnimSidebar;
    ListBox SpeedCurveSB;

    int getSpeedCurveSBIndex(String val) {
        for (int i = 0; i <= SpeedCurveSB.getItemCount(); i++) {
            if (SpeedCurveSB.getValue(i) == val)
                return i;
        }
        return 1;
    }

    CheckBox setPauseWhenWinUnfocused;

    Button closeButton;

    public ModDialog(CirSim cirSim) {
        super();
        this.cirSim = cirSim;
        vp = new VerticalPanel();
        setWidget(vp);
        setText("Modification Setup");
        vp.setWidth("400px");

        vp.add(new HTML("<big><b>UI Scale:</b></big>"));
        vp.add(scaleScrollbarElm = new HTML(getScaleScrollbar(getRealScale(), (int) (getRealScale() * 100))));
        vp.setCellHorizontalAlignment(scaleScrollbarElm, HasHorizontalAlignment.ALIGN_CENTER);
        vp.add(scaleButtons = new HorizontalPanel());
        scaleButtons.setWidth("100%");
        scaleButtons.add(setScale100Button = new Button("100%",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        vp.remove(scaleScrollbarElm);
                        vp.insert(scaleScrollbarElm = new HTML(getScaleScrollbar(1, 100)), 1);
                        vp.setCellHorizontalAlignment(scaleScrollbarElm, HasHorizontalAlignment.ALIGN_CENTER);
                        CirSim.executeJS("setScaleUI()");
                        OptionsManager.setOptionInStorage("MOD_UIScale", "1");
                    }
                }));
        scaleButtons.add(setDefaultScaleButton = new Button("Default scale<b>*</b>",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        vp.remove(scaleScrollbarElm);
                        vp.insert(scaleScrollbarElm = new HTML(getScaleScrollbar(CirSim.getDefaultScale(),
                                (int) (CirSim.getDefaultScale() * 100))), 1);
                        vp.setCellHorizontalAlignment(scaleScrollbarElm, HasHorizontalAlignment.ALIGN_CENTER);
                        CirSim.executeJS("setScaleUI()");
                        OptionsManager.setOptionInStorage("MOD_UIScale", Float.toString(CirSim.getDefaultScale()));
                    }
                }));
        scaleButtons.add(setScaleButton = new Button("Set",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        CirSim.executeJS("setScaleUI()");
                        OptionsManager.setOptionInStorage("MOD_UIScale", Float.toString(getRealScale()));
                    }
                }));
        vp.add(new HTML("<p>* - the default UI scale for your monitor is set to " +
                (int) (CirSim.getDefaultScale() * 100) + "%</p>"));
        // Styling buttons:
        setScaleButton.addStyleName("modButtons"); //.setHeight("20px");
        setScaleButton.addStyleName("modSetButtons");
        setScale100Button.addStyleName("modButtons");
        setDefaultScaleButton.addStyleName("modButtons");
        //remove "gwt-Button" style:
        setScaleButton.removeStyleName("gwt-Button");
        setScale100Button.removeStyleName("gwt-Button");
        setDefaultScaleButton.removeStyleName("gwt-Button");
        scaleButtons.setCellHorizontalAlignment(setScale100Button, HasHorizontalAlignment.ALIGN_CENTER);
        scaleButtons.setCellHorizontalAlignment(setDefaultScaleButton, HasHorizontalAlignment.ALIGN_CENTER);
        scaleButtons.setCellHorizontalAlignment(setScaleButton, HasHorizontalAlignment.ALIGN_CENTER);

        vp.add(new HTML("<hr><big><b>Top Menu Bar:</b></big>"));
        vp.add(topMenuBarVars = new HorizontalPanel());
        topMenuBarVars.setWidth("100%");
        topMenuBarVars.add(setStandartTopMenu = new CheckBox("Standart"));
        topMenuBarVars.add(setSmallTopMenu = new CheckBox("Small"));
        if (CirSim.MENU_BAR_HEIGHT < 30) setSmallTopMenu.setValue(true);
        else setStandartTopMenu.setValue(true);

        setStandartTopMenu.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setSmallTopMenu.getValue()) {
                    CirSim.MENU_BAR_HEIGHT = 30;
                    setSmallTopMenu.setValue(false);
                    setStandartTopMenu.setValue(true);
                    CirSim.executeJS("CircuitJS1.redrawCanvasSize()");
                    OptionsManager.setOptionInStorage("MOD_TopMenuBar", "standart");
                    CirSim.executeJS("setAllAbsBtnsTopPos(\"" + CirSim.getAbsBtnsTopPos() + "px\")");
                } else {
                    setStandartTopMenu.setValue(true);
                }
            }
        });

        setSmallTopMenu.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setStandartTopMenu.getValue()) {
                    CirSim.MENU_BAR_HEIGHT = 20;
                    setStandartTopMenu.setValue(false);
                    setSmallTopMenu.setValue(true);
                    CirSim.executeJS("CircuitJS1.redrawCanvasSize()");
                    OptionsManager.setOptionInStorage("MOD_TopMenuBar", "small");
                    CirSim.executeJS("setAllAbsBtnsTopPos(\"" + CirSim.getAbsBtnsTopPos() + "px\")");
                } else {
                    setSmallTopMenu.setValue(true);
                }
            }
        });

        // Styling checkboxes:
        topMenuBarVars.setCellHorizontalAlignment(setStandartTopMenu, HasHorizontalAlignment.ALIGN_CENTER);
        topMenuBarVars.setCellHorizontalAlignment(setSmallTopMenu, HasHorizontalAlignment.ALIGN_CENTER);

        vp.add(new HTML("<hr><big><b>Start/Stop and Reset Buttons:</b></big>"));
        vp.add(btnsPreview = new HorizontalPanel());
        btnsPreview.setWidth("100%");
        btnsPreview.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
        btnsPreview.add(previewText = new HTML("<big>Preview:</big>"));
        btnsPreview.add(resetPrevBtn = new Button("&#8634;"));
        btnsPreview.add(stopPrevBtn = new Button("&#xE800;"));
        // Replace preview button icon logic
        String absBtnIcon = OptionsManager.getOptionFromStorage("MOD_absBtnIcon", null);
        if ("pause".equals(absBtnIcon)) {
            stopPrevBtn.getElement().setInnerHTML("&#xE802;");
        }
        btnsPreview.setCellHorizontalAlignment(previewText, HasHorizontalAlignment.ALIGN_RIGHT);
        btnsPreview.setCellHorizontalAlignment(resetPrevBtn, HasHorizontalAlignment.ALIGN_RIGHT);
        btnsPreview.setCellHorizontalAlignment(stopPrevBtn, HasHorizontalAlignment.ALIGN_LEFT);

        stopPrevBtn.setStyleName("run-stop-btn modPrevBtn");
        resetPrevBtn.setStyleName("reset-btn modPrevBtn");
        // Replace theme logic
        String absBtnTheme = OptionsManager.getOptionFromStorage("MOD_absBtnTheme", null);
        if ("default".equals(absBtnTheme)) {
            stopPrevBtn.addStyleName("modDefaultRunStopBtn");
            resetPrevBtn.addStyleName("modDefaultResetBtn");
        } else {
            stopPrevBtn.addStyleName("gwt-Button");
            resetPrevBtn.addStyleName("gwt-Button");
        }

        vp.add(SRCheckBoxes = new HorizontalPanel());
        SRCheckBoxes.setWidth("100%");
        SRCheckBoxes.add(vp1 = new VerticalPanel());
        SRCheckBoxes.add(vp2 = new VerticalPanel());
        SRCheckBoxes.add(vp3 = new VerticalPanel());
        vp3.setHeight("100%");
        SRCheckBoxes.setCellVerticalAlignment(vp3, HasVerticalAlignment.ALIGN_MIDDLE);
        //SRCheckBoxes.setCellHorizontalAlignment(vp3, HasHorizontalAlignment.ALIGN_CENTER);

        vp1.add(new HTML("<b>Theme:</b>"));
        vp1.add(setDefaultSRBtns = new CheckBox("Default"));
        vp1.add(setClassicSRBtns = new CheckBox("Classic"));

        vp2.add(new HTML("<b>Icon:</b>"));
        vp2.add(setStopIcon = new CheckBox("Stop"));
        vp2.add(setPauseIcon = new CheckBox("Pause"));

        vp3.add(hideSRBtns = new CheckBox("HIDE BUTTONS"));

        if (cirSim.absResetBtn.getElement().hasClassName("modDefaultResetBtn"))
            setDefaultSRBtns.setValue(true);
        else setClassicSRBtns.setValue(true);

		/*if (CirSim.absRunStopBtn.getElement().getInnerText() == "&#xE800;")
			setStopIcon.setValue(true);
		else setPauseIcon.setValue(true);*/ //try to get info from localstorage

        // Replace all lstor.getItem/setItem usages with OptionsManager
        if ("stop".equals(OptionsManager.getOptionFromStorage("MOD_absBtnIcon", null))) {
            setStopIcon.setValue(true);
        } else {
            setPauseIcon.setValue(true);
        }

        if (OptionsManager.getBoolOptionFromStorage("MOD_hideAbsBtns", false)) {
            hideSRBtns.setValue(true);
        }

        setDefaultSRBtns.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setClassicSRBtns.getValue()) {
                    setClassicSRBtns.setValue(false);
                    setDefaultSRBtns.setValue(true);
                    //Buttons for preview:
                    stopPrevBtn.removeStyleName("gwt-Button");
                    stopPrevBtn.addStyleName("modDefaultRunStopBtn");
                    resetPrevBtn.removeStyleName("gwt-Button");
                    resetPrevBtn.addStyleName("modDefaultResetBtn");
                    //Absolute buttons:
                    cirSim.absRunStopBtn.removeStyleName("gwt-Button");
                    cirSim.absRunStopBtn.removeStyleName("modClassicButton");
                    cirSim.absRunStopBtn.addStyleName("modDefaultRunStopBtn");
                    cirSim.absResetBtn.removeStyleName("gwt-Button");
                    cirSim.absResetBtn.removeStyleName("modClassicButton");
                    cirSim.absResetBtn.addStyleName("modDefaultResetBtn");
                    //save:
                    OptionsManager.setOptionInStorage("MOD_absBtnTheme", "default");
                } else {
                    setDefaultSRBtns.setValue(true);
                }
            }
        });
        setClassicSRBtns.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setDefaultSRBtns.getValue()) {
                    setDefaultSRBtns.setValue(false);
                    setClassicSRBtns.setValue(true);
                    //Buttons for preview:
                    stopPrevBtn.removeStyleName("modDefaultRunStopBtn");
                    stopPrevBtn.addStyleName("gwt-Button");
                    resetPrevBtn.removeStyleName("modDefaultResetBtn");
                    resetPrevBtn.addStyleName("gwt-Button");
                    //Absolute buttons:
                    cirSim.absRunStopBtn.removeStyleName("modDefaultRunStopBtn");
                    cirSim.absRunStopBtn.addStyleName("gwt-Button");
                    cirSim.absRunStopBtn.addStyleName("modClassicButton");
                    cirSim.absResetBtn.removeStyleName("modDefaultResetBtn");
                    cirSim.absResetBtn.addStyleName("gwt-Button");
                    cirSim.absResetBtn.addStyleName("modClassicButton");
                    //save:
                    OptionsManager.setOptionInStorage("MOD_absBtnTheme", "classic");
                } else {
                    setClassicSRBtns.setValue(true);
                }
            }
        });
        setStopIcon.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setPauseIcon.getValue()) {
                    setPauseIcon.setValue(false);
                    setStopIcon.setValue(true);
                    stopPrevBtn.getElement().setInnerHTML("&#xE800;");
                    if (CirSimIsRunning())
                        cirSim.absRunStopBtn.getElement().setInnerHTML("&#xE800;");
                    //save:
                    OptionsManager.setOptionInStorage("MOD_absBtnIcon", "stop");
                } else {
                    setStopIcon.setValue(true);
                }
            }
        });
        setPauseIcon.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setStopIcon.getValue()) {
                    setStopIcon.setValue(false);
                    setPauseIcon.setValue(true);
                    stopPrevBtn.getElement().setInnerHTML("&#xE802;");
                    if (CirSimIsRunning())
                        cirSim.absRunStopBtn.getElement().setInnerHTML("&#xE802;");
                    //save:
                    OptionsManager.setOptionInStorage("MOD_absBtnIcon", "pause");
                } else {
                    setPauseIcon.setValue(true);
                }
            }
        });
        hideSRBtns.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (hideSRBtns.getValue()) {
                    cirSim.absRunStopBtn.setVisible(false);
                    cirSim.absResetBtn.setVisible(false);
                    //save:
                    OptionsManager.setOptionInStorage("MOD_hideAbsBtns", "true");
                } else {
                    cirSim.absRunStopBtn.setVisible(true);
                    cirSim.absResetBtn.setVisible(true);
                    //save:
                    OptionsManager.setOptionInStorage("MOD_hideAbsBtns", "false");
                }
            }
        });

        vp.add(new HTML("<hr><big><b>Sidebar:</b></big>"));
        vp.add(setOverlayingSidebar = new CheckBox("Sidebar is overlaying"));
        vp.setCellVerticalAlignment(setOverlayingSidebar, HasVerticalAlignment.ALIGN_TOP);
        //vp.setCellHorizontalAlignment(setOverlayingSidebar, HasHorizontalAlignment.ALIGN_CENTER);

        vp.add(SBAnimationSettings = new HorizontalPanel());
        SBAnimationSettings.setWidth("100%");
        SBAnimationSettings.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
        SBAnimationSettings.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
        SBAnimationSettings.add(setAnimSidebar = new CheckBox("Animation:"));
        SBAnimationSettings.setCellHorizontalAlignment(setAnimSidebar, HasHorizontalAlignment.ALIGN_LEFT);
        SBAnimationSettings.add(new Label("duration is"));
        SBAnimationSettings.add(DurationSB = new TextBox());
        DurationSB.setMaxLength(3);
        DurationSB.setVisibleLength(1);
        DurationSB.setHeight("0.6em");
        DurationSB.setAlignment(TextAlignment.CENTER);
        SBAnimationSettings.add(new Label("ms,"));
        SBAnimationSettings.add(new Label("speed curve is"));
        SBAnimationSettings.add(SpeedCurveSB = new ListBox());
        SpeedCurveSB.getElement().setAttribute("style", "appearance:none;padding:1px;");
        SpeedCurveSB.addItem("ease");
        SpeedCurveSB.addItem("linear");
        SpeedCurveSB.addItem("ease-in");
        SpeedCurveSB.addItem("ease-out");
        SpeedCurveSB.addItem("ease-in-out");

        vp.add(setShowSidebaronStartup = new CheckBox("Show sidebar on startup"));
        //vp.setCellHorizontalAlignment(setShowSidebaronStartup, HasHorizontalAlignment.ALIGN_CENTER);

        if (OptionsManager.getBoolOptionFromStorage("MOD_overlayingSidebar", false)) {
            setOverlayingSidebar.setValue(true);
        } else {
            setAnimSidebar.setEnabled(false);
        }
        if (OptionsManager.getBoolOptionFromStorage("MOD_overlayingSBAnimation", false)) {
            setAnimSidebar.setValue(true);
        }

        DurationSB.setValue(OptionsManager.getOptionFromStorage("MOD_SBAnim_duration", null));
        SpeedCurveSB.setItemSelected(getSpeedCurveSBIndex(OptionsManager.getOptionFromStorage("MOD_SBAnim_SpeedCurve", null)), true);

        DurationSB.addKeyPressHandler(new KeyPressHandler() {
            public void onKeyPress(KeyPressEvent event) {
                if (!Character.isDigit(event.getCharCode()) || "0".equals(DurationSB.getValue())) {
                    ((TextBox) event.getSource()).cancelKey();
                }
            }
        });

        DurationSB.addChangeHandler(new ChangeHandler() {
            public void onChange(ChangeEvent event) {
                OptionsManager.setOptionInStorage("MOD_SBAnim_duration", DurationSB.getValue());
                if (setOverlayingSidebar.getValue())
                    CirSim.setSidebarAnimation(DurationSB.getValue(), SpeedCurveSB.getSelectedItemText());
            }
        });

        SpeedCurveSB.addChangeHandler(new ChangeHandler() {
            public void onChange(ChangeEvent event) {
                OptionsManager.setOptionInStorage("MOD_SBAnim_SpeedCurve", SpeedCurveSB.getSelectedItemText());
                if (setOverlayingSidebar.getValue())
                    CirSim.setSidebarAnimation(DurationSB.getValue(), SpeedCurveSB.getSelectedItemText());
            }
        });

        setOverlayingSidebar.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setOverlayingSidebar.getValue()) {
                    OptionsManager.setOptionInStorage("MOD_overlayingSidebar", true);
                    setAnimSidebar.setEnabled(true);
                    if (setAnimSidebar.getValue())
                        CirSim.setSidebarAnimation(DurationSB.getValue(), SpeedCurveSB.getSelectedItemText());
                } else {
                    OptionsManager.setOptionInStorage("MOD_overlayingSidebar", false);
                    setAnimSidebar.setEnabled(false);
                    CirSim.setSidebarAnimation("none", "");
                }
            }
        });
        setAnimSidebar.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                if (setAnimSidebar.getValue()) {
                    OptionsManager.setOptionInStorage("MOD_overlayingSBAnimation", true);
                    if (setOverlayingSidebar.getValue())
                        CirSim.setSidebarAnimation(DurationSB.getValue(), SpeedCurveSB.getSelectedItemText());
                } else {
                    OptionsManager.setOptionInStorage("MOD_overlayingSBAnimation", false);
                    CirSim.setSidebarAnimation("none", "");
                }
            }
        });

        if (OptionsManager.getBoolOptionFromStorage("MOD_showSidebaronStartup", false)) {
            setShowSidebaronStartup.setValue(true);
        }
        setShowSidebaronStartup.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                OptionsManager.setOptionInStorage("MOD_showSidebaronStartup", setShowSidebaronStartup.getValue());
            }
        });

        vp.add(new HTML("<hr><big><b>Other:</b></big>"));
        vp.add(setPauseWhenWinUnfocused = new CheckBox("Pause simulation when window loses focus<br>(recommended for optimal performance)", true));
        vp.setCellHorizontalAlignment(setPauseWhenWinUnfocused, HasHorizontalAlignment.ALIGN_CENTER);
        if (OptionsManager.getBoolOptionFromStorage("MOD_setPauseWhenWinUnfocused", false)) {
            setPauseWhenWinUnfocused.setValue(true);
        }
        setPauseWhenWinUnfocused.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                OptionsManager.setOptionInStorage("MOD_setPauseWhenWinUnfocused", setPauseWhenWinUnfocused.getValue());
            }
        });
        vp.add(new HTML("<br>"));

        vp.add(closeButton = new Button("<b>Close</b>",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        closeDialog();
                    }
                }));
        vp.setCellHorizontalAlignment(closeButton,
                HasHorizontalAlignment.ALIGN_CENTER);

        center();
        show();
    }

    protected void closeDialog() {
        hide();
    }

}
