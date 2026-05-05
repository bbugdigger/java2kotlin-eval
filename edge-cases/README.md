# Edge cases — curated J2K stress tests

A hand-written dataset of 25 small Java cases organized into 10 categories, each targeting a specific known or suspected J2K weak spot. Each case has the same shape:

```
edge-cases/<category>/<case-name>/
├── source.java     # the Java input
├── expected.kt     # the idiomatic Kotlin we hope J2K produces
└── notes.md        # which hypothesis this case tests + why it's tricky
```

The Phase 9 `EdgeCaseRunner` discovers each leaf, runs J2K against `source.java`, and scores the converted `.kt` against `expected.kt` to produce a per-case **pass / partial / fail** verdict in `docs/edge-cases-report.md` (Phase 10).

## How this connects to the hypotheses doc

Each case is linked from the hypothesis it tests in [`docs/hypotheses.md`](../docs/hypotheses.md). The reverse-lookup index below tells you, for each case, which hypothesis (or hypotheses) it covers.

## Index by category

### `expression-body/` — fold one-statement returns into Kotlin's `fun … = expr` form

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`simple-return-fn/`](expression-body/simple-return-fn/) | [H1](../docs/hypotheses.md#h1-j2k-never-produces-expression-body-functions) | Two `return <expr>;` methods that should fold to `fun … = expr`. |
| [`computed-property/`](expression-body/computed-property/) | [H1](../docs/hypotheses.md#h1-j2k-never-produces-expression-body-functions), [H2](../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties) | Derived getters that should become Kotlin properties with `get() = expr`. |

### `property-folding/` — fold private field + public getter/setter into one Kotlin property

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`simple-bean/`](property-folding/simple-bean/) | [H2](../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties) | Textbook JavaBean: two private fields with paired getters and setters. |
| [`getter-only/`](property-folding/getter-only/) | [H2](../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties) | Final field with a single getter — should fold to a primary-ctor `val`. |
| [`setter-only/`](property-folding/setter-only/) | [H2](../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties) | Write-only field — exercises the case where keeping the setter function is *correct*. |

### `pojo-data-class/` — recognize POJOs that should become `data class`

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`pojo-equals-hashcode/`](pojo-data-class/pojo-equals-hashcode/) | [H3](../docs/hypotheses.md#h3-j2k-does-not-infer-data-class-from-pojos-that-satisfy-the-data-class-shape) | Canonical value class: ctor + final fields + equals/hashCode. |
| [`pojo-with-toString/`](pojo-data-class/pojo-with-toString/) | [H3](../docs/hypotheses.md#h3-j2k-does-not-infer-data-class-from-pojos-that-satisfy-the-data-class-shape) | Same shape plus a custom `toString` that should be retained as an `override`. |

### `nullability/` — nullability inference, null-check rewrites, JSR-305 propagation, Optional unwrapping

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`null-check-then-call/`](nullability/null-check-then-call/) | [H4](../docs/hypotheses.md#h4-j2k-avoids-non-null-assertions--entirely) | `if (x != null) x.foo()` — Kotlin smart-cast candidate. |
| [`optional-chain/`](nullability/optional-chain/) | [H4](../docs/hypotheses.md#h4-j2k-avoids-non-null-assertions--entirely) | `if (a != null && a.b != null)` — should collapse to `a?.b ?: default`. |
| [`jsr305-nullable/`](nullability/jsr305-nullable/) | [H8](../docs/hypotheses.md#h8-j2k-propagates-jsr-305--nullable--notnull-annotations-into-kotlin-nullable--non-null-types) | `@Nullable`/`@NotNull` on parameters and return — expected to be Refuted (J2K should propagate these). |
| [`optional-return/`](nullability/optional-return/) | [H12](../docs/hypotheses.md#h12-j2k-does-not-unwrap-javautiloptional-to-kotlin-t) | Method returning `Optional<String>` — should become `String?`. |
| [`optional-map-chain/`](nullability/optional-map-chain/) | [H12](../docs/hypotheses.md#h12-j2k-does-not-unwrap-javautiloptional-to-kotlin-t) | `.map().filter().orElse()` chain — should become `?.let{ } ?:`. |

### `string-templates/` — convert `+`-concatenation to `"$x"` interpolation

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`simple-concat/`](string-templates/simple-concat/) | [H5](../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates) | `"hello, " + name + "!"` — simplest case. |
| [`mixed-types-concat/`](string-templates/mixed-types-concat/) | [H5](../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates) | Concatenation involving `Int` and `Double` — tests type-aware interpolation. |
| [`multi-arg-concat/`](string-templates/multi-arg-concat/) | [H5](../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates) | Concat that includes a parenthesized `?:` expression — nested-expression stress test. |

### `records/` — Java records → Kotlin data classes

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`java-record-basic/`](records/java-record-basic/) | [H6](../docs/hypotheses.md#h6-j2k-does-not-convert-java-records-to-kotlin-data-classes) | Bare `record Point(int x, int y) {}`. |
| [`java-record-with-method/`](records/java-record-with-method/) | [H6](../docs/hypotheses.md#h6-j2k-does-not-convert-java-records-to-kotlin-data-classes) | Record with two custom methods. |

### `anonymous-classes/` — SAM conversion + nested anonymous closure capture

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`nested-anonymous-runnable/`](anonymous-classes/nested-anonymous-runnable/) | [H7](../docs/hypotheses.md#h7-j2k-preserves-nested-anonymous-classes-verbatim-instead-of-collapsing-to-lambdas) | Two `new Runnable() { ... }` blocks, one nested in the other; both close over a method param. |
| [`anonymous-with-state/`](anonymous-classes/anonymous-with-state/) | [H7](../docs/hypotheses.md#h7-j2k-preserves-nested-anonymous-classes-verbatim-instead-of-collapsing-to-lambdas) | Anonymous class with mutable state — negative-control case (lambdas can't capture mutable state). |

### `checked-exceptions/` — Java `throws` → Kotlin `@Throws` interop

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`throws-ioexception/`](checked-exceptions/throws-ioexception/) | [H9](../docs/hypotheses.md#h9-j2k-silently-drops-javas-throws-declarations-since-kotlin-has-no-checked-exceptions) | Single checked exception. |
| [`throws-multiple/`](checked-exceptions/throws-multiple/) | [H9](../docs/hypotheses.md#h9-j2k-silently-drops-javas-throws-declarations-since-kotlin-has-no-checked-exceptions) | Multiple checked exceptions. |

### `switch-statements/` — switch → when, fallthrough preservation

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`switch-fallthrough/`](switch-statements/switch-fallthrough/) | [H10](../docs/hypotheses.md#h10-j2ks-switch--when-conversion-loses-fallthrough-semantics) | Intentional fallthrough — semantically the case-1 body has to be expanded into case 1 to preserve behaviour. |
| [`switch-no-default/`](switch-statements/switch-no-default/) | [H10](../docs/hypotheses.md#h10-j2ks-switch--when-conversion-loses-fallthrough-semantics) | Negative control: switch with no fallthrough should become a clean expression-body `when`. |

### `concurrency/` — `synchronized` modifier and block

| Case | Hypothesis | One-line summary |
|---|---|---|
| [`synchronized-method/`](concurrency/synchronized-method/) | [H11](../docs/hypotheses.md#h11-j2k-converts-java-synchronized-blocksmethods-to-kotlin-synchronized--calls) | `synchronized` modifier on a method → `@Synchronized`. |
| [`synchronized-block/`](concurrency/synchronized-block/) | [H11](../docs/hypotheses.md#h11-j2k-converts-java-synchronized-blocksmethods-to-kotlin-synchronized--calls) | `synchronized (lock) { ... }` block → `synchronized(lock) { ... }` stdlib call. |

## Counts

- 10 categories
- 25 cases (each = 3 files: `source.java`, `expected.kt`, `notes.md`)
- 12 hypotheses covered (every hypothesis in `docs/hypotheses.md` has at least one case)

## Adding new cases

1. Pick or add a hypothesis in [`docs/hypotheses.md`](../docs/hypotheses.md).
2. Create `edge-cases/<category>/<descriptive-name>/` (existing category if applicable; new one if needed).
3. Write `source.java` (small, self-contained, single-purpose).
4. Write `expected.kt` (the human-port, idiomatic Kotlin you'd write by hand).
5. Write `notes.md` linking back to the hypothesis and explaining the trickiness.
6. Add a row in this README's index table.
7. Add the new case path under "Edge cases testing this" in the matching hypothesis entry.

The Phase 9 `EdgeCaseRunner` discovers cases by directory walk — no registration needed beyond placing the three files in the right place.
