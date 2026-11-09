#!/usr/bin/env bash
# Sets image.tag for one service in one environment. Run from the repository root.
# Usage: scripts/set-image-tag.sh <service> <tag> [env=dev]
# Used by hand, and by Jenkins (jenkins/update-gitops.sh) after every image push.
set -euo pipefail
[ $# -ge 2 ] || { echo "Usage: $0 <service> <tag> [env]" >&2; exit 1; }
svc="$1"; tag="$2"; env="${3:-dev}"
file="envs/${env}/${svc}/values.yaml"
[ -f "$file" ] || { echo "not found: $file" >&2; exit 1; }
# Only the tag line inside the image block (two-space indent) is touched
sed -i.bak -E "s|^(  tag: ).*|\1${tag}|" "$file"
rm -f "$file.bak"
grep -q "^  tag: ${tag}\$" "$file" || { echo "could not set the tag in $file" >&2; exit 1; }
echo "${file}: image.tag=${tag}"
