# Single security group for the instance — everything runs on one box.
resource "aws_security_group" "shophub" {
  name        = "shophub-sg"
  description = "ShopHub EC2 instance: SSH from admin IP, HTTP/HTTPS from anywhere"
  vpc_id      = data.aws_vpc.default.id

  tags = {
    Name    = "shophub-sg"
    Project = "shophub"
  }
}

# SSH — restricted to var.ssh_allowed_cidr.
resource "aws_security_group_rule" "ssh_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.ssh_allowed_cidr]
  description       = "SSH from admin IP only"
}

# HTTP — public; terminated by nginx in the compose stack.
resource "aws_security_group_rule" "http_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTP from anywhere"
}

# HTTPS — opened ahead of TLS so enabling it later is config-only (see
# docs/DEVOPS_ROADMAP.md).
resource "aws_security_group_rule" "https_in" {
  type              = "ingress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTPS from anywhere (reserved for future TLS)"
}

# Egress — all outbound (GHCR pulls, OS package repos).
resource "aws_security_group_rule" "all_out" {
  type              = "egress"
  security_group_id = aws_security_group.shophub.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "All outbound"
}
