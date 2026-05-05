# computed-property

**Tests:** [H1](../../../docs/hypotheses.md#h1-j2k-never-produces-expression-body-functions), [H2](../../../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties).

`Box` has two derived getters (`getArea`, `getPerimeter`) — each is a single-statement `return <expr>` whose ideal Kotlin form is a property with a `get() = expr` expression body, not a function.

This case combines two predicted failures: H1 says the body becomes a block; H2 says the getter is preserved as a standalone function rather than folded into a property.

Even fully refuting H2 (i.e., J2K does fold these into properties) wouldn't refute H1 — the property's `get()` would still be a block.
