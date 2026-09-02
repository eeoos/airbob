resource "aws_instance" "this" {
  ami                         = var.ami_id
  instance_type               = "c6i.xlarge"
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [var.security_group_id]
  iam_instance_profile        = var.instance_profile_name
  associate_public_ip_address = true
  source_dest_check           = true
  monitoring                  = true
  user_data                   = var.user_data
  user_data_replace_on_change = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    encrypted             = true
    delete_on_termination = true
    volume_type           = "gp3"
    volume_size           = 30
  }

  volume_tags = merge(var.tags, { Name = "${var.name_prefix}-loadgen-root", Service = "loadgen" })

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-loadgen"
    Service = "loadgen"
  })
}
