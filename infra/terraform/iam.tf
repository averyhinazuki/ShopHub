# ── IAM for SSM-based deploys ───────────────────────────────────────────────
# Two identities, two directions of trust:
#   1. The INSTANCE ROLE lets the EC2 box talk to Systems Manager — the SSM
#      agent (preinstalled on AL2023) dials OUT to AWS and waits for commands,
#      which is why no inbound port needs to be open for deploys.
#   2. The CI USER lets GitHub Actions ask SSM to run a command on the box.
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

# ── CI user for GitHub Actions ──────────────────────────────────────────────
# Deliberately NOT creating an aws_iam_access_key resource here: the secret
# key would land in terraform.tfstate in plaintext. Create the access key by
# hand in the console and paste it straight into GitHub repo secrets — the
# one place it needs to exist.
resource "aws_iam_user" "ci" {
  name = "shophub-ci"

  tags = {
    Project = "shophub"
  }
}

resource "aws_iam_user_policy" "ci_deploy" {
  name = "shophub-ci-deploy"
  user = aws_iam_user.ci.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # May send commands — but only to instances tagged Name=shophub.
        # The tag condition is what keeps this key useless against any other
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
