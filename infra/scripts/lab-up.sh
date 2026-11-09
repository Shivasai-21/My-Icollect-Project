#!/usr/bin/env bash
# Creates the VPC and EKS cluster, then points kubectl at it. Takes roughly 15 to 20 minutes.
# Usage: infra/scripts/lab-up.sh            (asks for confirmation before applying)
#        AUTO_APPROVE=1 infra/scripts/lab-up.sh
set -euo pipefail
cd "$(dirname "$0")/../terraform"

terraform init -input=false
terraform apply ${AUTO_APPROVE:+-auto-approve}

REGION="$(terraform output -raw region)"
NAME="$(terraform output -raw cluster_name)"
aws eks update-kubeconfig --name "$NAME" --region "$REGION"

echo "--- nodes ---"
kubectl get nodes -o wide
echo
echo "Cluster is up and billing by the hour. When you finish: infra/scripts/lab-down.sh"
