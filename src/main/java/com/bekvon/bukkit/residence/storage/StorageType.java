package com.bekvon.bukkit.residence.storage;

public enum StorageType {
    YAML,
    SQLITE,
    MYSQL,
    MARIADB,
    POSTGRESQL;

    public static StorageType fromString(String value) {
        if (value == null)
            return YAML;
        switch (value.trim().toUpperCase()) {
        case "SQLITE":
            return SQLITE;
        case "MYSQL":
            return MYSQL;
        case "MARIADB":
            return MARIADB;
        case "POSTGRES":
        case "POSTGRESQL":
            return POSTGRESQL;
        case "YAML":
        case "YML":
        default:
            return YAML;
        }
    }

    public boolean isDatabase() {
        return this != YAML;
    }
}
