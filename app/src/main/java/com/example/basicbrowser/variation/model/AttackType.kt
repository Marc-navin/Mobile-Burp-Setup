package com.example.basicbrowser.variation.model

/**
 * Supported attack types for Request Variation Lab (Burp Suite Intruder style).
 *
 * All four standard attack types:
 * 1. [SNIPER]: One shared payload set. Tests one position at a time while others retain baseline values.
 * 2. [BATTERING_RAM]: One shared payload set. The exact same payload is inserted into every marked position simultaneously.
 * 3. [PITCHFORK]: One payload set per position. Sets advance in lockstep, terminating when the shortest set is exhausted.
 * 4. [CLUSTER_BOMB]: One payload set per position. Tests every combination (Cartesian product) across all sets.
 */
enum class AttackType(val displayName: String, val description: String) {
    SNIPER(
        displayName = "Sniper",
        description = "Single shared payload set. Tests each insertion point one-by-one while all other positions retain baseline values."
    ),
    BATTERING_RAM(
        displayName = "Battering Ram",
        description = "Single shared payload set. Inserts the exact same payload simultaneously into every marked position."
    ),
    PITCHFORK(
        displayName = "Pitchfork",
        description = "One payload set per position. Sets advance in lockstep (request 1 takes item 1 from each set), stopping at the shortest set."
    ),
    CLUSTER_BOMB(
        displayName = "Cluster Bomb",
        description = "One payload set per position. Tests every combination (full Cartesian product) across all positions."
    );

    val isSinglePayloadSet: Boolean get() = this == SNIPER || this == BATTERING_RAM
    val isMultiPayloadSet: Boolean get() = this == PITCHFORK || this == CLUSTER_BOMB
}
