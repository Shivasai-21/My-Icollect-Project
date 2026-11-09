#!/usr/bin/env bash
# Installs the tools the pipeline calls on the Jenkins machine (Ubuntu 22.04 or 24.04).
# Jenkins itself: install it from the official page, https://www.jenkins.io/doc/book/installing/linux/
set -euo pipefail

sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven git curl unzip jq python3 python3-venv python3-pip docker.io

# Node.js 20 (the Ubuntu package is too old)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# AWS CLI v2
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip
unzip -q -o /tmp/awscliv2.zip -d /tmp
sudo /tmp/aws/install --update

# The jenkins user must be allowed to run Docker
if id jenkins >/dev/null 2>&1; then
  sudo usermod -aG docker jenkins
  sudo systemctl restart jenkins
else
  echo "Jenkins is not installed yet. After installing it run:"
  echo "  sudo usermod -aG docker jenkins && sudo systemctl restart jenkins"
fi

echo "--- versions ---"
java -version 2>&1 | head -1; mvn -version | head -1; node -v; python3 --version; docker --version; aws --version
