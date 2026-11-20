FROM node:26.13.1-alpine3.24 AS build
COPY --from=simulator-admin . /workspace/admin-ui/
WORKDIR /workspace/platform/web
COPY package*.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run format:check && npm run build

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/platform/web/dist /usr/share/nginx/html
EXPOSE 80
