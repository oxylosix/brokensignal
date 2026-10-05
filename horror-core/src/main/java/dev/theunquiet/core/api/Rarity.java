package dev.theunquiet.core.api;

public enum Rarity {
    COMMON(100),
    UNCOMMON(36),
    RARE(10),
    VERY_RARE(3),
    SECRET(2),
    ULTRA_RARE(1);

    private final int weight;

    Rarity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
