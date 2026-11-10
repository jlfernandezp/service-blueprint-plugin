package org.processmining.serviceblueprint.model;

import java.awt.Color;

/**
 * Niveles del Service Blueprint.
 */
public enum BlueprintLevel {

    CUSTOMER("Customer", new Color(173, 216, 230)),
    FRONTSTAGE("Frontstage", new Color(144, 238, 144)),
    BACKSTAGE("Backstage", new Color(255, 228, 181)),
    SUPPORT("Support", new Color(216, 191, 216)),
    UNCATEGORIZED("Unclassified", new Color(255, 182, 182));

    private final String displayName;
    private final Color color;

    BlueprintLevel(String displayName, Color color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Color getColor() {
        return color;
    }
}
