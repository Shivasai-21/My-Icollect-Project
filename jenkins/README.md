# Jenkins pipeline for iCollect (source code to ECR)

Every service has a three-line `Jenkinsfile` that calls one shared pipeline
(`jenkins/shared-library/vars/icollectPipeline.groovy`). Adding a sixth service means adding one more
three-line file, not copying a pipeline.

## What the pipeline does

| # | Stage | What runs | Fails the build when |
|---|---|---|---|
| 1 | Prepare | Reads the git SHA, builds the image name `<acct>.dkr.ecr.<region>.amazonaws.com/icollect/<service>:<sha8>` | `AWS_ACCOUNT_ID` is not set |
| 2 | Build and test | Java: `mvn -B -ntp clean verify`. Node: `npm ci && npm test`. Python: venv, `pytest`. Static: nothing | A test fails |
| 3 | SonarQube analysis | `sonar-scanner` (only if `RUN_SONAR` is ticked) | Scanner error |
| 4 | Quality gate | `waitForQualityGate` (only if `RUN_SONAR`) | Gate fails |
| 5 | Docker build | `docker build` using the service's Dockerfile | Build error |
| 6 | Trivy scan | Reports HIGH, fails on CRITICAL that has a fix | A fixable CRITICAL vulnerability |
| 7 | Push to ECR | Checks the repo exists, skips if the tag already exists, logs in, pushes, prints the digest | Repo missing, no permission |
| 8 | Update GitOps | Optional. Commits the new image tag into `envs/dev/<service>/values.yaml` in the GitOps repo (`jenkins/update-gitops.sh`) | Bad token, no write access |

The image tag is the 8-character git SHA, and ECR tags are immutable. Re-running the same commit does not fail,
it skips the push. Stage 7 only runs if `PUSH_IMAGE` is ticked and, in a multibranch job, the branch is `main`.
Stage 8 only runs when `GITOPS_REPO_URL` is set (see `GITOPS-EKS-GUIDE.md`, Part 2), so until you set it the pipeline
effectively ends at ECR. The build also archives `image-<service>.txt` with the image address.

## Part A: AWS side (once)

1. Pick a region (the scripts default to `ap-south-1`, Mumbai) and note your 12-digit account ID.
2. Create the repositories:
   ```bash
   aws configure                        # or use SSO; run as an admin user, not root
   cd jenkins && AWS_REGION=ap-south-1 ./create-ecr-repos.sh
   ```
   They are private, tag-immutable, scan on push, and keep only the last 10 images to limit storage cost.
3. Give Jenkins permission to push. Copy `ecr-push-policy.json`, replace `<REGION>` and `<ACCOUNT_ID>`,
   and create an IAM policy named `icollect-ecr-push` from it. Then pick ONE way to attach it:
   - **Jenkins on EC2 (recommended):** create an IAM role for EC2, attach the policy, and attach the role
     to the instance. No keys are stored anywhere.
   - **Jenkins on your own machine:** create an IAM user with only this policy, create an access key,
     and store it as a Jenkins credential (Part C). Delete the key when you finish the project.

## Part B: Jenkins machine

Option 1, EC2 (closest to production): Ubuntu 22.04 or 24.04, `t3.medium` or larger (Maven plus Docker builds
need around 4 GB RAM), 30 GB disk, security group allowing port 8080 only from your own IP. It bills
by the hour while running, so stop the instance when you are not practicing. The public IP changes after a stop and start.

Option 2, your own Linux, macOS or WSL2 machine (no cost). If the dashboard is running on port 8080, move
Jenkins to another port such as 8090.

Install Jenkins natively, not in a container. A containerized Jenkins needs extra volume and socket setup
for Docker builds and is a common source of confusing errors.

1. Install Jenkins from https://www.jenkins.io/doc/book/installing/linux/ (follow the current page, repository keys change).
2. Install the tools the pipeline calls: `./jenkins/setup-tools-ubuntu.sh`
   (JDK 17, Maven, Node 20, Python with venv, Docker, AWS CLI v2, and adds the `jenkins` user to the `docker` group).
3. Open `http://<host>:8080`, unlock with `sudo cat /var/lib/jenkins/secrets/initialAdminPassword`,
   install the suggested plugins, and create an admin user.
4. Extra plugins (Manage Jenkins > Plugins): none are required for the basic run. For Sonar, add
   "SonarQube Scanner".

Check as the jenkins user: `sudo -u jenkins docker ps`, `sudo -u jenkins aws sts get-caller-identity` (works with an instance role).

## Part C: Jenkins configuration

1. **Global environment variables** (Manage Jenkins > System > Global properties > Environment variables):

   | Name | Value |
   |---|---|
   | `AWS_ACCOUNT_ID` | your 12-digit account ID |
   | `AWS_REGION` | `ap-south-1` (or your region) |
   | `AWS_CREDENTIALS_ID` | only if not using an instance role: the id from step 3 |
   | `GITOPS_REPO_URL` | later, for the GitOps step: `https://github.com/<user>/icollect-gitops.git` |
   | `GITOPS_CREDENTIALS_ID` | later: id of a username and GitHub token credential with write access to that repo |

