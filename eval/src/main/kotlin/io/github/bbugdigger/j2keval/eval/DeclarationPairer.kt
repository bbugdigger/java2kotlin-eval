package io.github.bbugdigger.j2keval.eval

/**
 * Pairs a gold declaration with its converted counterpart.
 *
 * Both sides are non-null when the FQN (and signature, for overloads) matched.
 * Any declaration that exists on only one side ends up in [PairingResult.goldOnly]
 * or [PairingResult.convertedOnly].
 */
data class DeclarationPair(val gold: Declaration, val converted: Declaration)

/** Output of [DeclarationPairer.pair]. */
data class PairingResult(
    val matched: List<DeclarationPair>,
    val goldOnly: List<Declaration>,
    val convertedOnly: List<Declaration>,
)

/**
 * Match declarations between two trees by FQN, with signature fallback for overloads.
 *
 * Strategy:
 *  - For each FQN, group declarations on both sides.
 *  - If a single declaration sits on both sides under the same FQN, pair them.
 *  - If multiple declarations share an FQN (overloaded functions), match by signature
 *    (`name(arity)` — see [Declaration.signature]).
 *  - Anything left unmatched goes to [PairingResult.goldOnly] / [PairingResult.convertedOnly].
 *
 * Pairing on lexical FQN means renames between gold and converted will appear as
 * orphans on both sides, which is correct: we want to surface those for the
 * findings narrative rather than hiding them behind fuzzy matching.
 */
object DeclarationPairer {

    fun pair(gold: List<Declaration>, converted: List<Declaration>): PairingResult {
        val goldByFqn: Map<String, List<Declaration>> = gold.groupBy { it.fqn }
        val convertedByFqn: Map<String, List<Declaration>> = converted.groupBy { it.fqn }

        val matched = mutableListOf<DeclarationPair>()
        val goldOnly = mutableListOf<Declaration>()
        val convertedOnly = mutableListOf<Declaration>()

        val allFqns = goldByFqn.keys + convertedByFqn.keys
        for (fqn in allFqns) {
            val g = goldByFqn[fqn].orEmpty()
            val c = convertedByFqn[fqn].orEmpty()
            when {
                g.isEmpty() -> convertedOnly += c
                c.isEmpty() -> goldOnly += g
                g.size == 1 && c.size == 1 -> matched += DeclarationPair(g.single(), c.single())
                else -> matchByOverloadSignature(g, c, matched, goldOnly, convertedOnly)
            }
        }

        return PairingResult(
            matched = matched,
            goldOnly = goldOnly,
            convertedOnly = convertedOnly,
        )
    }

    private fun matchByOverloadSignature(
        gold: List<Declaration>,
        converted: List<Declaration>,
        matched: MutableList<DeclarationPair>,
        goldOnly: MutableList<Declaration>,
        convertedOnly: MutableList<Declaration>,
    ) {
        val convertedBySig = converted.groupBy { it.signature }.mapValues { (_, v) -> v.toMutableList() }
        for (g in gold) {
            val bucket = convertedBySig[g.signature]
            val match = bucket?.removeFirstOrNull()
            if (match != null) matched += DeclarationPair(g, match) else goldOnly += g
        }
        // Remaining converted entries are orphans.
        for ((_, leftover) in convertedBySig) convertedOnly += leftover
    }
}
