/**
 * 智能分页页码：当前页前后各 around 页 + 首尾页，中间用 '...' 折叠，避免页数过多时全部平铺。
 * @param {number} current 当前页码（1 起）
 * @param {number} total 总页数
 * @param {number} around 当前页前后展示的页数
 * @returns {Array<number|'...'>}
 */
export function smartPageList(current, total, around = 2) {
  const t = Math.max(1, total | 0)
  const c = Math.min(Math.max(1, current | 0), t)
  // 页数较少时全部展示
  if (t <= 2 * around + 3) return Array.from({ length: t }, (_, i) => i + 1)
  const set = new Set([1, t])
  for (let p = c - around; p <= c + around; p++) {
    if (p >= 1 && p <= t) set.add(p)
  }
  const sorted = [...set].sort((a, b) => a - b)
  const out = []
  let prev = 0
  for (const p of sorted) {
    if (p - prev > 1) out.push('...')
    out.push(p)
    prev = p
  }
  return out
}
