# Librarium — reusable development skills

Task-specific playbooks for working on the **Librarium Android** client. Each
subdirectory is a self-contained skill: a `SKILL.md` with the instructions, plus
any reference material it needs.

These exist because this repo depends on two large external codebases that are
expensive to re-read from scratch every session:

| Skill | Use it when |
| --- | --- |
| [`librarium-ios-reference/`](librarium-ios-reference/SKILL.md) | You need to know how the shipping iOS client behaves — the reference implementation this Android client mirrors. |
| [`librarium-api-contract/`](librarium-api-contract/SKILL.md) | You need to call the Go backend, add a screen that reads server data, or verify an endpoint/field name. |
| [`android-build-toolchain/`](android-build-toolchain/SKILL.md) | You need to build, compile-check, or debug the Gradle build on this machine. |

## The one rule that matters

**The API owns the logic.** The iOS app renders what the server computes; it does
not reimplement server-side filtering or ranking. Do not port server logic into
Kotlin, and do not invent endpoint or field names — verify them against the docs
in [`../docs/`](../docs) or the live source.

## Reference documents

Deep reference material lives in [`../docs/`](../docs):

- [`ios-app-architecture.md`](../docs/ios-app-architecture.md) — the iOS app's topology, feature inventory, services and models.
- [`ios-scan-flow.md`](../docs/ios-scan-flow.md) — the barcode/ISBN scan-to-add flow in full detail.
- [`api-contract.md`](../docs/api-contract.md) — the librarium-api HTTP contract.
- [`implementation-status.md`](../docs/implementation-status.md) — what is built, what is left, and the known debt. Read this first when resuming.
- [`dependency-versions.md`](../docs/dependency-versions.md) — the verified Gradle version matrix and its sources.
