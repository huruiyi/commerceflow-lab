FROM node:26.13.1-alpine3.24 AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration production

FROM nginx:1.31.8-alpine3.24
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/commerceflow-frontend/browser /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=5s --timeout=3s --retries=20 CMD wget -q --spider http://127.0.0.1/healthz || exit 1
