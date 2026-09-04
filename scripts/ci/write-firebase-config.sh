#!/usr/bin/env bash
# Writes the Firebase config that a telemetry build needs. It is never committed (see .gitignore),
# so it comes from the repo secret; fail loudly rather than let the Google Services plugin guess.
set -euo pipefail

dest="app/google-services.json"

if [ -z "${GOOGLE_SERVICES_JSON:-}" ]; then
  echo "::error::GOOGLE_SERVICES_JSON secret is missing; -Pinclude-telemetry cannot build without it."
  exit 1
fi
printf '%s' "$GOOGLE_SERVICES_JSON" > "$dest"
echo "Wrote $dest."
