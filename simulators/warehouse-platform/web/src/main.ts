import { createApp } from 'vue';
import { installAdminGrid } from '@commerceflow/simulator-admin/vxe';
import '@commerceflow/simulator-admin/style.css';
import App from './App.vue';
import './style.css';

const app = createApp(App);
installAdminGrid(app);
app.mount('#app');
