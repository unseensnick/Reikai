---
title: Built-in sources
titleTemplate: Browse - Frequently Asked Questions
description: Which sources ship with Reikai, which are wrapped extensions, and which bugs belong here.
---

# Built-in sources

_Dev records: [exh-subsystem.md](dev/plans/exh-subsystem.md), [md-enhanced-source.md](dev/plans/md-enhanced-source.md). Doc map: [README.md](README.md)._

Most sources in **Reikai** come from extensions you install yourself.
A few come from Reikai, and those are the ones whose bugs belong in this repository.

- **Built-in** means Reikai ships it. There is no extension to install, and a bug in it is Reikai's.
- **Enhanced** means you install a third-party extension and Reikai wraps it to add metadata, login or other features. How the wrapper behaves is Reikai's; the extension itself belongs to whoever publishes it.
- **Adult** sources only appear once [adult sources](adult-sources.md) are switched on.

## Reporting a bug in one

Keep the issue title general, for example "Error opening a built-in gallery source", and name the source by its shorthand in the body.
That keeps the tracker readable without turning issue titles into a list of site names.

| Shorthand | Source | Type | Content |
| --------- | ------ | ---- | ------- |
| EH  | E-Hentai | Built-in | Adult |
| ExH | ExHentai | Built-in | Adult |
| NH  | nHentai | Built-in + Enhanced | Adult |
| Pu  | Pururin | Built-in | Adult |
| HF  | HentaiFox | Enhanced | Adult |
| Asm | AsmHentai | Enhanced | Adult |
| SN  | SchaleNetwork (Koharu) | Enhanced | Adult |
| 8M  | 8Muses | Enhanced | Adult |
| LRR | LANraragi | Enhanced | Adult |
| MD  | MangaDex | Enhanced | Mainstream |

::: info Anything not in this table is a third-party extension
Its bugs belong to whoever publishes it, not here.
:::
