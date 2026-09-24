output "instance_id" {
  value = aws_instance.this.id
}

output "public_ip" {
  value = aws_instance.this.public_ip
}

output "contract" {
  value = {
    instance_type = aws_instance.this.instance_type
    public_ipv4   = aws_instance.this.associate_public_ip_address
    monitoring    = aws_instance.this.monitoring
  }
}
