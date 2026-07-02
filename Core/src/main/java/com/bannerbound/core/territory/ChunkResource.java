package com.bannerbound.core.territory;

/**
 * The resource type a chunk can carry in the specialized-chunks system (the diplomacy scarcity layer).
 * {@code NONE} = an ordinary chunk. Derived deterministically from world seed + chunk coords + biome —
 * see {@link ChunkResources}. Design: repo-root {@code SPECIALIZED_CHUNKS_PLAN.md}.
 */
public enum ChunkResource {
    NONE,
    CATTLE,
    HORSES,
    COPPER,
    TIN,
    MARBLE,
    IRON,
    PIGS,
    CHICKENS,
    SHEEP,
    FISH,
    WHEAT,
    CARROT,
    BEETROOT,
    POTATO,
    STONE,
    CLAY,
    SAND,
    COAL,
    LIMESTONE,
    ANDESITE,
    DIORITE,
    GRANITE;

    /** Single-char glyph for the {@code /bannerbound chunktype} debug map. */
    public char code() {
        return switch (this) {
            case NONE -> '.';
            case CATTLE -> 'C';
            case HORSES -> 'H';
            case COPPER -> 'c';
            case TIN -> 't';
            case MARBLE -> 'M';
            case IRON -> 'I';
            case PIGS -> 'P';
            case CHICKENS -> 'K';
            case SHEEP -> 'S';
            case FISH -> 'F';
            case WHEAT -> 'w';
            case CARROT -> 'r';
            case BEETROOT -> 'b';
            case POTATO -> 'p';
            case STONE -> 's';
            case CLAY -> 'y';
            case SAND -> 'a';
            case COAL -> 'o';
            case LIMESTONE -> 'l';
            case ANDESITE -> 'n';
            case DIORITE -> 'd';
            case GRANITE -> 'g';
        };
    }
}
