# icollect-gitops

The deployment source of truth. Argo CD watches this repository and makes the cluster match it.
Copy this folder's contents into its own Git repository named `icollect-gitops` (not inside the application repository).

```
charts/microservice/     one reusable Helm chart for all services
envs/dev/<service>/      values.yaml per service (image tag, ports, env vars, resources)
argocd/                  AppProject and the ApplicationSet that creates one Application per envs/dev/* folder
scripts/configure.sh     fills in your GitHub user, AWS account and region (run once)
scripts/set-image-tag.sh sets image.tag for one service (Jenkins runs this after every push)
```

Flow: Jenkins pushes an image to ECR, then commits the new tag into `envs/dev/<service>/values.yaml`.
Argo CD sees the commit and rolls the Deployment. Rollback is `git revert` of that commit.
Add `envs/uat/` and `envs/prod/` later by copying a folder and changing values; promotion is a pull request.
