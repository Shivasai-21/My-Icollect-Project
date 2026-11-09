#!/usr/bin/env bash
# Removes everything the lab created so billing stops. Takes roughly 10 to 15 minutes.
# Usage: infra/scripts/lab-down.sh        (asks for confirmation)
#        AUTO_APPROVE=1 infra/scripts/lab-down.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Step 1: delete what Kubernetes created in AWS (load balancers, volumes). If these still exist,
# Terraform cannot delete the VPC and the destroy hangs or fails.
if command -v kubectl >/dev/null 2>&1 && kubectl get ns >/dev/null 2>&1; then
  echo "Deleting Argo CD applications (and the workloads they own)..."
  kubectl -n argocd delete applicationset --all --ignore-not-found --timeout=120s || true
  kubectl -n argocd delete applications.argoproj.io --all --ignore-not-found --timeout=180s || true

  echo "Deleting any Services of type LoadBalancer..."
  kubectl get svc -A --no-headers -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,TYPE:.spec.type \
    | awk '$3=="LoadBalancer"{print $1" "$2}' \
    | while read -r ns name; do kubectl delete svc -n "$ns" "$name" --ignore-not-found || true; done

  echo "Waiting briefly for AWS to release those resources..."
  sleep 45
fi

# Step 2: destroy the infrastructure
cd "$ROOT/terraform"
terraform destroy ${AUTO_APPROVE:+-auto-approve}

echo
echo "Destroyed. Confirm nothing is left running:"
echo "  aws eks list-clusters --region \$(terraform output -raw region 2>/dev/null || echo ap-south-1)"
echo "  aws ec2 describe-nat-gateways --filter Name=state,Values=available --query 'NatGateways[].NatGatewayId'"
echo "  and check Billing > Cost Explorer the next day."
