package com.bekvon.bukkit.residence;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/**
 * 驗證 Residence 主類在伺服器未安裝 WorldGuard / WorldEdit 時，
 * 外部插件透過反射呼叫 Residence.class.getMethod(...)（例如 QuickShop Compat-Residence）
 * 不會因方法簽名引用 optional soft-depend 類別而拋出 NoClassDefFoundError。
 */
class ResidenceReflectionSafetyTest {

    @Test
    void reflectionGetMethodOnResidenceSucceedsWithoutWorldGuardOrWorldEdit() throws Exception {
        URL classesDir = Residence.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader parent = Residence.class.getClassLoader();

        try (URLClassLoader isolated = new URLClassLoader(new URL[] { classesDir }, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.sk89q.")) {
                    throw new ClassNotFoundException("Simulated missing optional plugin class: " + name);
                }
                if (name.equals("com.bekvon.bukkit.residence.Residence")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> resClass = isolated.loadClass("com.bekvon.bukkit.residence.Residence");
            Class<?> claimedResClass = isolated.loadClass("com.bekvon.bukkit.residence.protection.ClaimedResidence");

            Method m = resClass.getMethod("isResidenceInAuction", claimedResClass);
            assertNotNull(m);
            assertEquals(boolean.class, m.getReturnType());

            // 同時驗證所有宣告方法與欄位反射皆不會觸發 WorldGuard/WorldEdit 類別載入
            assertTrue(resClass.getDeclaredMethods().length > 0);
            assertTrue(resClass.getDeclaredFields().length > 0);
        }
    }
}
