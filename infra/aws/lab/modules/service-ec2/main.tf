resource "aws_instance" "this" {
  for_each = var.hosts

  ami                         = var.ami_id
  instance_type               = each.value.instance_type
  subnet_id                   = each.value.subnet_id
  vpc_security_group_ids      = each.value.security_group_ids
  iam_instance_profile        = each.value.instance_profile_name
  associate_public_ip_address = false
  source_dest_check           = true
  user_data                   = each.value.user_data
  user_data_replace_on_change = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  credit_specification {
    cpu_credits = "unlimited"
  }

  root_block_device {
    encrypted             = true
    delete_on_termination = true
    volume_type           = "gp3"
    volume_size           = each.value.volume_size
  }

  volume_tags = merge(var.tags, { Name = "${var.name_prefix}-${each.key}-root" })

  tags = merge(
    var.tags,
    {
      Name    = "${var.name_prefix}-${each.key}"
      Service = each.key
    },
    each.value.monitoring ? { Monitoring = "node-exporter" } : {},
  )
}
