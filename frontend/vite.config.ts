import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'PROMPT_PRACTICE_')
  const apiPort = env.PROMPT_PRACTICE_API_PORT ?? '18080'

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      proxy: {
        '^/api(?:/|\\?|$)': `http://127.0.0.1:${apiPort}`,
      },
    },
  }
})
