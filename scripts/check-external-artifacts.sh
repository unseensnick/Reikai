#!/usr/bin/env bash
# Fail if a dependency hosted outside Maven Central has stopped resolving.
#
# JitPack builds an artifact from a source repo on demand and serves it from a cache. If that repo
# is deleted or renamed, or its own build stops working, the artifact disappears and cannot be
# rebuilt. That is not hypothetical: com.github.arkon.FlexibleAdapter went this way, and a clone of
# main could not be built from a cold cache until the catalog moved to Maven Central.
#
# Nothing in the normal loop catches it. A developer's Gradle cache still holds the jar, and CI
# restores that cache rather than re-resolving, so the failure only appears on a fresh clone. This
# asks the repository directly instead.
#
# Maven Central and Google artifacts are immutable once published, so only the JitPack coordinates
# are checked. Usage: bash scripts/check-external-artifacts.sh

set -euo pipefail

CATALOGS=(gradle/libs.versions.toml gradle/mihon.versions.toml)
declare -A VERSIONS
COORDS=()

for catalog in "${CATALOGS[@]}"; do
    [ -f "$catalog" ] || continue
    section=""
    while IFS= read -r line; do
        case "$line" in
            '[versions]')  section=versions;  continue ;;
            '[libraries]') section=libraries; continue ;;
            '['*)          section="";        continue ;;
        esac
        if [ "$section" = versions ] &&
           [[ "$line" =~ ^[[:space:]]*([A-Za-z0-9_.-]+)[[:space:]]*=[[:space:]]*\"([^\"]+)\" ]]; then
            VERSIONS["${BASH_REMATCH[1]}"]="${BASH_REMATCH[2]}"
        elif [ "$section" = libraries ] && [[ "$line" =~ module[[:space:]]*=[[:space:]]*\"([^\"]+)\" ]]; then
            module="${BASH_REMATCH[1]}"
            case "$module" in com.github.*) ;; *) continue ;; esac
            if [[ "$line" =~ version\.ref[[:space:]]*=[[:space:]]*\"([^\"]+)\" ]]; then
                version="${VERSIONS[${BASH_REMATCH[1]}]:-}"
            elif [[ "$line" =~ version[[:space:]]*=[[:space:]]*\"([^\"]+)\" ]]; then
                version="${BASH_REMATCH[1]}"
            else
                version=""
            fi
            if [ -z "$version" ]; then
                echo "cannot resolve a version for $module; the catalog parser needs a look" >&2
                exit 1
            fi
            COORDS+=("$module:$version")
        fi
    done < "$catalog"
done

# A parser that quietly stops matching looks exactly like a healthy tree, so an empty result is a
# failure rather than a pass.
if [ "${#COORDS[@]}" -eq 0 ]; then
    echo "no JitPack coordinates found in ${CATALOGS[*]}; the parser is broken, not the tree" >&2
    exit 1
fi

missing=()
inconclusive=()

for coord in "${COORDS[@]}"; do
    group="${coord%%:*}"
    rest="${coord#*:}"
    artifact="${rest%%:*}"
    version="${rest#*:}"
    path="${group//./\/}/$artifact/$version/$artifact-$version.pom"
    status=$(curl -s -o /dev/null -w '%{http_code}' -m 30 --retry 2 --retry-delay 3 \
        "https://jitpack.io/$path" || echo 000)
    case "$status" in
        200) printf '  ok            %s\n' "$coord" ;;
        404|410)
            printf '  MISSING (%s)  %s\n' "$status" "$coord"
            missing+=("$coord")
            ;;
        *)
            # Rate limiting and outages answer like this. Treating them as missing would cry wolf,
            # which is how a scheduled check gets ignored.
            printf '  unclear (%s)  %s\n' "$status" "$coord"
            inconclusive+=("$coord")
            ;;
    esac
    sleep 1
done

echo
if [ "${#missing[@]}" -gt 0 ]; then
    echo "${#missing[@]} dependency(s) no longer resolve and cannot be rebuilt:"
    printf '  %s\n' "${missing[@]}"
    echo
    echo "A fresh clone cannot be built until each moves to a coordinate that still exists."
    echo "Check whether Mihon has already moved it: grep the artifact in refs/mihon's catalog."
    exit 1
fi

if [ "${#inconclusive[@]}" -gt 0 ]; then
    echo "checked ${#COORDS[@]}, ${#inconclusive[@]} gave no clear answer (JitPack was slow or rate-limiting)."
    echo "Not treated as a failure. Re-run to get a clear reading."
    exit 0
fi

echo "checked ${#COORDS[@]} JitPack dependencies, all still resolve."
