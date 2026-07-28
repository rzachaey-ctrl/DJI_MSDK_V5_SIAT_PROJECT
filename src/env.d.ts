// Environment variable definition
// https://cn.vitejs.dev/guide/env-and-mode.html#env-files

interface ImportMetaEnv {
  VITE_APP_ENVIRONMENT: 'DEV' | 'STAG' | 'UAT' | 'PROD',
  // api gateway
  VITE_APP_APIGATEWAY_BACKEND_HOST: string
  VITE_DJI_APP_ID?: string
  VITE_DJI_APP_KEY?: string
  VITE_DJI_APP_LICENSE?: string
  VITE_API_BASE_URL?: string
  VITE_WEBSOCKET_URL?: string
  // More environment variables...
}
