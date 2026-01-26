package com.brandon.medievalmarkets.market;

import org.bukkit.Material;

public final class Commodity {
    private final String id;
    private final Material material;
    private final double baseValue;
    private final double elasticity;

    public Commodity(String id, Material material, double baseValue, double elasticity) {
        this.id = id;
        this.material = material;
        this.baseValue = baseValue;
        this.elasticity = elasticity;
    }

    public String id() { return id; }
    public Material material() { return material; }
    public double baseValue() { return baseValue; }
    public double elasticity() { return elasticity; }
}
