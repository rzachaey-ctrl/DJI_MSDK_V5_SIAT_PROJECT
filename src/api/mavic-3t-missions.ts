import request, { IWorkspaceResponse } from '/@/api/http/request'

const API_PREFIX = '/api/v1/msdk/missions'

export type MsdkMissionStatus =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'UPLOADING_TO_AIRCRAFT'
  | 'READY'
  | 'EXECUTING'
  | 'PAUSED'
  | 'INTERRUPTED'
  | 'FINISHED'
  | 'FAILED'

export interface MsdkMission {
  taskId: string
  originalFileName: string
  fileSize: number
  status: MsdkMissionStatus
  waylineId?: number
  waypointIndex?: number
  message?: string
  createdAt: number
  updatedAt: number
  downloadUrl: string
}

interface MsdkMissionWire {
  task_id: string
  original_file_name: string
  file_size: number
  status: MsdkMissionStatus
  wayline_id?: number
  waypoint_index?: number
  message?: string
  created_at: number
  updated_at: number
  download_url: string
}

export interface MsdkMissionCommand {
  request_id: string
  type: string
  task_id: string
  timestamp: number
}

export async function uploadMsdkMission (
  file: File
): Promise<IWorkspaceResponse<MsdkMission>> {
  const form = new FormData()
  form.append('file', file)
  const response = await request.post(API_PREFIX, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  return mapMissionResponse(response.data)
}

export async function getMsdkMission (
  taskId: string
): Promise<IWorkspaceResponse<MsdkMission>> {
  const response = await request.get(`${API_PREFIX}/${taskId}`)
  return mapMissionResponse(response.data)
}

export async function listMsdkMissions (
  limit = 20
): Promise<IWorkspaceResponse<MsdkMission[]>> {
  const response = await request.get(API_PREFIX, { params: { limit } })
  const payload = response.data as IWorkspaceResponse<unknown>
  if (payload.code !== 0) {
    return payload as IWorkspaceResponse<MsdkMission[]>
  }
  if (!Array.isArray(payload.data) || !payload.data.every(isMissionWire)) {
    throw new Error('Mission list response has an invalid data payload.')
  }
  return { ...payload, data: payload.data.map(mapMission) }
}

export async function sendMsdkMissionAction (
  taskId: string,
  action: 'prepare' | 'start' | 'pause' | 'resume' | 'stop'
): Promise<IWorkspaceResponse<MsdkMissionCommand>> {
  const response = await request.post(`${API_PREFIX}/${taskId}/${action}`)
  return response.data
}

function mapMissionResponse (
  response: IWorkspaceResponse<unknown>
): IWorkspaceResponse<MsdkMission> {
  if (response.code !== 0) {
    return response as IWorkspaceResponse<MsdkMission>
  }
  if (!isMissionWire(response.data)) {
    throw new Error('Mission response has an invalid data payload.')
  }
  return { ...response, data: mapMission(response.data) }
}

function isMissionWire (value: unknown): value is MsdkMissionWire {
  if (!value || typeof value !== 'object') return false
  const mission = value as Partial<MsdkMissionWire>
  return typeof mission.task_id === 'string' &&
    typeof mission.original_file_name === 'string' &&
    typeof mission.file_size === 'number' &&
    typeof mission.status === 'string' &&
    typeof mission.created_at === 'number' &&
    typeof mission.updated_at === 'number' &&
    typeof mission.download_url === 'string'
}

function mapMission (mission: MsdkMissionWire): MsdkMission {
  return {
    taskId: mission.task_id,
    originalFileName: mission.original_file_name,
    fileSize: mission.file_size,
    status: mission.status,
    waylineId: mission.wayline_id,
    waypointIndex: mission.waypoint_index,
    message: mission.message,
    createdAt: mission.created_at,
    updatedAt: mission.updated_at,
    downloadUrl: mission.download_url
  }
}
