/**
 * 智能菜谱视频步骤定位系统 - 前端交互
 * 
 * 功能：
 * - 加载本地视频和步骤 JSON 数据
 * - 点击步骤跳转（含边界保护）
 * - 实时高亮当前步骤（requestAnimationFrame + 二分查找）
 * - 低置信度步骤标记
 */

(function () {
    'use strict';

    // ---- 状态 ----
    let player = null;
    let steps = [];
    let currentActiveIndex = -1;
    let videoDuration = 0;
    let animationId = null;

    // DOM 引用
    const videoInput = document.getElementById('video-input');
    const jsonInput = document.getElementById('json-input');
    const stepsList = document.getElementById('steps-list');
    const stepsBadge = document.getElementById('steps-badge');
    const stepCount = document.getElementById('step-count');

    // ---- 初始化播放器 ----
    function initPlayer() {
        player = videojs('my-video', {
            controls: true,
            fluid: true,
            playbackRates: [0.5, 0.75, 1, 1.25, 1.5, 2],
            html5: {
                hls: { overrideNative: true },
                nativeAudioTracks: false,
                nativeVideoTracks: false,
            },
        });

        player.ready(function () {
            // 获取视频时长
            player.on('loadedmetadata', function () {
                videoDuration = player.duration();
                startHighlightLoop();
            });

            // 视频切换时重置
            player.on('loadstart', function () {
                steps = [];
                videoDuration = 0;
                currentActiveIndex = -1;
            });
        });
    }

    // ---- 文件加载 ----
    videoInput.addEventListener('change', function (e) {
        const file = e.target.files[0];
        if (!file) return;

        const url = URL.createObjectURL(file);
        if (player) {
            player.src({ type: file.type || 'video/mp4', src: url });
            player.load();
        }
    });

    jsonInput.addEventListener('change', function (e) {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = function (evt) {
            try {
                const data = JSON.parse(evt.target.result);
                loadSteps(data);
            } catch (err) {
                alert('JSON 解析失败，请检查文件格式：' + err.message);
            }
        };
        reader.readAsText(file);
    });

    // ---- 加载步骤数据 ----
    function loadSteps(data) {
        if (!data.steps || !Array.isArray(data.steps)) {
            alert('步骤数据格式无效：缺少 steps 数组');
            return;
        }

        steps = data.steps;
        videoDuration = data.duration || 0;

        // 更新 UI
        stepsBadge.textContent = steps.length + ' 步';
        stepCount.textContent = '已加载 ' + steps.length + ' 个步骤';
        renderSteps(steps);
        currentActiveIndex = -1;
    }

    // ---- 渲染步骤列表 ----
    function renderSteps(steps) {
        if (steps.length === 0) {
            stepsList.innerHTML = `
                <div class="steps-empty">
                    <div class="empty-icon">📭</div>
                    <p>该视频暂无步骤数据</p>
                </div>
            `;
            return;
        }

        const html = steps.map(function (step, index) {
            const confidenceClass = step.confidence === 'low' ? 'confidence-low' : '';
            const timeStr = formatTime(step.start) + ' - ' + formatTime(step.end);

            return `
                <div class="step-card ${confidenceClass}" 
                     data-index="${index}" 
                     onclick="window._onStepClick(${index})">
                    <div class="step-number">${index + 1}</div>
                    <div class="step-content">
                        <div class="step-summary">${escapeHtml(step.summary)}</div>
                        <div class="step-time">
                            <span class="time-icon">⏱</span>
                            ${timeStr}
                        </div>
                        <div class="step-text">${escapeHtml(step.text || '')}</div>
                    </div>
                </div>
            `;
        }).join('');

        stepsList.innerHTML = html;
    }

    // ---- 步骤点击跳转（含边界保护） ----
    window._onStepClick = function (index) {
        const step = steps[index];
        if (!step || !player) return;

        // 获取安全的跳转目标时间
        var dur = videoDuration;
        // 如果 JSON 中 duration 无效，尝试从播放器获取
        if (!dur || isNaN(dur) || dur <= 0) {
            dur = player.duration();
        }
        // 若播放器仍未返回有效时长，使用步骤自身的 end 时间
        if (!dur || isNaN(dur) || dur <= 0) {
            dur = step.end + 1.0;
        }

        // 目标时间：step.start 不能超过视频时长
        var targetTime = step.start;
        if (dur > 0) {
            targetTime = Math.min(step.start, dur - 0.1);
        }
        targetTime = Math.max(targetTime, 0);

        // 如果视频还没加载（duration 无效），先等 loadedmetadata 再 seek
        if (!player.duration() || isNaN(player.duration()) || player.duration() <= 0) {
            player.one('loadedmetadata', function () {
                player.currentTime(targetTime);
                var playPromise = player.play();
                if (playPromise && typeof playPromise.catch === 'function') {
                    playPromise.catch(function () {
                        // 浏览器自动播放策略阻止，静默处理
                    });
                }
            });
            // 确保视频开始加载
            player.load();
        } else {
            player.currentTime(targetTime);
            var playPromise = player.play();
            if (playPromise && typeof playPromise.catch === 'function') {
                playPromise.catch(function () {
                    // 浏览器自动播放策略阻止，静默处理
                });
            }
        }

        // 滚动到对应步骤
        var card = document.querySelector('.step-card[data-index="' + index + '"]');
        if (card) {
            card.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    };

    // ---- 实时高亮（requestAnimationFrame + 二分查找） ----
    function startHighlightLoop() {
        if (animationId) {
            cancelAnimationFrame(animationId);
        }
        updateActiveStep();
    }

    function updateActiveStep() {
        if (!player || steps.length === 0) {
            animationId = requestAnimationFrame(updateActiveStep);
            return;
        }

        const currentTime = player.currentTime();

        // 二分查找当前步骤
        let left = 0;
        let right = steps.length - 1;
        let foundIndex = -1;

        while (left <= right) {
            const mid = Math.floor((left + right) / 2);
            const s = steps[mid];

            if (currentTime < s.start) {
                right = mid - 1;
            } else if (currentTime > s.end) {
                left = mid + 1;
            } else {
                foundIndex = mid;
                break;
            }
        }

        // 仅当步骤变化时才操作 DOM
        if (foundIndex !== currentActiveIndex) {
            // 移除旧高亮
            if (currentActiveIndex >= 0) {
                const oldEl = document.querySelector(
                    `.step-card[data-index="${currentActiveIndex}"]`
                );
                if (oldEl) oldEl.classList.remove('active');
            }

            // 添加新高亮
            if (foundIndex >= 0) {
                const newEl = document.querySelector(
                    `.step-card[data-index="${foundIndex}"]`
                );
                if (newEl) {
                    newEl.classList.add('active');
                    newEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                }
            }

            currentActiveIndex = foundIndex;
        }

        animationId = requestAnimationFrame(updateActiveStep);
    }

    // ---- 工具函数 ----
    function formatTime(seconds) {
        if (seconds == null || isNaN(seconds)) return '--:--';
        const m = Math.floor(seconds / 60);
        const s = Math.floor(seconds % 60);
        return m + ':' + (s < 10 ? '0' : '') + s;
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // ---- 启动 ----
    document.addEventListener('DOMContentLoaded', function () {
        initPlayer();
    });

})();
