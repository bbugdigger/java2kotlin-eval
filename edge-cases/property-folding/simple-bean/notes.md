# simple-bean

**Tests:** [H2](../../../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties).

The textbook Java POJO: private fields with paired public getters and setters following JavaBeans naming. Idiomatic Kotlin folds the field + accessors into a single `var` property whose synthesized getters and setters serve the same role.

If H2 holds, J2K will emit `Person` with three declarations per logical field: a private `var firstName: String?`, a `getFirstName(): String?` function, and a `setFirstName(firstName: String?)` function — surfacing the same problem we observed on every spring-petclinic bean class.
