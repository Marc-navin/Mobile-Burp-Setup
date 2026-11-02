package com.example.basicbrowser.variation.model

/**
 * Supported attack types for Request Variation Lab (Intruder).
 *
 * Exactly two attack types are supported:
 * 1. [SNIPER]: Uses one shared payload list, testing each insertion point
 *    individually while all other positions retain their baseline values.
 * 2. [CLUSTER_BOMB]: Uses an independent payload list for every insertion point,
 *    generating the full Cartesian product across all positions.
 */
enum class AttackType(val displayName: String, val description: String) {
    SNIPER(
        displayName = "Sniper",
        description = "Single shared payload list. Tests each insertion point one-by-one while all other positions retain their original baseline values."
    ),
    CLUSTER_BOMB(
        displayName = "Cluster Bomb",
        description = "Separate payload list for each insertion point. Tests every possible combination (Cartesian product) across all positions."
    )
}

