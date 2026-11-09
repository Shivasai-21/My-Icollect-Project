# Step 2: GitOps repo, Helm chart, EKS (Terraform) and Argo CD

You start this step with: five images in ECR (from the Jenkins pipeline) and the project folder from Step 1.
You finish with: iCollect running on EKS, deployed by Argo CD from Git, usable from a browser, and then destroyed
so you stop paying.

```
 Jenkins --push image--> ECR
    |                      ^
    | commit new tag       | pull image
    v                      |
 icollect-gitops (Git) --watched by--> Argo CD --applies Helm chart--> EKS (namespace icollect-dev)
```

## What is in the project folder for this step

| Folder | Purpose |
|---|---|
| `gitops/` | Goes into its own Git repository, `icollect-gitops`. Helm chart, per-service values, Argo CD manifests |
| `infra/terraform/` | VPC and EKS cluster (plain AWS resources, local state) |
| `infra/scripts/` | `lab-up.sh`, `install-argocd.sh`, `lab-down.sh` |
| `jenkins/update-gitops.sh` | Called by the new "Update GitOps" pipeline stage |

## Cost: read this first

- The EKS control plane bills about $0.10 per hour (about $73 a month) while the cluster exists. Worker nodes
  are extra, billed per hour by EC2. A cluster left running for a month will use a large share of your credits.
- Old Kubernetes versions fall into extended support at six times the control plane rate. The Terraform leaves the
  version unset so EKS picks its current default, and sets `support_type = STANDARD` so the cluster is upgraded instead of
  being moved to extended support. Do not pin an old version.
- The default layout has no NAT gateway (nodes sit in public subnets) because NAT gateways also bill by the hour.
  Set `enable_nat_gateway = true` only if you want the production-style layout.
- Rough size of one 3-hour session: well under a few dollars for control plane plus two small nodes. Treat that as an
  estimate and check the AWS pricing calculator and your Billing page.
- Rule: create at the start of a session, destroy at the end. Set a calendar reminder.
- Make sure your AWS Budgets alert exists (Step 1 guide, section 10).

## Part 0: Tools on your laptop

| Tool | Check |
|---|---|
| Terraform 1.5 or newer (or OpenTofu) | `terraform version` |
| AWS CLI v2, logged in as your admin user | `aws sts get-caller-identity` |
| kubectl | `kubectl version --client` |
| Git | `git --version` |
| Helm (optional, only to preview the chart) | `helm version` |

## Part 1: Create the GitOps repository

1. On GitHub create an empty repository named `icollect-gitops`. Public is simplest (Argo CD can read it without
   credentials). Nothing secret is stored in it. For a private repo, add a repository credential in Argo CD later.
2. Copy the contents of the `gitops/` folder into a new local folder and fill in your details:
   ```bash
   cp -r icollect-platform/gitops icollect-gitops && cd icollect-gitops
   scripts/configure.sh <github-user> <aws-account-id> ap-south-1
   git init -b main && git add . && git commit -m "Initial GitOps repository"
   git remote add origin https://github.com/<github-user>/icollect-gitops.git
   git push -u origin main
   ```
3. Check: `grep -r "repository:" envs/dev | head -2` shows your real ECR address. Image tags still say
   `REPLACE_WITH_IMAGE_TAG`. Jenkins will fill them in next.

## Part 2: Connect Jenkins to the GitOps repo

1. Create a fine-grained personal access token on GitHub, limited to the `icollect-gitops` repository, with
   Contents: Read and write.
2. In Jenkins: Manage Jenkins > Credentials > Add > "Username with password": username = your GitHub user,
   password = the token, ID = `github-gitops`.
3. Manage Jenkins > System > Global properties > Environment variables, add:

   | Name | Value |
   |---|---|
   | `GITOPS_REPO_URL` | `https://github.com/<github-user>/icollect-gitops.git` |
   | `GITOPS_CREDENTIALS_ID` | `github-gitops` |

4. Make sure Jenkins has the updated `icollect-platform` code (the new "Update GitOps" stage lives in the shared
   library; push your updated repo to GitHub).
