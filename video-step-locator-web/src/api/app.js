import request from './request'

// APP 安装包扫码下载（公开接口，无需登录）
export const appAPI = {
  // 最新版本信息 + APK 文件信息（供扫码下载页展示）
  getDownloadInfo: (platform = 'android') =>
    request.get('/app/download/info', { params: { platform } }),
}

// 生成绝对下载 URL（二维码内容）
export function buildDownloadUrl(platform = 'android', id = null) {
  const base = window.location.origin
  const path = `/api/app/download?platform=${platform}${id ? `&id=${id}` : ''}`
  return `${base}${path}`
}
