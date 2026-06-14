import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import router from './router';
import { setUnauthenticatedRedirect } from '@/auth/session';
import './styles/index.css';

// Wire the 401 redirect hook: any unauthenticated response from the API
// client clears the in-memory session and bounces the user back to the
// login page. Registered once, on app bootstrap, BEFORE `app.mount` so
// the very first navigation can already redirect.
setUnauthenticatedRedirect(() => {
  // Use replace() so the unauthenticated URL is not preserved in history.
  // We deliberately do NOT pass a `redirect` query parameter — that would
  // leak the previous route to the login page and gives no real benefit
  // for a single-page admin tool.
  void router.replace('/login');
});

const app = createApp(App);
app.use(ElementPlus);
app.use(router);
app.mount('#app');