2. **Shared library** (Manage Jenkins > System > Global Trusted Pipeline Libraries > Add):
   - Name: `icollect-shared-lib`, Default version: `main`
   - Retrieval method: Modern SCM, Git, your repository URL (and credentials if private)
   - Library path: `jenkins/shared-library`

3. **AWS credential (only when not on EC2)**: Manage Jenkins > Credentials > Add > "Username with password".
   Username = access key ID, Password = secret access key, ID = `aws-ecr`. Use that id in `AWS_CREDENTIALS_ID`.

4. **SonarQube (optional, skip at first):** add a SonarQube server named `sonarqube`, a "SonarQube Scanner" tool
   named `sonar-scanner`, and a webhook in SonarQube pointing to `http://<jenkins>:8080/sonarqube-webhook/`.
   Then tick `RUN_SONAR` when running a build.

## Part D: Create the jobs

Create one job per service (5 jobs). For each: New Item > Pipeline (or Multibranch Pipeline) > name, for example
`icollect-notification-service`.

- Pipeline script from SCM, SCM = Git, your repository URL, branch `*/main`
- **Script Path:** `notification-service/Jenkinsfile` (use the matching folder for each service)
- Build trigger for practice: Poll SCM with `H/5 * * * *`. (A GitHub webhook needs Jenkins reachable
  from the internet, which you should avoid on a lab instance.)

Start with `notification-service` (no Maven, fastest) to prove Jenkins, Docker, AWS and ECR work, then do
the Java services, then `reporting-service` and `web-dashboard`.

## Part E: Run and check

Click Build with Parameters (the first run may show only Build; the parameters appear after one run).
Expected console, abbreviated:

```
Service: notification-service (node)
Image  : 123456789012.dkr.ecr.ap-south-1.amazonaws.com/icollect/notification-service:a1b2c3d4
[Build and test]   # tests 3 ... # pass 3
[Docker build]     Successfully built / naming to ...:a1b2c3d4
[Trivy scan]       Total: 0 (CRITICAL: 0)
[Push to ECR]      Login Succeeded ... a1b2c3d4: digest: sha256:... size: ...
SUCCESS: notification-service -> 123456789012.dkr.ecr.ap-south-1.amazonaws.com/icollect/notification-service:a1b2c3d4
```

Verify in AWS:

```bash
aws ecr describe-images --repository-name icollect/notification-service --region ap-south-1 \
    --query 'imageDetails[].[imageTags[0],imagePushedAt,imageScanStatus.status]' --output table
```
Also check the ECR console: the image is there, scan on push has results. Run the build a second time on the same commit and
confirm it prints that the tag already exists and still succeeds.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `AWS_ACCOUNT_ID is not set` | Global variable missing | Part C step 1 |
| `docker: permission denied` | jenkins user not in docker group | `sudo usermod -aG docker jenkins && sudo systemctl restart jenkins` |
| `Library icollect-shared-lib not found` | Library name, version or path wrong | Check name, `main`, and path `jenkins/shared-library` |
| `mvn: command not found` | Tools not installed on the Jenkins machine | Run `setup-tools-ubuntu.sh` |
| `ensurepip is not available` | `python3-venv` missing | `sudo apt-get install python3-venv` |
| `Unable to locate credentials` | No instance role and no credential id | Attach the role, or set `AWS_CREDENTIALS_ID` |
| `AccessDeniedException` on ECR | Policy not attached, or account or region placeholders wrong | Re-check `ecr-push-policy.json` ARNs |
| `ECR repository ... not found` | Repositories not created | Run `create-ecr-repos.sh` in the same region |
| Trivy fails the build | Fixable CRITICAL CVE in the base image | Rebuild on a newer base image tag, or review the finding. Do not just disable the scan |
| First Trivy run is slow | Downloading the vulnerability database | Normal, it is cached in the `trivy-cache` Docker volume |
| Out of memory or disk | Instance too small | Use t3.medium or larger, run `docker system prune -af` |

## Known limits (and what you would improve)

- Java builds compile twice (`mvn verify`, then again inside the Dockerfile). Acceptable for learning, but
  production pipelines usually build the jar once and copy it into the image.
- The monorepo triggers a job for every commit. Add path filters so only changed services build.
- Repositories are created by a script. Terraform will replace this in the infrastructure step.
- Nexus (artifact repository) is not in this pipeline. It adds a server to run; add it later if you want it.
- The Groovy library was compile-checked and the Node and Python build commands were run here, but the
  whole pipeline was not run in a real Jenkins server, and Docker was not available in the authoring environment.

## Interview talking points

- One shared pipeline, thin Jenkinsfiles: change a gate once and every service gets it.
- Build once: the pipeline builds one immutable image per commit, tagged with the git SHA.
- Idempotent and safe: re-running the same commit does not break on immutable tags, and credentials never
  appear in the image or the Jenkinsfile.
- Instance role over access keys when running on EC2.
- Gates: tests, optional Sonar quality gate, Trivy on CRITICAL.
