#!/usr/bin/env bash
# Commits a new image tag into the GitOps repository so Argo CD deploys it.
# Usage:  jenkins/update-gitops.sh <service> <image-tag> [env=dev]
# Needs:  GITOPS_REPO_URL  e.g. https://github.com/<user>/icollect-gitops.git
#         GIT_USER, GIT_TOKEN  (GitHub username and a personal access token with write access to that repo)
# The token is handed to git through GIT_ASKPASS, so it never appears in a URL or in the build log.

#!/usr/bin/env bash
# Updates the image tag in the GitOps repository.
set -euo pipefail

[ $# -ge 2 ] || {
  echo "Usage: $0 <service> <image-tag> [env]" >&2
  exit 1
}

SERVICE="$1"
TAG="$2"
ENVIRONMENT="${3:-dev}"

: "${GITOPS_REPO_URL:?GITOPS_REPO_URL is not set}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/askpass.sh" <<'EOS'
#!/bin/sh
case "$1" in
  Username*) printf '%s' "${GIT_USER:-}" ;;
  *)         printf '%s' "${GIT_TOKEN:-}" ;;
esac
EOS

chmod +x "$WORK/askpass.sh"
export GIT_ASKPASS="$WORK/askpass.sh"
export GIT_TERMINAL_PROMPT=0

for attempt in 1 2 3; do
  rm -rf "$WORK/repo"

  git clone -q --depth 1 --branch main \
    "$GITOPS_REPO_URL" "$WORK/repo"

  cd "$WORK/repo"

  # GitOps files are inside the nested gitops directory.
  cd gitops

  git config user.name "jenkins"
  git config user.email "jenkins@localhost"

  bash scripts/set-image-tag.sh "$SERVICE" "$TAG" "$ENVIRONMENT"

  if git diff --quiet; then
    echo "No change: ${SERVICE} is already at ${TAG} in ${ENVIRONMENT}"
    exit 0
  fi

  git add -A

  git commit -q -m \
    "${ENVIRONMENT}: ${SERVICE} -> ${TAG}${BUILD_NUMBER:+ (jenkins build #${BUILD_NUMBER})}"

  if git push -q origin HEAD:main; then
    echo "Pushed ${SERVICE} ${TAG} to ${ENVIRONMENT}"
    exit 0
  fi

  echo "Push rejected (attempt ${attempt}), retrying with a fresh clone"
  cd "$WORK"
  sleep $((attempt * 2))
done

echo "Could not push after 3 attempts" >&2
exit 1

