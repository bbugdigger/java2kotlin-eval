# jsr305-nullable

**Tests:** [H8](../../../docs/hypotheses.md#h8-j2k-propagates-jsr-305--nullable--notnull-annotations-into-kotlin-nullable--non-null-types).

`@Nullable` and `@NotNull` from `org.jetbrains.annotations` are the canonical Kotlin-Java interop nullability annotations. J2K has had handling for these for a long time; we expect H8 to be **Refuted** (i.e., J2K *does* propagate them correctly into `String?` / `String`).

If H8 holds (i.e., J2K *fails* to propagate), that's a genuine surprise and a high-priority finding — the contract was that this exact case would just work.
