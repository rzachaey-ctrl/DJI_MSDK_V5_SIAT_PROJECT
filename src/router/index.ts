import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { ERouterName } from '/@/types/index'

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    redirect: '/' + ERouterName.PROJECT
  },
  // 首页
  {
    path: '/' + ERouterName.PROJECT,
    name: ERouterName.PROJECT,
    component: () => import('/@/pages/page-web/index.vue')
  },
  // Device console
  {
    path: '/' + ERouterName.HOME,
    name: ERouterName.HOME,
    component: () => import('/@/pages/page-web/home.vue'),
    redirect: '/' + ERouterName.DEVICES,
    children: [
      {
        path: '/' + ERouterName.DEVICES,
        name: ERouterName.DEVICES,
        component: () => import('/@/pages/page-web/projects/devices.vue')
      },
      {
        path: '/' + ERouterName.MSDK_CONTROL,
        name: ERouterName.MSDK_CONTROL,
        component: () => import('/@/pages/page-web/projects/mavic-3t-control.vue')
      },
      {
        path: '/' + ERouterName.MSDK_MISSION,
        name: ERouterName.MSDK_MISSION,
        component: () => import('/@/pages/page-web/projects/mavic-3t-missions.vue')
      }
    ]
  },
  // Device map
  {
    path: '/' + ERouterName.WORKSPACE,
    name: ERouterName.WORKSPACE,
    component: () => import('/@/pages/page-web/projects/workspace.vue'),
    redirect: '/' + ERouterName.TSA,
    children: [
      {
        path: '/' + ERouterName.TSA,
        name: ERouterName.TSA,
        component: () => import('/@/pages/page-web/projects/tsa.vue')
      }
    ]
  },
  // pilot
  {
    path: '/' + ERouterName.PILOT,
    name: ERouterName.PILOT,
    component: () => import('/@/pages/page-pilot/pilot-index.vue'),
  },
  {
    path: '/' + ERouterName.PILOT_HOME,
    component: () => import('/@/pages/page-pilot/pilot-home.vue')
  },
  {
    path: '/' + ERouterName.PILOT_MEDIA,
    component: () => import('/@/pages/page-pilot/pilot-media.vue')
  },
  {
    path: '/' + ERouterName.PILOT_LIVESHARE,
    component: () => import('/@/pages/page-pilot/pilot-liveshare.vue')
  },
  {
    path: '/' + ERouterName.PILOT_BIND,
    component: () => import('/@/pages/page-pilot/pilot-bind.vue')
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
