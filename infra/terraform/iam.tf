# ── IAM for SSM-based deploys ───────────────────────────────────────────────
# Two identities, two directions of trust:
#   1. The INSTANCE ROLE lets the EC2 box talk to Systems Manager — the SSM
#      agent dials OUT to AWS and waits for commands, which is why no inbound
#      port needs to be open for deploys. (The agent ships preinstalled on the
#      STANDARD AL2023 AMI — not the minimal variant, which is what broke
#      deploys until main.tf pinned the AMI properly.)
#   2. The CI ROLE lets GitHub Actions ask SSM to run a command on the box,
#      assumed via OIDC so no long-lived key exists anywhere.
# Neither identity can do anything else — least privilege is the whole point.

# EC2 instances can't hold credentials directly; they assume a role. This
# trust policy says "only the EC2 service may wear this role".
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

# AWS-managed policy with exactly what the SSM agent needs (register with
# SSM, receive commands, report output). Attaching a managed policy beats
# hand-writing the ~10 actions it contains.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# EC2 can't reference a role directly — it needs this wrapper. Historical
# AWS quirk; think of the instance profile as the role's "EC2 adapter".
resource "aws_iam_instance_profile" "shophub" {
  name = "shophub-instance-profile"
  role = aws_iam_role.instance.name
}

# ── CI identity for GitHub Actions (OIDC, no long-lived key) ────────────────
# This replaced an `aws_iam_user` whose access key was created by hand in the
# console and pasted into GitHub repo secrets. That worked, but the key was a
# permanent credential living in two places Terraform couldn't see, which
# caused two distinct problems:
#
#   1. `terraform destroy` failed on the user, because AWS refuses to delete
#      an IAM user that still holds an access key it doesn't manage. The
#      destroy died halfway, after the instance and EIP were already gone.
#   2. Had it SUCCEEDED, that would have been worse — the GitHub secrets would
#      have silently become invalid with no way to recover them, because the
#      key material only ever existed in the console at creation time.
#
# OIDC removes the credential rather than protecting it. GitHub signs a
# short-lived JWT describing the workflow run; AWS trusts that signature and
# exchanges it for ~1-hour STS credentials at job start. Nothing long-lived
# exists to leak, rotate, or lose — and the whole identity is now Terraform-
# managed and genuinely disposable.
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = []

  tags = {
    Project = "shophub"
  }
}

resource "aws_iam_role" "ci" {
  name = "shophub-ci"

  # The trust policy is the security boundary, and `sub` is the part that
  # matters. Without a condition on it, ANY GitHub repository in the world
  # could assume this role — the provider only proves "GitHub Actions issued
  # this token", not "your repo issued it".
  #
  # Pinned to the main branch specifically, not `repo:owner/name:*`: the
  # wildcard would also match pull_request runs, which on a public repo can be
  # opened by anyone. The deploy job is already main-only (see ci.yml), so
  # nothing legitimate is lost by saying so here too — defence in depth, where
  # the workflow condition and the trust policy have to BOTH be wrong before
  # anything bad happens.
  #
  # Note the repository casing: GitHub emits the canonical name in `sub`, and
  # StringEquals is case-sensitive. "shophub" would silently never match.
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
        # May send commands — but only to instances tagged Name=shophub.
        # The tag condition is what keeps this role useless against any other
        # EC2 instance in the account, and it keeps working across
        # destroy/re-apply cycles because the tag survives where instance
        # IDs don't.
        Sid      = "SendDeployCommandToTaggedInstance"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ec2:*:*:instance/*"
        Condition = {
          StringEquals = { "ssm:resourceTag/Name" = "shophub" }
        }
      },
      {
        # SendCommand also needs permission on the DOCUMENT being run —
        # locked to AWS's stock "run a shell script" document, so this user
        # can't invoke anything more exotic.
        Sid      = "UseRunShellScriptDocument"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ssm:*:*:document/AWS-RunShellScript"
      },
      {
        # Read back status/stdout/stderr of commands it sent, so the Actions
        # log can show the deploy result.
        Sid      = "ReadCommandResults"
        Effect   = "Allow"
        Action   = "ssm:GetCommandInvocation"
        Resource = "*"
      },
      {
        # Resolve "the running instance tagged shophub" to an instance ID.
        # DescribeInstances doesn't support resource-level scoping, so this
        # is account-wide read-only metadata — acceptable.
        Sid      = "FindInstanceByTag"
        Effect   = "Allow"
        Action   = "ec2:DescribeInstances"
        Resource = "*"
      }
    ]
  })
}
