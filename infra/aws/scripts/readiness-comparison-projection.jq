. as $receipt |
{
  schemaVersion: 1,
  executionCode: .executionCode,
  dataset: .dataset,
  bundle: {
    commit: .bundle.commit,
    archiveSha256: .bundle.archiveSha256,
    manifestVersionId: .bundle.manifestVersionId,
    manifestSha256: .bundle.manifestSha256
  },
  images: .images,
  ami: .actual.ami,
  databaseShape: (
    .actual.rds
    | del(.identifier, .resourceId, .parameterGroups)
    + {
        parameterGroup: {
          count: ($receipt.actual.rds.parameterGroups | length),
          family: $receipt.actual.rdsParameterGroupFamily
        }
      }
  ),
  topology: {
    mode: .topology.mode,
    policy: .topology.policy,
    dnsMode: .topology.dnsMode,
    ingress: {
      publicRestricted: (.topology.dnsMode == "direct-only"),
      prefixLength: (if .topology.dnsMode == "direct-only" then 32 else 0 end)
    },
    cacheEnabled: .topology.cacheEnabled,
    loadGeneratorEnabled: .topology.loadGeneratorEnabled,
    alb: (
      .actual.alb.shape
      | del(.arn, .dnsName, .securityGroups)
      | .availabilityZones = (.availabilityZones | sort)
      | .securityGroupCount = ($receipt.actual.alb.shape.securityGroups | length)
      | .ingress = (
          $receipt.actual.alb.observedIngress[0]
          | {
              isEgress,
              ipProtocol,
              fromPort,
              toPort,
              ipv4PrefixLength: (.cidrIpv4 | split("/")[1] | tonumber),
              hasIpv6: (.cidrIpv6 != null),
              hasPrefixList: (.prefixListId != null),
              hasReferencedGroup: (.referencedGroupId != null)
            }
        )
    )
  },
  autoScalingCapacity: (.actual.autoScalingGroup | del(.name)),
  dataBootstrapProjectionSha256: .bootstrap.dataProjectionSha256,
  networkClearanceProjectionSha256: .networkClearance.projectionSha256,
  smoke: .smoke
}
