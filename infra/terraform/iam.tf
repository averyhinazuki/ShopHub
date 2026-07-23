# ── IAM for SSM-based deploys ───────────────────────────────────────────────
# Instance role: lets the EC2 box register with SSM (agent dials out, so no
# inbound port needed for deploys). CI role: lets GitHub Actions run commands
# on the box via OIDC, no long-lived key. Both are least-privilege.

# EC2 assumes this role; trust policy limits it to the EC2 service.
resource "aws_iam_role" "instance" {
  name = "shophub-instance-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project = "shophub"
  }
}

# AWS-managed policy covering exactly what the SSM agent needs.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Instance profile — the wrapper EC2 needs to reference the role.
resource "aws_iam_instance_profile" "shophub" {
  name = "shophub-instance-profile"
  role = aws_iam_role.instance.name
}

# ── CI identity for GitHub Actions (OIDC) ───────────────────────────────────
# Replaced a hand-created IAM user access key. OIDC has GitHub sign a
# short-lived JWT that AWS exchanges for ~1-hour STS credentials, so nothing
# long-lived exists to leak, rotate, or lose, and the identity is fully
# Terraform-managed.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # thumbprint_list omitted deliberately: IAM validates GitHub's certificate
  # against its own trust store and populates this field itself. Pinning it
  # produces a permanent corrective diff and breaks on certificate rotation.
  lifecycle {
    ignore_changes = [thumbprint_list]
  }

  tags = {
    Project = "shophub"
  }
}

resource "aws_iam_role" "ci" {
  name = "shophub-ci"

  # The `sub` condition is the security boundary — without it any GitHub repo
  # could assume this role. Pinned to the main branch (not a wildcard, which
  # would match attacker-opened pull_request runs on a public repo). Repository
  # casing must match GitHub's canonical `sub` exactly (case-sensitive).
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:averyhinazuki/ShopHub:ref:refs/heads/main"
        }
      }
    }]
  })

  tags = {
    Project = "shophub"
  }
}

resource "aws_iam_role_policy" "ci_deploy" {
  name = "shophub-ci-deploy"
  role = aws_iam_role.ci.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # SendCommand only to instances tagged Name=shophub. The tag condition
        # survives destroy/re-apply where instance IDs don't.
        Sid      = "SendDeployCommandToTaggedInstance"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ec2:*:*:instance/*"
        Condition = {
          StringEquals = { "ssm:resourceTag/Name" = "shophub" }
        }
      },
      {
        # SendCommand also needs the document — locked to the stock shell script.
        Sid      = "UseRunShellScriptDocument"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ssm:*:*:document/AWS-RunShellScript"
      },
      {
        # Read back command status/output for the Actions log.
        Sid      = "ReadCommandResults"
        Effect   = "Allow"
        Action   = "ssm:GetCommandInvocation"
        Resource = "*"
      },
      {
        # Resolve the tagged instance to an ID. DescribeInstances has no
        # resource-level scoping — account-wide read-only metadata.
        Sid      = "FindInstanceByTag"
        Effect   = "Allow"
        Action   = "ec2:DescribeInstances"
        Resource = "*"
      }
    ]
  })
}
