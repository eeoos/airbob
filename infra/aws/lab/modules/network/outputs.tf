output "vpc_id" {
  value = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  value = { for key, subnet in aws_subnet.public : key => subnet.id }
}

output "private_subnet_ids" {
  value = { for key, subnet in aws_subnet.private : key => subnet.id }
}

output "private_route_table_ids" {
  value = { for key, route_table in aws_route_table.private : key => route_table.id }
}

output "s3_endpoint_id" {
  value = aws_vpc_endpoint.s3.id
}
