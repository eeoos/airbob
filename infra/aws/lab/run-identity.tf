resource "terraform_data" "run_identity" {
  input = {
    run_id                 = var.run_id
    resource_fencing_token = var.fencing_token
  }
}
