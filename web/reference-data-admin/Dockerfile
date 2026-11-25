FROM node:26.13.1-alpine3.24 AS build
COPY --from=simulator-admin . /workspace/web/packages/simulator-admin/
WORKDIR /workspace/web/reference-data-admin
COPY package*.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run format:check && npm run build

FROM nginx:1.31.8-alpine3.24
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/web/reference-data-admin/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=5s --timeout=3s --retries=20 CMD wget -q --spider http://127.0.0.1/healthz || exit 1
