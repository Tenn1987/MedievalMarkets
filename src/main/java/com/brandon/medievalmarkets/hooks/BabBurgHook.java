package com.brandon.medievalmarkets.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Optional hook into Burgs & Banners (BAB).
 *
 * Goals:
 *  - No compile-time dependency on BAB
 *  - Provide the exact helper methods MarketService expects:
 *      isInBurg(Location)
 *      currencyAt(Location)
 *      treasuryIdAt(Location)
 *
 * If BAB is missing or its internals changed, this gracefully returns:
 *  - isInBurg => false
 *  - currencyAt => null
 *  - treasuryIdAt => null
 */
public final class BabBurgHook {

    private final Plugin owner;

    private Object burgManager;         // BAB BurgManager (reflected)
    private Method mGetBurgAtChunk;     // burgManager.getBurgAt(Chunk)
    private Method mGetBurgAtLocation;  // burgManager.getBurgAt(Location)

    private Method mBurgName;           // burg.getName()
    private Method mBurgCurrency;       // burg.getCurrency() or getCurrencyCode()
    private Method mBurgTreasuryId;     // burg.getTreasuryId() (UUID)

    private boolean available;

    public BabBurgHook(Plugin owner) {
        this.owner = owner;
        attach();
    }

    /** True if BAB hook is attached and can resolve burgs. */
    public boolean isAvailable() {
        return available;
    }

    /** Returns true if the location is inside a burg (claimed territory). */
    public boolean isInBurg(Location loc) {
        return burgAt(loc) != null;
    }

    /**
     * Returns the burg currency code at a location, or null if not in a burg or unknown.
     * Example: Rome => "DEN"
     */
    public String currencyAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        // Try: burg.getCurrency() or burg.getCurrencyCode()
        try {
            if (mBurgCurrency != null) {
                Object val = mBurgCurrency.invoke(burg);
                if (val != null) {
                    String s = String.valueOf(val).trim();
                    return s.isEmpty() ? null : s.toUpperCase(Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Returns the treasury UUID for the burg at a location.
     * Returns null in wilderness (sales not allowed).
     *
     * If BAB doesn't expose a treasury UUID, we generate a stable UUID from the burg name.
     */
    public UUID treasuryIdAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        // Prefer a real treasury UUID if BAB provides it.
        try {
            if (mBurgTreasuryId != null) {
                Object val = mBurgTreasuryId.invoke(burg);
                if (val instanceof UUID id) return id;
            }
        } catch (Throwable ignored) {}

        // Fallback: stable UUID from burg name (so treasury IDs remain consistent across restarts).
        String name = burgName(burg);
        if (name == null || name.isBlank()) return null;
        return UUID.nameUUIDFromBytes(("BAB_TREASURY:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /* =========================
       Internal
       ========================= */

    private Object burgAt(Location loc) {
        if (!available || loc == null || loc.getWorld() == null) return null;

        try {
            // Some BAB builds: getBurgAt(Location)
            if (mGetBurgAtLocation != null) {
                return mGetBurgAtLocation.invoke(burgManager, loc);
            }
        } catch (Throwable ignored) {}

        try {
            // Others: getBurgAt(Chunk)
            if (mGetBurgAtChunk != null) {
                Chunk ch = loc.getChunk();
                return mGetBurgAtChunk.invoke(burgManager, ch);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private String burgName(Object burg) {
        if (burg == null) return null;
        try {
            if (mBurgName != null) {
                Object val = mBurgName.invoke(burg);
                return val == null ? null : String.valueOf(val);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void attach() {
        // Try common BAB plugin names
        Plugin bab =
                Bukkit.getPluginManager().getPlugin("BurgsAndBanners");
        if (bab == null) bab = Bukkit.getPluginManager().getPlugin("Burgs & Banners");
        if (bab == null) bab = Bukkit.getPluginManager().getPlugin("BAB");

        if (bab == null || !bab.isEnabled()) {
            owner.getLogger().info("BAB hook: Burgs & Banners not present. Territory restrictions disabled.");
            available = false;
            return;
        }

        // Find BurgManager on the BAB main class:
        //  - method getBurgManager()
        //  - or field burgManager
        Object mgr = null;
        try {
            Method m = safeMethod(bab.getClass(), "getBurgManager");
            if (m != null) mgr = m.invoke(bab);
        } catch (Throwable ignored) {}

        if (mgr == null) {
            mgr = safeFieldValue(bab, "burgManager");
        }

        if (mgr == null) {
            owner.getLogger().warning("BAB hook: Could not obtain burgManager from BAB. Hook disabled.");
            available = false;
            return;
        }

        this.burgManager = mgr;

        // Burg lookup methods
        this.mGetBurgAtLocation = safeMethod(mgr.getClass(), "getBurgAt", Location.class);
        this.mGetBurgAtChunk = safeMethod(mgr.getClass(), "getBurgAt", Chunk.class);

        if (mGetBurgAtLocation == null && mGetBurgAtChunk == null) {
            owner.getLogger().warning("BAB hook: Could not find BurgManager.getBurgAt(Location/Chunk). Hook disabled.");
            available = false;
            return;
        }

        // We now need to discover Burg methods by sampling a burg class (best-effort).
        // We'll attempt to resolve method handles by name; if missing, we fallback gracefully.
        // These are OPTIONAL; missing doesn't disable hook, it only limits currency/treasury.
        // We'll resolve these once we can obtain a burg instance at runtime, but we can still pre-wire names.
        // We do a lazy bind: we bind once we see a burg in burgAt().
        bindBurgMethodsBestEffort();

        available = true;
        owner.getLogger().info("BAB hook: Attached (reflection).");
    }

    private void bindBurgMethodsBestEffort() {
        // We can’t instantiate a Burg safely here, so we just prep for common method names.
        // When we first get a burg object, Java reflection will still work with these Method handles
        // IF we bound them from the right class. So we do a conservative approach:
        // We leave them null here and bind lazily later only if needed.
        // (But we DO try to bind against common Burg class name if it exists.)
        try {
            Class<?> burgClass = Class.forName("com.brandon.burgsbanners.burg.Burg");
            this.mBurgName = safeMethod(burgClass, "getName");
            this.mBurgCurrency = firstNonNull(
                    safeMethod(burgClass, "getCurrency"),
                    safeMethod(burgClass, "getCurrencyCode")
            );
            this.mBurgTreasuryId = firstNonNull(
                    safeMethod(burgClass, "getTreasuryId"),
                    safeMethod(burgClass, "treasuryId")
            );
        } catch (Throwable ignored) {
            // Fine. We'll still work for isInBurg() without these.
        }
    }

    private static Method safeMethod(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... vals) {
        for (T v : vals) if (v != null) return v;
        return null;
    }

    private static Object safeFieldValue(Object instance, String fieldName) {
        try {
            Class<?> c = instance.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(instance);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
