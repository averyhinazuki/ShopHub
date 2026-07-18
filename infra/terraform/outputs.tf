output "instance_id" {
  description = "EC2 instance ID — useful for `aws ssm start-session`/console lookups."
  value       = aws_instance.shophub.id
}

output "public_ip" {
  description = "Stable Elastic IP. Open this in a browser once user-data has finished (see docs/DEPLOY.md verification steps)."
  value       = aws_eip.shophub.public_ip
}

output "ssh_command" {
  description = "Copy-pasteable SSH command using the private key you already have."
  value       = "ssh -i ~/.ssh/shophub ec2-user@${aws_eip.shophub.public_ip}"
}

output "app_url" {
  description = "URL to hit once nginx + the app are up."
  value       = "http://${aws_eip.shophub.public_ip}/"
}
