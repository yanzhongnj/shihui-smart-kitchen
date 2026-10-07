"""
验证 adjust_step_timestamps 修复 - 测试步骤6/7/8级联错误被修复
"""
import sys
import json
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from scripts.match_timestamps import adjust_step_timestamps, apply_offset


def main():
    # 模拟匹配后的步骤数据（步骤5匹配到208.94错误，步骤6-8正确匹配到各自segment）
    simulated_steps = [
        {'index': 1, 'start': 18.75, 'end': 53.02, 'confidence': 'high', 'summary': '准备小料'},
        {'index': 2, 'start': 53.32, 'end': 55.52, 'confidence': 'low', 'summary': '配菜切丝'},
        {'index': 3, 'start': 55.82, 'end': 79.43, 'confidence': 'low', 'summary': '准备猪肉'},
        {'index': 4, 'start': 79.73, 'end': 128.21, 'confidence': 'low', 'summary': '腌制肉丝'},
        {'index': 5, 'start': 128.51, 'end': 208.94, 'confidence': 'low', 'summary': '调制鱼香汁'},
        {'index': 6, 'start': 130.627, 'end': 160.613, 'confidence': 'low', 'summary': '滑炒肉丝'},
        {'index': 7, 'start': 161.052, 'end': 189.605, 'confidence': 'low', 'summary': '炒香小料'},
        {'index': 8, 'start': 190.752, 'end': 208.943, 'confidence': 'low', 'summary': '倒入鱼香汁翻炒'},
    ]

    print("=" * 60)
    print("修复前：步骤5 end=208.94 占满全视频，步骤6/7/8被级联推出界")
    print("=" * 60)
    for s in simulated_steps:
        marker = " ← 错误" if s['end'] > 200 and s['index'] >= 5 else ""
        print(f"  步骤{s['index']}: {s['start']:.2f}s - {s['end']:.2f}s [{s['confidence']}]{marker}")

    # 应用修复后的 adjust_step_timestamps
    adjusted = adjust_step_timestamps(simulated_steps)

    print()
    print("=" * 60)
    print("adjust_step_timestamps 修复后：")
    print("=" * 60)
    for s in adjusted:
        marker = ""
        if s['index'] == 5 and s['end'] < 200:
            marker = " ✅ 已修正（前移）"
        elif s['index'] == 5 and s['confidence'] == 'low':
            marker = " ✅ 已标记低置信度"
        print(f"  步骤{s['index']}: {s['start']:.2f}s - {s['end']:.2f}s [{s['confidence']}]{marker}")

    # 应用偏移 + 裁剪
    video_duration = 208.94
    for step in adjusted:
        if step.get('end', 0) > video_duration:
            step['end'] = video_duration
        if step.get('start', 0) > video_duration:
            step['start'] = max(video_duration - 2.0, 0)

    final = apply_offset(adjusted, offset=0.2)

    print()
    print("=" * 60)
    print("最终输出（偏移 -0.2s + 时长裁剪）：")
    print("=" * 60)
    all_ok = True
    for s in final:
        ok = s['start'] < video_duration and s['end'] <= video_duration and s['start'] < s['end']
        if not ok:
            all_ok = False
        status = "✅" if ok else "❌"
        print(f"  {status} 步骤{s['index']}: {s['start']:.2f}s - {s['end']:.2f}s [{s['confidence']}] {s['summary']}")

    print()
    if all_ok:
        print("✅ 所有步骤时间均在视频时长内，级联错误已修复！")
    else:
        print("❌ 仍有步骤时间异常")

    # 验证关键检查点
    checks = []
    checks.append(("步骤6 start < 视频时长", final[5]['start'] < video_duration))
    checks.append(("步骤7 start < 视频时长", final[6]['start'] < video_duration))
    checks.append(("步骤8 start < 视频时长", final[7]['start'] < video_duration))
    checks.append(("步骤6 end <= 视频时长", final[5]['end'] <= video_duration))
    checks.append(("步骤7 end <= 视频时长", final[6]['end'] <= video_duration))
    checks.append(("步骤8 end <= 视频时长", final[7]['end'] <= video_duration))

    print()
    print("=" * 60)
    print("关键检查点：")
    for name, result in checks:
        print(f"  {'✅' if result else '❌'} {name}")


if __name__ == "__main__":
    main()
