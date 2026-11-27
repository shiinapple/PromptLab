import { createApp } from 'vue'
import '../styles/tokens.css'
import App from './App.vue'
import { i18n, initLocale } from '../i18n/locale'

initLocale(location.search)
createApp(App).use(i18n).mount('#app')
