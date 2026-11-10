# angular-course-2022

Project implemented in Angular course of [@hakanozer](https://github.com/hakanozer). It contains basic Angular feature implementations.\

The project now targets Angular 22. The login request uses a form-encoded POST body, so the password is not placed in the URL. Browser storage contains only a user profile used for UI navigation; it is not authentication or authorization evidence. Every protected business API must still enforce authorization on its server.

To run locally:

```shell
npm install
npm start
```

Verification:

```shell
npm run build -- --configuration production
npm test -- --watch=false --browsers=ChromeHeadless
npm audit
```

## Docker quick start

```shell
docker compose up --build -d
docker compose ps
```

Open `http://localhost:14200`. The production build is served by Nginx and supports Angular client-side route fallback. The container health endpoint is `http://localhost:14200/healthz`. Stop it with `docker compose down`.
