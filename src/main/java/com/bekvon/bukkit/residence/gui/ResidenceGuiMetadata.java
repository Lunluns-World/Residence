package com.bekvon.bukkit.residence.gui;

public class ResidenceGuiMetadata {

    private String alias;
    private String description;
    private String icon;
    private boolean listed = true;

    public ResidenceGuiMetadata() {
    }

    public ResidenceGuiMetadata(String alias, String description, String icon, boolean listed) {
        this.alias = normalize(alias);
        this.description = normalize(description);
        this.icon = normalize(icon);
        this.listed = listed;
    }

    private static String normalize(String s) {
        if (s == null)
            return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = normalize(alias);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = normalize(description);
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = normalize(icon);
    }

    public boolean isListed() {
        return listed;
    }

    public void setListed(boolean listed) {
        this.listed = listed;
    }

    public boolean isDefault() {
        return alias == null && description == null && icon == null && listed;
    }
}
