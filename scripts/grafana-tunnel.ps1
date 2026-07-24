<#
.SYNOPSIS
  Opens a local tunnel to the ShopHub Grafana (or Prometheus) UI on the EC2 box.

.DESCRIPTION
  Grafana is deliberately NOT public: docker-compose.cloud.yml binds it to
  127.0.0.1:3000 on the instance, and the security group only opens 22/80/443.
  Access goes through AWS SSM port forwarding instead, which needs no inbound
  port, no SSH key, and is IAM-authenticated + logged in CloudTrail.

  The instance ID is looked up by tag on every run, because the box is
  disposable -- a `terraform apply` that changes user_data replaces it and the
  ID changes. Never hardcode it.

  Keep this window open while you use the UI; Ctrl-C closes the tunnel.

.PARAMETER Prometheus
  Tunnel to the raw Prometheus UI (9090) instead of Grafana (3000).

.PARAMETER LocalPort
  Override the local port (defaults to the same as the remote port).

.EXAMPLE
  .\scripts\grafana-tunnel.ps1
  Then open http://localhost:3000 (admin / grafana_admin_password from terraform.tfvars).

.EXAMPLE
  .\scripts\grafana-tunnel.ps1 -Prometheus
  Then open http://localhost:9090

.NOTES
  Requires the AWS CLI and the SSM Session Manager plugin:
  https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
#>
[CmdletBinding()]
param(
    [switch] $Prometheus,
    [int]    $LocalPort = 0
)

$ErrorActionPreference = 'Stop'

$Region     = 'us-east-1'   # the stack lives here; the local CLI default is ap-northeast-1
$RemotePort = if ($Prometheus) { 9090 } else { 3000 }
if ($LocalPort -eq 0) { $LocalPort = $RemotePort }

if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw "aws CLI not found on PATH."
}

Write-Host "Looking up the running shophub instance in $Region..." -ForegroundColor Cyan
$instanceId = (
    aws ec2 describe-instances `
        --region $Region `
        --filters "Name=tag:Name,Values=shophub" "Name=instance-state-name,Values=running" `
        --query "Reservations[].Instances[].InstanceId" `
        --output text
).Trim()

if ([string]::IsNullOrWhiteSpace($instanceId) -or $instanceId -eq 'None') {
    throw "No running instance tagged Name=shophub in $Region. Is the stack up?"
}
if ($instanceId -match '\s') {
    throw "Multiple running shophub instances found ($instanceId). Resolve that before tunnelling."
}

$what = if ($Prometheus) { 'Prometheus' } else { 'Grafana' }
Write-Host "$what on $instanceId : forwarding remote $RemotePort -> http://localhost:$LocalPort" -ForegroundColor Green
if (-not $Prometheus) {
    Write-Host "Login: admin / the grafana_admin_password in infra/terraform/terraform.tfvars" -ForegroundColor DarkGray
}
Write-Host "Leave this window open. Ctrl-C to close the tunnel.`n" -ForegroundColor DarkGray

# Parameters must be JSON; values are arrays of strings.
$params = "{`"portNumber`":[`"$RemotePort`"],`"localPortNumber`":[`"$LocalPort`"]}"

aws ssm start-session `
    --region $Region `
    --target $instanceId `
    --document-name AWS-StartPortForwardingSession `
    --parameters $params
