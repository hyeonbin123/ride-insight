# Build the static site, serve it with nginx and proxy /api to the server container.
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY . .
RUN npm run build

# 서빙 단계 베이스는 nginx stable 가지(1.30, 2026-10-29 기준 1.30.6)다. 전에 쓰던 1.27은 2025-06-24에 지원이 끝난 가지라
# 보안 수정을 받지 못했다(2026-10-19 코드 검토). 새 기능과 동작 변경이 먼저 들어가는 mainline(1.31) 대신 수정만 받는 stable을
# 쓰고, 가지 태그라 다시 빌드하면 그 가지의 최신 패치를 받는다. 공식 이미지에 sub_filter(CSP nonce)와 gzip 모듈이 들어 있다
# (nginx -V로 확인).
FROM nginx:1.30-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
