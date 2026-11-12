#!/usr/bin/env bash
# Creates one private ECR repository per iCollect service (idempotent).
# Usage: AWS_REGION=ap-south-1 ./create-ecr-repos.sh
# Later, Terraform will own these repositories; this script is for the first hands-on run.
set -euo pipefail

REGION="${AWS_REGION:-ap-south-1}"
SERVICES=(customer-service payment-service notification-service reporting-service web-dashboard)

LIFECYCLE='{"rules":[{"rulePriority":1,"description":"Keep the last 10 images","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}'

for svc in "${SERVICES[@]}"; do
  repo="icollect/${svc}"
  if aws ecr describe-repositories --repository-names "$repo" --region "$REGION" >/dev/null 2>&1; then
    echo "exists : $repo"
  else
    aws ecr create-repository \
      --repository-name "$repo" \
      --image-tag-mutability IMMUTABLE \
      --image-scanning-configuration scanOnPush=true \
      --region "$REGION" >/dev/null
    echo "created: $repo"
  fi
  # Keeps storage small so old images do not accumulate cost
  aws ecr put-lifecycle-policy --repository-name "$repo" --lifecycle-policy-text "$LIFECYCLE" --region "$REGION" >/dev/null
done

echo "Registry: $(aws sts get-caller-identity --query Account --output text).dkr.ecr.${REGION}.amazonaws.com"
