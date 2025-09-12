package com.coder.toolbox.util

import kotlin.random.Random

val adjectives = listOf(
    "brave", "calm", "clever", "curious", "eager",
    "fast", "gentle", "happy", "kind", "lively",
    "mighty", "noble", "quiet", "rapid", "shiny",
    "swift", "tough", "vast", "wise", "young"
)

val nouns = listOf(
    "bear", "cloud", "dragon", "eagle", "fire",
    "forest", "hawk", "lion", "moon", "mountain",
    "owl", "panther", "river", "shadow", "sky",
    "star", "storm", "tree", "wolf", "wind"
)

/**
 * Easy to remember names, helps with logs investigation
 */
fun friendlyName(): String {
    val number = Random.nextInt(10, 99) // 2 digits for extra uniqueness
    return "${adjectives.random()}-${nouns.random()}-$number"
}