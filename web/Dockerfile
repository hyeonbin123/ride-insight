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
# 베이스에 남은 OS 패키지 취약점 두 건을 이 단계에서 고친 버전으로 올린다(2026-10-30). 2026-10-21에 빌드된 이 태그의
# 이미지(Alpine 3.24.3)에는 brotli-libs 1.2.0-r1(CVE-2026-111497, CRITICAL, 고친 버전 1.2.1-r0)과 curl·libcurl
# 8.22.0-r0(CVE-2026-92392, HIGH, 고친 버전 8.23.0-r0)이 들어 있고, 2026-10-30에도 태그가 같은 이미지를 가리켰다. nginx는
# 둘 다 링크하지 않아 실행 중에 닿지는 않는다(그래서 처음에는 근거를 적고 만료일을 붙여 .trivyignore로 스캔에서 뺐다).
# 하지만 제외는 만료일에 풀려 베이스가 다시 나오지 않으면 CI 이미지 검사가 실패하고, 닿지 않는다는 판단도 매번 다시 해야
# 한다. 그래서 제외 대신 패키지를 고친다. 이름을 준 세 패키지만 Alpine 저장소의 최신으로 올리므로 nginx(1.30.6, world에
# 버전이 고정됨)와 다른 패키지는 베이스 그대로다(올리기 전후 apk info -v를 비교해 세 줄만 바뀐 것을 확인했다). 빌드할 때
# Alpine 저장소에 접속해야 한다. 베이스가 고친 버전으로 다시 나오면 이 줄은 아무것도 바꾸지 않는다(그때 지워도 된다).
RUN apk upgrade --no-cache brotli-libs libcurl curl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
