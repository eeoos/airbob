resource "aws_instance" "this" {
  ami                         = var.ami_id
  instance_type               = "t3.micro"
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [var.security_group_id]
  iam_instance_profile        = var.instance_profile_name
  associate_public_ip_address = false
  source_dest_check           = false
  user_data                   = var.user_data
  user_data_replace_on_change = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  credit_specification {
    cpu_credits = "unlimited"
  }

  lifecycle {
    # Associating the separately managed EIP makes DescribeInstances report a
    # public address even though the subnet and launch request both disable
    # automatic public IPv4 assignment. The provider then refreshes this
    # ForceNew attribute to true between Lab phases and would replace the NAT.
    ignore_changes = [associate_public_ip_address]
  }

  root_block_device {
    encrypted             = true
    delete_on_termination = true
    volume_type           = "gp3"
    volume_size           = 8
  }

  volume_tags = merge(var.tags, { Name = "${var.name_prefix}-nat-root" })

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-nat"
    Service = "nat"
  })
}

resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-nat" })
}
