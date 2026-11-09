output "region" {
  value = var.region
}

output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "kubernetes_version" {
  value = aws_eks_cluster.main.version
}

output "node_subnets" {
  description = "Where the worker nodes run"
  value       = var.enable_nat_gateway ? "private (NAT gateway enabled)" : "public (no NAT gateway, lowest cost)"
}

output "update_kubeconfig" {
  description = "Run this to point kubectl at the cluster"
  value       = "aws eks update-kubeconfig --name ${aws_eks_cluster.main.name} --region ${var.region}"
}
