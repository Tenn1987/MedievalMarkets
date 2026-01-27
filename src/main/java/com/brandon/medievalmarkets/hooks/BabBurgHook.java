package com.brandon.medievalmarkets.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Optional hook into BurgsAndBanners (BAB) using reflection only.
 * - Detects which burg (if any) owns the chunk at a location.
 * - Returns burg currency code (adopted currency).
 * - Provides a stable MPC treasury UUID per burg.
 */
public final class BabBurgHook {

    private final Plugin owner;

    private Object burgManager;              // com.brandon.burgsbanners.burg.BurgManager
    private Method mGetBurgByClaim;          // BurgManager#getBurgByClaim(ChunkClaim)
    private Constructor<?> cChunkClaim;      // new ChunkClaim(UUID worldId, int chunkX, int chunkZ)

    // Burg getters (resolved lazily at runtime)
    private Method mBurgGetId;               // Burg#getId()
    private Method mBurgGetCurrency;         // Burg#getAdoptedCurrencyCode() or getCurrencyCode()

    public BabBurgHook(Plugin owner) {
        this.owner = owner;
        tryInit();
    }

    public boolean isReady() {
        return burgManager != null && mGetBurgByClaim != null && cChunkClaim != null;
    }

    /** True if this location is inside a claimed burg chunk. */
    public boolean isInBurg(Location loc) {
        return burgAt(loc) != null;
    }

    /** Returns the adopted currency code of the burg at this location, or null if wilderness/BAB unavailable. */
    public String currencyAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        try {
            ensureBurgCurrencyAccessor(burg);

            if (mBurgGetCurrency != null) {
                Object v = mBurgGetCurrency.invoke(burg);
                return (v == null) ? null : v.toString();
            }

            // Field fallback
            try {
                Field f = burg.getClass().getDeclaredField("adoptedCurrencyCode");
                f.setAccessible(true);
                Object v = f.get(burg);
                return (v == null) ? null : v.toString();
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Treasury UUID for the burg at this location, or null if wilderness.
     * Treasury scheme: UUID.nameUUIDFromBytes(("burg:" + burgId).getBytes(UTF_8))
     */
    public UUID treasuryIdAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        try {
            ensureBurgIdAccessor(burg);
            if (mBurgGetId == null) return null;

            String burgId = (String) mBurgGetId.invoke(burg);
            if (burgId == null || burgId.isBlank()) return null;

            return UUID.nameUUIDFromBytes(("burg:" + burgId).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            owner.getLogger().warning("[MM] BAB hook error (treasuryIdAt): " + t.getMessage());
            return null;
        }
    }

    /* =========================
       Internal
       ========================= */

    private Object burgAt(Location loc) {
        if (!isReady() || loc == null) return null;

        try {
            World w = loc.getWorld();
            if (w == null) return null;

            Chunk ch = loc.getChunk();
            Object claim = cChunkClaim.newInstance(w.getUID(), ch.getX(), ch.getZ());
            return mGetBurgByClaim.invoke(burgManager, claim);
        } catch (Throwable t) {
            return null;
        }
    }

    private void tryInit() {
        try {
            Plugin bab = Bukkit.getPluginManager().getPlugin("BurgsAndBanners");
            if (bab == null || !bab.isEnabled()) return;

            Object bm = tryGetBurgManager(bab);
            if (bm == null) return;

            Class<?> burgManagerClass = Class.forName("com.brandon.burgsbanners.burg.BurgManager");
            Class<?> chunkClaimClass = Class.forName("com.brandon.burgsbanners.burg.ChunkClaim");

            this.burgManager = bm;
            this.mGetBurgByClaim = burgManagerClass.getMethod("getBurgByClaim", chunkClaimClass);
            this.cChunkClaim = chunkClaimClass.getConstructor(UUID.class, int.class, int.class);

        } catch (Throwable ignored) {
            // If anything fails, hook stays inactive.
        }
    }

    private Object tryGetBurgManager(Plugin bab) {
        try {
            // Preferred: getBurgManager()
            Method m = bab.getClass().getMethod("getBurgManager");
            return m.invoke(bab);
        } catch (Throwable ignored) { }

        try {
            // Fallback: field named burgManager
            Field f = bab.getClass().getDeclaredField("burgManager");
            f.setAccessible(true);
            return f.get(bab);
        } catch (Throwable ignored) { }

        return null;
    }

    private void ensureBurgIdAccessor(Object burg) {
        if (mBurgGetId != null) return;
        try {
            mBurgGetId = burg.getClass().getMethod("getId");
        } catch (Throwable ignored) { }
    }

    private void ensureBurgCurrencyAccessor(Object burg) {
        if (mBurgGetCurrency != null) return;

        try {
            mBurgGetCurrency = burg.getClass().getMethod("getAdoptedCurrencyCode");
            return;
        } catch (Throwable ignored) { }

        try {
            mBurgGetCurrency = burg.getClass().getMethod("getCurrencyCode");
        } catch (Throwable ignored) { }
    }
}