5. Run all five Jenkins jobs. Each one now ends with an "Update GitOps" stage.
6. Check: in the `icollect-gitops` repo, five commits like `dev: payment-service -> a1b2c3d4 (jenkins build #7)`
   appear, and `grep -rh "^  tag:" envs/dev` shows five real tags. (Running a job again on the same commit prints
   "No change", which is correct.)

## Part 3: Create the EKS cluster

```bash
cd icollect-platform/infra/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars: uncomment api_allowed_cidrs and put your own IP:  curl ifconfig.me
cd ../..
infra/scripts/lab-up.sh
```
Terraform shows a plan (a VPC, subnets, IAM roles, the cluster, a node group; about 25 resources) and asks you to type
`yes`. Expect 15 to 20 minutes.

Check:
```bash
kubectl get nodes          # 2 nodes, STATUS Ready
kubectl get pods -A        # coredns, aws-node, kube-proxy are Running
```
If `kubectl` cannot connect, run the command printed by `terraform output update_kubeconfig`, and check that your
current IP is still in `api_allowed_cidrs`.

## Part 4: Install Argo CD

```bash
infra/scripts/install-argocd.sh
```
It prints the admin password. Open the UI in a second terminal:
```bash
kubectl -n argocd port-forward svc/argocd-server 8443:443
```
Browse to https://localhost:8443, accept the certificate warning, log in as `admin`.

## Part 5: Tell Argo CD what to deploy

From your `icollect-gitops` folder (the one you configured in Part 1):
```bash
kubectl apply -f argocd/project.yaml -f argocd/applicationset.yaml
kubectl -n argocd get applications --watch
```
The ApplicationSet finds the five folders under `envs/dev/` and creates five Applications. Within a few minutes they
should show `Synced` and `Healthy`.

Check:
```bash
kubectl -n icollect-dev get pods        # 5 pods, all Running and 1/1 Ready
kubectl -n icollect-dev get svc         # 5 ClusterIP services
```
Java pods take about a minute or two to become Ready. The `web-dashboard` pod may restart once or twice at first,
because nginx looks up the other services' names when it starts. That is expected and settles by itself.

## Part 6: Use the application

```bash
kubectl -n icollect-dev port-forward svc/web-dashboard 8080:8080
```
Open http://localhost:8080. All four status chips should say "up". Make a payment: you should see the balance fall,
a receipt appear, and the report update. This is the same chain you tested locally, now running on Kubernetes.

Useful commands:
```bash
kubectl -n icollect-dev logs deploy/payment-service --tail=30
kubectl -n icollect-dev describe pod -l app=customer-service
kubectl -n icollect-dev get events --sort-by=.lastTimestamp | tail
```

## Part 7: Experiments (these are your interview stories)

1. **Deploy by Git.** Change a message in `notification-service/server.js`, push. Jenkins builds, pushes a new image, commits
   the new tag; Argo CD rolls the Deployment. Watch with `kubectl -n icollect-dev rollout status deploy/notification-service`.
2. **Rollback by Git.** In `icollect-gitops`, `git revert` that Jenkins commit and push. Argo CD returns to the old image.
3. **Self-heal.** `kubectl -n icollect-dev scale deploy/customer-service --replicas=3`. Argo CD notices the drift
   and scales it back to 1 within a few minutes. The cluster follows Git, not manual edits.
4. **A broken release.** Set a tag that does not exist in `envs/dev/payment-service/values.yaml` and push. The pod goes
   `ImagePullBackOff`, but the old pod keeps serving (rolling update with `maxUnavailable: 0`). Fix it with a revert.
   Diagnose with `kubectl describe pod`.
5. **Dependency failure.** In `envs/dev/notification-service/values.yaml` set `replicaCount: 0`, commit and push. Argo CD scales it
   down (a direct `kubectl scale` would just be healed back). Make a payment in the dashboard: it still succeeds, the dashboard shows
   "No receipt found", and payment-service logs a warning. Revert the commit to restore it.

