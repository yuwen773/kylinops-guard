# 演示视频元数据

> **本文件必填，强制入 Git**。视频文件本身是否入 Git 视体积而定（参见 plan 顶部"Git 入库策略"）。
> **本模板**：录制完成后用真实数值替换 `<占位符>`。

## 文件信息

- **文件名**：`demo-recording.mp4`
- **时长**：`<实际秒数>` 秒（≤ 420）
- **分辨率**：`<如 1920x1080>`
- **帧率**：`<如 30fps>`
- **码率**：`<如 8Mbps>`
- **文件大小**：`<实际 MB>` MB
- **存放位置**：
  - Git 路径：`<入 Git 时填 "docs/demo/video/demo-recording.mp4"；否则填 "未入 Git（仅最终提交包）">`
  - 备选位置：`<Releases URL / 团队共享盘 URL / 比赛提交包内路径>`

## 校验

- **SHA256**：`<实际校验值>`
- **MD5**（可选）：`<实际校验值>`

## 入库状态

- [ ] 已入 Git（≤ 50MB 时勾选）
- [ ] 仅在最终提交压缩包中提供（> 50MB 时勾选）
- [ ] 已上传到 Releases / 共享盘
- [ ] 已包含在最终比赛提交包中

---

## 录制元数据填写指引

### 如何计算 SHA256（Git Bash）

```bash
sha256sum docs/demo/video/demo-recording.mp4
```

输出形如 `<hash>  docs/demo/video/demo-recording.mp4`，把 hash 复制到上面。

### 如何提取视频时长 / 分辨率 / 帧率 / 码率

```bash
# 时长（秒）
ffprobe -v error -show_entries format=duration \
    -of default=noprint_wrappers=1:nokey=1 \
    docs/demo/video/demo-recording.mp4

# 分辨率 + 帧率
ffprobe -v error -select_streams v:0 \
    -show_entries stream=width,height,r_frame_rate \
    -of default=noprint_wrappers=1 \
    docs/demo/video/demo-recording.mp4

# 码率
ffprobe -v error -show_entries format=bit_rate \
    -of default=noprint_wrappers=1:nokey=1 \
    docs/demo/video/demo-recording.mp4
```

### 如何计算文件大小

```bash
du -m docs/demo/video/demo-recording.mp4 | cut -f1
```

输出形如 `<size_in_MB>`。

### 入库决策

```bash
FILE_SIZE_MB=$(du -m docs/demo/video/demo-recording.mp4 | cut -f1)
if [ "$FILE_SIZE_MB" -lt 50 ]; then
    echo "可入 Git（< 50MB）"
    git add docs/demo/video/demo-recording.mp4
else
    echo "不入 Git（≥ 50MB），仅元数据入 Git"
    # 不 git add .mp4；只 git add demo-recording.md
fi
```

---

## 已知风险与处理

- **录制中演示失败** → 重录该场景段，不回退代码（参考 `demo-checklist.md` §录制中应急）
- **录制后视频文件丢失** → 通过 SHA256 校验找回；如无法找回，立即重录
- **演示数据未 seeded** → 录制前必须跑 `sudo bash deploy/scripts/seed-demo.sh`
