#!/bin/bash
# Let's Encrypt 초기 인증서 발급 스크립트
# Oracle Cloud VM에서 실행

set -e

DOMAIN="api.airbob.cloud"
EMAIL="${1:-admin@airbob.cloud}"

echo "=== Let's Encrypt SSL 인증서 발급 ==="
echo "도메인: $DOMAIN"
echo "이메일: $EMAIL"
echo ""

# 1. 초기 nginx 설정 사용
echo "[1/5] 초기 nginx 설정 적용..."
cp nginx/conf.d/default.conf.init nginx/conf.d/default.conf

# 2. 볼륨 생성
echo "[2/5] Docker 볼륨 생성..."
docker volume create --name airbob_certbot-www 2>/dev/null || true
docker volume create --name airbob_certbot-certs 2>/dev/null || true

# 3. nginx 임시 시작
echo "[3/5] nginx 컨테이너 시작..."
docker run -d --name nginx-init \
    -p 80:80 \
    -v $(pwd)/nginx/nginx.conf:/etc/nginx/nginx.conf:ro \
    -v $(pwd)/nginx/conf.d:/etc/nginx/conf.d:ro \
    -v airbob_certbot-www:/var/www/certbot \
    nginx:1.25-alpine

sleep 3

# 4. 인증서 발급
echo "[4/5] Let's Encrypt 인증서 발급 중..."
docker run --rm \
    -v airbob_certbot-www:/var/www/certbot \
    -v airbob_certbot-certs:/etc/letsencrypt \
    certbot/certbot:arm64v8-latest \
    certonly --webroot \
    --webroot-path=/var/www/certbot \
    --email $EMAIL \
    --agree-tos \
    --no-eff-email \
    -d $DOMAIN

# 5. 정리 및 SSL 설정 복원
echo "[5/5] 정리 중..."
docker stop nginx-init && docker rm nginx-init

# SSL 설정 복원
cat > nginx/conf.d/default.conf << 'NGINX_CONF'
# Upstream for Spring Boot App
upstream app {
    server app:8080;
    keepalive 32;
}

# CORS 허용 도메인 (프론트엔드)
map $http_origin $cors_origin {
    default "";
    "~^https://.*\.vercel\.app$" $http_origin;
    "https://airbob.cloud" $http_origin;
    "https://www.airbob.cloud" $http_origin;
}

# HTTP → HTTPS 리다이렉트
server {
    listen 80;
    server_name api.airbob.cloud;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# HTTPS API 서버
server {
    listen 443 ssl;
    http2 on;
    server_name api.airbob.cloud;

    ssl_certificate /etc/letsencrypt/live/api.airbob.cloud/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.airbob.cloud/privkey.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_timeout 1d;
    ssl_session_cache shared:SSL:10m;
    ssl_session_tickets off;

    add_header Strict-Transport-Security "max-age=63072000" always;

    location /health {
        access_log off;
        return 200 "healthy\n";
        add_header Content-Type text/plain;
    }

    location / {
        if ($request_method = 'OPTIONS') {
            add_header 'Access-Control-Allow-Origin' $cors_origin always;
            add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, PATCH, DELETE, OPTIONS' always;
            add_header 'Access-Control-Allow-Headers' 'DNT,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization,Cookie' always;
            add_header 'Access-Control-Allow-Credentials' 'true' always;
            add_header 'Access-Control-Max-Age' 86400;
            add_header 'Content-Type' 'text/plain charset=UTF-8';
            add_header 'Content-Length' 0;
            return 204;
        }

        add_header 'Access-Control-Allow-Origin' $cors_origin always;
        add_header 'Access-Control-Allow-Credentials' 'true' always;
        add_header 'Access-Control-Expose-Headers' 'Content-Length,Content-Range' always;

        proxy_pass http://app;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_set_header Cookie $http_cookie;
        proxy_pass_header Set-Cookie;
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 4k;
    }
}
NGINX_CONF

echo ""
echo "=== 완료! ==="
echo ""
echo "DNS 설정:"
echo "  api.airbob.cloud  → A 레코드 → Oracle VM IP"
echo "  airbob.cloud      → Vercel 설정 (CNAME 또는 A)"
echo "  www.airbob.cloud  → Vercel 설정 (CNAME)"
echo ""
echo "프론트엔드 API 베이스 URL:"
echo "  https://api.airbob.cloud"
echo ""
echo "전체 서비스 시작:"
echo "  docker compose -f docker-compose.prod.yml --env-file .env.prod up -d"