## Part 8: Destroy everything

```bash
infra/scripts/lab-down.sh
```
It removes the Argo CD applications and any LoadBalancer services first, then runs `terraform destroy` (type `yes`).
Expect 10 to 15 minutes. Then confirm:
```bash
aws eks list-clusters --region ap-south-1          # empty list
aws ec2 describe-instances --region ap-south-1 --filters Name=instance-state-name,Values=running \
    --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==`Name`]|[0].Value]'
```
The Jenkins server is separate and keeps billing until you stop it (Step 3 guide). ECR images cost a small storage
fee, and the lifecycle policy keeps only the last 10 per repository.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `terraform apply` fails on `upgrade_policy` or unknown argument | Old AWS provider | `terraform init -upgrade` |
| `kubectl` times out | Your IP changed or is not in `api_allowed_cidrs` | Update the variable and `terraform apply` |
| Application `Unknown` or `ComparisonError` | Wrong repo URL, or private repo without credentials | Check `repoURL` in the ApplicationSet; add a repo credential |
| No Applications created | `envs/dev/*` folders missing on `main`, or ApplicationSet not applied | `kubectl -n argocd describe applicationset icollect-dev` |
| `ImagePullBackOff` | Tag still `REPLACE_WITH_IMAGE_TAG`, tag not in ECR, or wrong account or region in the repository URL | `kubectl describe pod`; fix values; confirm with `aws ecr describe-images` |
| `CreateContainerConfigError` mentioning non-root | Image user not numeric | The chart sets a numeric `runAsUser`; keep it |
| Pod `Pending` with `Too many pods` or insufficient memory | Small nodes are full | Raise `node_desired_size` (and re-apply) or lower resource requests |
| Java pod restarts during startup | Startup probe too short for a small CPU request | Raise `probes.startup.failureThreshold` |
| `web-dashboard` crash-loops | Upstream Service names not found (other apps not synced yet) | Wait for the other Applications, or check the names and ports |
| `terraform destroy` hangs on the VPC or subnets | A load balancer or network interface was created by Kubernetes | Delete LoadBalancer services, wait a minute, retry |
| Jenkins "Update GitOps" fails with authentication | Token wrong or lacks write access | Recreate the token with Contents: Read and write |

## What was verified, and what was not

Verified while building this: the Helm chart lints and renders for all five services, and the output passes Kubernetes
schema validation; the Argo CD AppProject and ApplicationSet pass their official schemas; the Terraform files parse and are
correctly formatted; the Jenkins "Update GitOps" script was tested against a local Git repository, including
unchanged tags and two services pushing at once; the shared library compiles.

Not verified: `terraform apply` itself (the provider registry was not reachable from the authoring environment, so the
configuration was not validated against the AWS provider's schema), a real EKS cluster, Argo CD, and the Java services.
Expect to fix small things on your first run. If something fails, paste the exact error.

## Interview talking points

- Why GitOps: Git is the source of truth, rollback is a revert, drift is corrected automatically, nobody needs write access to the cluster.
- One chart, many services: all differences are in values files, so a new service is one folder.
- Build once, promote by tag: Jenkins builds one immutable image per commit; deployment is a commit of that tag.
- Safe rollouts: `maxUnavailable: 0`, readiness and startup probes, a failed release leaves the old pods serving.
- Security defaults: numeric non-root user, dropped capabilities, no privilege escalation, images pulled with the node's
  read-only ECR role, nothing secret in Git.
- What you would change for production: namespaces per domain and per environment, NetworkPolicies, private nodes behind NAT,
  remote Terraform state with locking, an Ingress with an ALB and TLS, secrets from Secrets Manager, and separate UAT and PROD
  folders promoted by pull request.

## Next steps

Step 3: Prometheus and Grafana (using the scrape annotations already on the Java pods) and alerts.
Step 4: RDS MySQL, Spring Data JPA and Flyway migrations.
Step 5: Ingress with the AWS Load Balancer Controller, then UAT and PROD folders with approval-gated promotion.
