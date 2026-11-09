variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name used in tags"
  type        = string
  default     = "lab"
}

variable "cluster_name" {
  description = "EKS cluster name (also used as a prefix for other resources)"
  type        = string
  default     = "icollect-lab"
}

variable "kubernetes_version" {
  description = <<-EOT
    Kubernetes minor version, for example "1.36". Leave null to let EKS use its current default version,
    which is always in standard support. Old versions fall into extended support, which bills the control plane
    at about six times the normal hourly rate, so do not pin an old version.
  EOT
  type        = string
  default     = null
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to use (EKS needs at least 2)"
  type        = number
  default     = 2
}

variable "enable_nat_gateway" {
  description = <<-EOT
    false (default): worker nodes run in public subnets with public IPs. This is cheaper because there is no NAT gateway
    (which bills by the hour), and is acceptable for a short-lived lab. Nodes only accept traffic from inside the cluster.
    true: nodes run in private subnets behind one NAT gateway, which is the production-style layout.
  EOT
  type        = bool
  default     = false
}

variable "node_instance_types" {
  description = "EC2 instance types for the worker nodes"
  type        = list(string)
  default     = ["t3.medium"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT (SPOT is cheaper but nodes can be reclaimed)"
  type        = string
  default     = "ON_DEMAND"
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_min_size" {
  type    = number
  default = 1
}

variable "node_max_size" {
  type    = number
  default = 3
}

variable "api_allowed_cidrs" {
  description = "CIDR blocks allowed to reach the Kubernetes API endpoint. Restrict this to your own IP, for example [\"203.0.113.10/32\"]."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
