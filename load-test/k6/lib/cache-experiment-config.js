// Shared request generator; AWS identity and resource checks belong to the AWS runner.
export function experimentTarget(env) {
  const require = (ok, message) => { if (!ok) throw new Error(message); };
  const scope = env.EXPERIMENT_ENVIRONMENT || 'local';
  require(['local', 'aws'].includes(scope), 'Unknown experiment environment');
  const origins = JSON.parse(env.EXPERIMENT_ORIGINS || '[]');
  require(Array.isArray(origins) && origins.length > 0 && origins.length <= 4, 'Origins required');
  let hosts = {};
  if (scope === 'local') {
    require(origins.every((v) => /^http:\/\/127\.0\.0\.1:[1-9]\d{0,4}$/.test(v)
      && Number(v.split(':').pop()) <= 65535), 'Local experiment origins must be loopback HTTP ports');
    require(!env.EXPERIMENT_HOSTS, 'Local experiments cannot override DNS');
  } else {
    // Keep the certificate name while directing traffic to the experiment ALB, without public DNS cutover.
    require(origins.length === 1 && origins[0] === 'https://api.airbob.cloud', 'AWS certificate origin required');
    hosts = JSON.parse(env.EXPERIMENT_HOSTS || '{}');
    const ip = hosts['api.airbob.cloud'];
    require(Object.keys(hosts).length === 1 && typeof ip === 'string'
      && /^(\d{1,3}\.){3}\d{1,3}$/.test(ip)
      && ip.split('.').every((part) => Number(part) <= 255)
      && !/^(0|127|169\.254)\./.test(ip), 'Verified ALB IPv4 override required');
  }
  return { scope, origins, hosts,
    limits: scope === 'local'
      ? { rate: 2000, seconds: 120, burst: 300, vus: 1000 }
      : { rate: 20000, seconds: 1800, burst: 1000, vus: 20000 } };
}
