import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import './styles/index.css';

// Bootstrap the KylinOps Guard frontend shell.
// Business pages, router, and API client are added in later Phase 2 tasks.
const app = createApp(App);
app.use(ElementPlus);
app.mount('#app');
