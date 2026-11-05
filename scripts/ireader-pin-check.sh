#!/usr/bin/env bash
# Fail when what IReader extensions link against has drifted from upstream IReader.
#
# Installed IReader extensions run on the host's copy of IReader's source-api and Ktor, compiled
# against whatever upstream IReader shipped when each was built. So these two follow upstream
# IReader, not "latest" and not Mihon: a host Ktor older than upstream's failed every request of a
# newer extension with an IllegalAccessError. Renovate leaves them alone on purpose (renovate.json).
#
# Run it after pulling refs/IReader. A mismatch is a prompt to review, not to bump blindly: check
# what the new version ships first (docs/dev/feature-ports.md, IReader). Usage, from the repo root:
#   bash scripts/ireader-pin-check.sh [path-to-IReader-clone]

set -euo pipefail

REF="${1:-../refs/IReader}"
OURS=gradle/libs.versions.toml

# An upstream source-api already reviewed and declined, so it reads as held rather than drift until
# upstream moves past it. 1.5.3 bundles its own copies of eu.kanade.tachiyomi's network and source
# classes, which would shadow the ones manga extensions link against (docs/dev/feature-ports.md).
REVIEWED_API="1.5.3"

version_of() { # catalog key
    sed -n "s/^$2 = \"\([^\"]*\)\".*/\1/p" "$1" | head -1
}

[ -f "$REF/gradle/libs.versions.toml" ] || { echo "No IReader clone at $REF" >&2; exit 2; }

our_ktor=$(version_of "$OURS" ireader-ktor)
our_api=$(version_of "$OURS" ireader-sourceApi)
their_ktor=$(version_of "$REF/gradle/libs.versions.toml" ktor)
their_api=$(sed -n 's/.*findProperty("packageVersion").*?: "\([^"]*\)".*/\1/p' "$REF/source-api/build.gradle.kts" | head -1)

status=0
check() { # name ours theirs
    if [ "$2" = "$3" ]; then
        echo "ok    $1 $2"
    else
        echo "DRIFT $1: Reikai $2, upstream IReader $3"
        status=1
    fi
}
check "Ktor" "$our_ktor" "$their_ktor"
if [ "$their_api" = "$REVIEWED_API" ] && [ "$our_api" != "$their_api" ]; then
    echo "held  source-api $our_api (upstream $their_api reviewed and declined)"
else
    check "source-api" "$our_api" "$their_api"
fi
exit $status
