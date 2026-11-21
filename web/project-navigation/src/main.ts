import { createApp, defineComponent, h } from 'vue'
import { createRouter, createWebHashHistory, RouterView } from 'vue-router'
import App from './App.vue'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', alias: '/cards', name: 'cards', component: App },
    { path: '/overview', name: 'overview', component: App },
    { path: '/group/:group', name: 'group', component: App },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

createApp(defineComponent({ render: () => h(RouterView) })).use(router).mount('#app')
