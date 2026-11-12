#!/usr/bin/env bash
# Replaces the placeholders once, after you copy this folder into your own icollect-gitops repository.
# Usage: scripts/configure.sh <github-user> <aws-account-id> <aws-region>
set -euo pipefail
[ $# -eq 3 ] || { echo "Usage: $0 shivasai-21 577152805698 ap-south-1" >&2; exit 1; }
USER_NAME="$1"; ACCOUNT="$2"; REGION="$3"
cd "$(dirname "$0")/.."
files=$(grep -rlE "YOUR_GITHUB_USER|ACCOUNT_ID|\.REGION\." argocd envs)
for f in $files; do
  sed -i.bak -e "s|YOUR_GITHUB_USER|${USER_NAME}|g" -e "s|ACCOUNT_ID|${ACCOUNT}|g" -e "s|\.REGION\.|.${REGION}.|g" "$f"
  rm -f "$f.bak"
done
echo "Configured for github.com/${USER_NAME}/icollect-gitops, account ${ACCOUNT}, region ${REGION}"
grep -rn "REPLACE_WITH_IMAGE_TAG" envs | sed 's/^/still needs a tag: /' || true
