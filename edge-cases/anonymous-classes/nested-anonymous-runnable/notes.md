# nested-anonymous-runnable

**Tests:** [H7](../../../docs/hypotheses.md#h7-j2k-preserves-nested-anonymous-classes-verbatim-instead-of-collapsing-to-lambdas).

Two `new Runnable() { ... }` blocks, one nested inside the other. `Runnable` is a SAM interface, so each anonymous instance is a candidate for collapse to a Kotlin lambda via the `Runnable { ... }` SAM constructor.

The outer anonymous captures `label` (closes over the method parameter). The inner anonymous *also* captures `label` indirectly via the outer enclosing scope — exactly the kind of scope-chain that Meta's blog flagged as a J2K weak spot.

H7 expects J2K to keep at least the nested `Runnable` as `object : Runnable { ... }` rather than collapse it.
