# One security group covering everything this instance needs to expose.
# A learner setup doesn't need to split this into per-tier SGs (web/app/db)
# since everything runs on one box — that split matters once app and DB are
# separate instances/services and you want the DB SG to only accept traffic
# from the app SG, not the internet.
resource "aws_security_group" "shophub" {
  name        = "shophub-sg"
  description = "ShopHub EC2 instance: SSH from admin IP, HTTP/HTTPS from anywhere"
  vpc_id      = data.aws_vpc.default.id

  tags = {
    Name    = "shophub-sg"
    Project = "shophub"
  }
}

# SSH — restricted to var.ssh_allowed_cidr. This is the one port where
# "anywhere" is a real risk (credential stuffing / botnets scanning :22
# constantly); everything else on the box is behind nginx or not
# internet-facing at all.
resource "aws_security_group_rule" "ssh_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.ssh_allowed_cidr]
  description       = "SSH from admin IP only"
}

# HTTP — open to the world; this is the whole point of running a public web
# app. nginx (inside the compose stack) is what actually terminates this.
resource "aws_security_group_rule" "http_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTP from anywhere"
}

# HTTPS — open now even though TLS isn't wired up yet (see docs/DEVOPS_ROADMAP.md,
# "later Let's Encrypt TLS"). Opening the port ahead of time means adding TLS
# later is a config-only change, no infra change / re-apply needed.
resource "aws_security_group_rule" "https_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTPS from anywhere (reserved for future TLS)"
}

# Egress — allow all outbound. The instance needs to reach GHCR (pull the
# image), the OS package repos (dnf install docker), and generally the
# internet; there's no sensitive internal network to protect it from on the
# way out, unlike a locked-down prod VPC.
resource "aws_security_group_rule" "all_out" {
  type              = "egress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "All outbound"
}
