#!/usr/bin/env bash
# Installs Argo CD into the cluster and prints the admin password.
# Pin a version with:  ARGOCD_VERSION=<tag> infra/scripts/install-argocd.sh   (default: the "stable" release)
set -euo pipefail
VERSION="${ARGOCD_VERSION:-stable}"

kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
# Server-side apply is needed because some Argo CD CRDs are too large for a normal client-side apply
kubectl apply -n argocd --server-side --force-conflicts \
  -f "https://raw.githubusercontent.com/argoproj/argo-cd/${VERSION}/manifests/install.yaml"

kubectl -n argocd rollout status deploy/argocd-server --timeout=300s
kubectl -n argocd rollout status deploy/argocd-applicationset-controller --timeout=300s

echo
echo "Argo CD admin password:"
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
echo
echo
echo "Open the UI:  kubectl -n argocd port-forward svc/argocd-server 8443:443"
echo "Then browse to https://localhost:8443 (accept the certificate warning), user: admin"
