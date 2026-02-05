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
import java.util.Locale;
import java.util.UUID;

public final class BabBurgHook {

    private final Plugin owner;

    private boolean ready;

    private Object burgManager;                 // com.brandon.burgsbanners.burg.BurgManager
    private Method mGetBurgByClaim;             // BurgManager#getBurgByClaim(ChunkClaim)

    private Constructor<?> cChunkClaim;         // new ChunkClaim(UUID, int, int)

    // Burg accessors (best-effort; resolved lazily)
    private Method mBurgGetId;                  // getId() (optional legacy)
    private Method mBurgGetName;                // getName()
    private Method mBurgGetCurrency;            // getAdoptedCurrencyCode()
    private Method mBurgGetTreasuryUuid;        // getTreasuryUuid()  <-- NEW desired path

    public BabBurgHook(Plugin owner) {
        this.owner = owner;
        tryInit();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isInBurg(Location loc) {
        return burgAt(loc) != null;
    }

    /**
     * Currency code at this location (if burg), else null.
     */
    public String currencyAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        try {
            ensureBurgCurrencyAccessor(burg);
            if (mBurgGetCurrency == null) return null;

            Object v = mBurgGetCurrency.invoke(burg);
            if (v == null) return null;
            return String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Treasury UUID for the burg at this location, or null if wilderness.
     *
     * Preferred: burg.getTreasuryUuid()
     * Fallback: deterministic UUID from "burg:" + burgId (legacy compatibility)
     */
    public UUID treasuryIdAt(Location loc) {
        Object burg = burgAt(loc);
        if (burg == null) return null;

        try {
            // Preferred modern accessor:
            ensureBurgTreasuryAccessor(burg);
            if (mBurgGetTreasuryUuid != null) {
                Object v = mBurgGetTreasuryUuid.invoke(burg);
                if (v instanceof UUID u) return u;
            }

            // Fallback legacy: burg id -> nameUUIDFromBytes
            ensureBurgIdAccessor(burg);
            if (mBurgGetId == null) return null;

            Object idObj = mBurgGetId.invoke(burg);
            String burgId = (idObj == null) ? null : String.valueOf(idObj);
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
        if (!ready || loc == null) return null;

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
            if (bab == null || !bab.isEnabled()) {
                ready = false;
                owner.getLogger().info("[MM] BAB hook: BurgsAndBanners not present.");
                return;
            }

            Object bm = tryGetBurgManager(bab);
            if (bm == null) {
                ready = false;
                owner.getLogger().warning("[MM] BAB hook: could not obtain burgManager (missing getter + field?).");
                return;
            }

            Class<?> chunkClaimClass = Class.forName("com.brandon.burgsbanners.burg.ChunkClaim");

            this.burgManager = bm;
            this.mGetBurgByClaim = bm.getClass().getMethod("getBurgByClaim", chunkClaimClass);
            this.cChunkClaim = chunkClaimClass.getConstructor(UUID.class, int.class, int.class);

            ready = true;
            owner.getLogger().info("[MM] BAB hook: attached via BurgManager#getBurgByClaim(ChunkClaim).");

        } catch (Throwable t) {
            ready = false;
            owner.getLogger().warning("[MM] BAB hook init failed: " + t.getMessage());
        }
    }

    private Object tryGetBurgManager(Plugin bab) {
        // Preferred: public getBurgManager()
        try {
            Method m = bab.getClass().getMethod("getBurgManager");
            return m.invoke(bab);
        } catch (Throwable ignored) { }

        // Fallback: private field burgManager
        try {
            Field f = bab.getClass().getDeclaredField("burgManager");
            f.setAccessible(true);
            return f.get(bab);
        } catch (Throwable ignored) { }

        return null;
    }

    private void ensureBurgIdAccessor(Object burg) {
        if (mBurgGetId != null) return;
        try { mBurgGetId = burg.getClass().getMethod("getId"); } catch (Throwable ignored) { }
        try { mBurgGetName = burg.getClass().getMethod("getName"); } catch (Throwable ignored) { }
    }

    private void ensureBurgCurrencyAccessor(Object burg) {
        if (mBurgGetCurrency != null) return;

        try { mBurgGetCurrency = burg.getClass().getMethod("getAdoptedCurrencyCode"); return; } catch (Throwable ignored) { }
        try { mBurgGetCurrency = burg.getClass().getMethod("getCurrencyCode"); return; } catch (Throwable ignored) { }
        try { mBurgGetCurrency = burg.getClass().getMethod("getCurrency"); } catch (Throwable ignored) { }
    }

    private void ensureBurgTreasuryAccessor(Object burg) {
        if (mBurgGetTreasuryUuid != null) return;
        try { mBurgGetTreasuryUuid = burg.getClass().getMethod("getTreasuryUuid"); } catch (Throwable ignored) { }
    }
}
