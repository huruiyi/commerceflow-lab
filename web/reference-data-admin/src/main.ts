import { createApp } from 'vue';
import Antd from 'ant-design-vue';
import { installAdminGrid, setAdminLocale } from '@commerceflow/simulator-admin/vxe';
import 'ant-design-vue/dist/reset.css';
import '@commerceflow/simulator-admin/style.css';
import App from './App.vue';
import './style.css';

const app = createApp(App).use(Antd);
installAdminGrid(app);
setAdminLocale('zh-CN');
app.mount('#app');
