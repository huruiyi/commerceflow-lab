FROM node:26.13.1-alpine3.24 AS build
COPY --from=simulator-admin . /workspace/web/packages/simulator-admin/
WORKDIR /workspace/simulators/warehouse-platform/web
COPY package*.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run format:check && npm run build

FROM nginx:stable-alpine
COPY default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/simulators/warehouse-platform/web/dist /usr/share/nginx/html
EXPOSE 80
