package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.Widget;

/**
 * Utility class for common GWT operations and CSS styling
 */
public class GWTUtils {

    /**
     * Sets a CSS property on a widget's element
     * @param widget The widget to style
     * @param property The CSS property name
     * @param value The CSS property value
     */
    public static void setStyle(Widget widget, String property, String value) {
        widget.getElement().getStyle().setProperty(property, value);
    }

    /**
     * Sets multiple CSS properties on a widget's element
     * @param widget The widget to style
     * @param properties Array of property-value pairs (property1, value1, property2, value2, ...)
     */
    public static void setStyles(Widget widget, String... properties) {
        if (properties.length % 2 != 0) {
            throw new IllegalArgumentException("Properties must be in pairs (property, value)");
        }

        for (int i = 0; i < properties.length; i += 2) {
            widget.getElement().getStyle().setProperty(properties[i], properties[i + 1]);
        }
    }

    // Flexbox-specific helper methods

    /**
     * Sets flex container properties on a widget
     * @param widget The widget to make a flex container
     * @param direction Flex direction (row, column, etc.)
     */
    public static void setFlexContainer(Widget widget, String direction) {
        setStyles(widget,
            "display", "flex",
            "flexDirection", direction
        );
    }

    /**
     * Sets flex item properties on a widget
     * @param widget The widget to configure as flex item
     * @param grow Flex grow value
     * @param shrink Flex shrink value
     * @param basis Flex basis value
     */
    public static void setFlexItem(Widget widget, String grow, String shrink, String basis) {
        setStyles(widget,
            "flexGrow", grow,
            "flexShrink", shrink,
            "flexBasis", basis
        );
    }

    /**
     * Sets common flex shorthand property
     * @param widget The widget to style
     * @param flex Flex shorthand value (e.g., "1 1 auto")
     */
    public static void setFlex(Widget widget, String flex) {
        setStyle(widget, "flex", flex);
    }

    // Layout helper methods

    /**
     * Sets position properties on a widget
     * @param widget The widget to position
     * @param position Position type (relative, absolute, etc.)
     */
    public static void setPosition(Widget widget, String position) {
        setStyle(widget, "position", position);
    }

    /**
     * Sets size properties on a widget
     * @param widget The widget to size
     * @param width Width value
     * @param height Height value
     */
    public static void setSize(Widget widget, String width, String height) {
        setStyles(widget,
            "width", width,
            "height", height
        );
    }

    /**
     * Sets padding on a widget
     * @param widget The widget to pad
     * @param padding Padding value
     */
    public static void setPadding(Widget widget, String padding) {
        setStyle(widget, "padding", padding);
    }

    /**
     * Sets margin on a widget
     * @param widget The widget to margin
     * @param margin Margin value
     */
    public static void setMargin(Widget widget, String margin) {
        setStyle(widget, "margin", margin);
    }

    /**
     * Sets border properties on a widget
     * @param widget The widget to border
     * @param border Border value
     */
    public static void setBorder(Widget widget, String border) {
        setStyle(widget, "border", border);
    }

    /**
     * Sets background color on a widget
     * @param widget The widget to color
     * @param color Background color value
     */
    public static void setBackgroundColor(Widget widget, String color) {
        setStyle(widget, "backgroundColor", color);
    }

    /**
     * Sets text alignment on a widget
     * @param widget The widget to align
     * @param align Text alignment value
     */
    public static void setTextAlign(Widget widget, String align) {
        setStyle(widget, "textAlign", align);
    }

    /**
     * Sets font properties on a widget
     * @param widget The widget to style
     * @param size Font size
     * @param weight Font weight
     * @param family Font family
     */
    public static void setFont(Widget widget, String size, String weight, String family) {
        setStyles(widget,
            "fontSize", size,
            "fontWeight", weight,
            "fontFamily", family
        );
    }

    /**
     * Sets overflow properties on a widget
     * @param widget The widget to style
     * @param overflowX Horizontal overflow
     * @param overflowY Vertical overflow
     */
    public static void setOverflow(Widget widget, String overflowX, String overflowY) {
        setStyles(widget,
            "overflowX", overflowX,
            "overflowY", overflowY
        );
    }

    /**
     * Sets box sizing on a widget
     * @param widget The widget to style
     * @param boxSizing Box sizing value (border-box, content-box)
     */
    public static void setBoxSizing(Widget widget, String boxSizing) {
        setStyle(widget, "boxSizing", boxSizing);
    }

    /**
     * Clears a CSS property on a widget's element
     * @param widget The widget to modify
     * @param property The CSS property name to clear
     */
    public static void clearStyle(Widget widget, String property) {
        widget.getElement().getStyle().clearProperty(property);
    }
}
