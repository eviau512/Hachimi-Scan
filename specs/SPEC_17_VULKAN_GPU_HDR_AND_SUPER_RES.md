# 技术规格书 17：基于 Vulkan Compute 的硬件加速 HDR 与亚像素超分管线规范 (Vulkan GPU HDR & Super-Resolution Specification)

> **文档性质**：核心计算摄影与 GPU 硬件加速架构规格书 (Core Computational Photography & GPU Acceleration Specification)  
> **版本**：v1.0 · 2026-10-02  
> **分支**：`gpu-hdr`  
> **制定目标**：全面唤醒 Android 设备旗舰级 GPU（如 Qualcomm Adreno 750 / 830）的通用并行算力，通过 Vulkan Compute 1.1+ 管线重构多帧亚像素超分、极限大光比高光抑制与局部色调映射（LTM），将 50MP 处理延迟从 CPU 的 ~2.8 秒大幅缩减至 400ms 以内，彻底解决室内夜景极端高光（如台灯、发光二极管）过曝死白与视差模糊问题。

---

## 1. 物理机理与架构突破 (Architecture & Physics)

### 1.1 现状瓶颈分析 (CPU Pipeline Bottlenecks)
- **单应性视差局限**：CPU 上的 ORB + 单应性变换矩阵（Homography）属于二维平面投影，无法处理三维真实场景不同纵深（如近处衣物与远处墙面）的视差微抖，导致局部微重影；
- **高光抑制不足**：由于依赖 CameraX 的全局 AE 曝光补偿（`-2 EV`），暗光下被驱动截断，导致台灯等极端点光源依然深陷饱和区（过曝率高达 27.23%）；
- **计算吞吐瓶颈**：在 CPU 上运行 5000 万像素的加权融合与高斯滤波，耗时约 2.8 秒，手机多核高负荷发热。

### 1.2 Vulkan Compute 架构拓扑
```
[Camera2 Manual Capture]
 ├─ Frame 0 (EV 0, Base)
 ├─ Frame 1 (Manual 1/1000s, Extreme Highlight Recovery)
 ├─ Frame 2 (EV 0, Sub-pixel Aux 1)
 └─ Frame 3 (EV 0, Sub-pixel Aux 2)
       │
       ▼ (JNI Native Transfer)
[Vulkan Compute Context (Adreno / Mali)]
       │
       ├─ Pass 1: sub_pixel_warp_accum.comp (2x Canvas 50MP Bicubic Warping & Denoise)
       │   └─ Workgroup: 16x16, Image/Buffer Device Local
       │
       ├─ Pass 2: highlight_graft_hdr.comp (Smooth Hermite Blending with Frame 1)
       │   └─ Un-saturates desk lamp & nightlight cores
       │
       ├─ Pass 3: fast_guided_ltm.comp (Local Tone Mapping, Base/Detail Separation)
       │   └─ Compresses 14 EV dynamic range, eliminates light halos
       │
       └─ Pass 4: s_curve_micro_contrast.comp (Toe Damping & Edge Coring)
       │
       ▼ (Zero-Copy / Staging Readback)
[Upright Rotation & JPEG 95% IMWRITE]
```

---

## 2. 曝光控制契约规范 (Camera2 Exposure Contract)

为了向 Vulkan 管线提供真正包含完整高光纹理的原始像素，连拍帧序列必须实施**强制手动快门策略（Manual Shutter Override）**：

| 帧序号 | 曝光模式 | 快门速度 (`SENSOR_EXPOSURE_TIME`) | ISO (`SENSOR_SENSITIVITY`) | 用途 |
|:---:|:---:|:---:|:---:|:---|
| **Frame 0** | 自动曝光 (AE) | 传感器自动（如 1/17s） | 传感器自动（如 4000） | 空间几何与暗部信噪比基准帧 |
| **Frame 1** | **全手动 (Manual)** | **强制 1/1000s ~ 1/2000s** | **强制锁定 100 ~ 200** | **极限高光帧：确保台灯灯罩、夜灯发光面退出 255 饱和区** |
| **Frame 2** | 自动曝光 (AE) | 传感器自动（恢复基准） | 传感器自动 | 亚像素位移重构辅助帧 1 |
| **Frame 3** | 自动曝光 (AE) | 传感器自动（恢复基准） | 传感器自动 | 亚像素位移重构辅助帧 2 |

---

## 3. Vulkan Compute Shader 阶段算法数学模型

### 3.1 阶段一：亚像素双三次插值与加权累加 (`sub_pixel_warp_accum.comp`)
- **线程模型**：每个 Workgroup 大小为 $16 \times 16$，总网格覆盖 $8192 \times 6144$ 个输出像素；
- **重构坐标映射**：
  $$\begin{pmatrix} x' \\ y' \\ 1 \end{pmatrix} = H_{2\times}^{-1} \begin{pmatrix} X_{\text{50M}} \\ Y_{\text{50M}} \\ 1 \end{pmatrix}$$
- **双三次插值（Bicubic Kernel）**：
  采用 Catmull-Rom 样条基函数对 16 个临近样本进行 GPU 硬件纹理加速采样，最大限度恢复超越单帧奈奎斯特极限的高频光学微纹理；
- **时域高斯权重计算**：
  $$W_i = \exp\left(-\frac{\|I_i(x', y') - I_0(x, y)\|^2}{2 \sigma_{\text{color}}^2}\right)$$
  在 GPU 局部寄存器中直接计算，彻底告别 CPU 查表。

### 3.2 阶段二：极限高光 Hermite 融合 (`highlight_graft_hdr.comp`)
针对 Frame 1（极短曝光帧），其亮度缩放倍率增益为：
$$\text{Gain} = \frac{T_0 \cdot \text{ISO}_0}{T_1 \cdot \text{ISO}_1}$$
对于基准图亮度 $Y_0 \ge 215$ 的区域，应用三次 Hermite 平滑混合阶梯：
$$t = \text{clamp}\left(\frac{Y_0 - 215.0}{245.0 - 215.0}, 0.0, 1.0\right)$$
$$\alpha = t^2 (3.0 - 2.0 t)$$
$$C_{\text{final}} = (1.0 - \alpha) \cdot C_{\text{accum}} + \alpha \cdot \text{clamp}(C_{\text{short}} \cdot \text{Gain}, 0, 255)$$
将台灯灯罩内部的物理轮廓精准嫁接回 50MP 画布，边缘完全无缝。

### 3.3 阶段三：基于导向滤波的局部色调映射 (`fast_guided_ltm.comp`)
- **高低频分离**：
  通过快速导向滤波（Fast Guided Filter）将融合后的高动态图分解为：
  - **基础层（Base Layer $I_{\text{base}}$）**：全局光照分布；
  - **细节层（Detail Layer $I_{\text{detail}} = I - I_{\text{base}}$）**：物理表面高频纹理。
- **大光比对数压缩**：
  $$I_{\text{base\_compressed}} = \frac{\log(1.0 + \mu \cdot I_{\text{base}})}{\log(1.0 + \mu)}$$
  其中 $\mu = 8.0$。此操作只压缩空间大光比，彻底压制台灯周围发散的光晕（Halo），同时拉起暗部墙面；
- **细节重合成**：
  $$I_{\text{LTM}} = I_{\text{base\_compressed}} + \beta \cdot I_{\text{detail}}$$
  其中 $\beta = 1.15$（微反差轻度增益）。

### 3.4 阶段四：S 曲线黑位收敛 (`s_curve_contrast.comp`)
- **暗部黑电平平滑沉降**：
  针对极暗阴影区（$Y \le 28.0$），执行：
  $$u = \frac{Y}{28.0}, \quad Y_{\text{tone}} = Y \cdot u^{0.65}$$
  消灭暗光紫红散粒底噪，重塑深邃通透的夜景纯黑底色。

---

## 4. 容错与无缝降级策略 (Fallback Strategy)

1. **环境探针检测**：
   在 JNI 初始化时探测 `vkCreateInstance`、物理设备（`vkEnumeratePhysicalDevices`）以及是否支持 `VK_QUEUE_COMPUTE_BIT`；
2. **安全降级机制**：
   若设备驱动不支持 Vulkan 1.1 或初始化失败（例如特定模拟器或极老旧设备），引擎**自动无缝回退（Fallback）至 CPU OpenMP 管线（`burst_fusion.cpp`）**，保证 App 绝对不崩溃、业务逻辑 100% 连续。

---

## 5. 验收标准与性能指标 (KPIs)

| 评估维度 | CPU OpenMP 基准 (v7) | Vulkan GPU 目标 (v8 / gpu-hdr) |
|:---|:---:|:---:|
| **50MP 处理总延迟** | ~2800 ms | **≤ 450 ms** |
| **极端高光过曝率（台灯区域）** | 27.23% (死白光球) | **≤ 4.5% (清晰可见灯罩轮廓)** |
| **插座发光体过曝率** | 15.68% (不规则亮斑) | **≤ 3.0% (清晰呈现环形夜灯)** |
| **内存额外开销** | ~400 MB (Java/Native) | **≤ 180 MB (Vulkan Device Local)** |
| **CPU 峰值占用率** | 8 核心 100% 满载 | **单核调度，CPU 占用 ≤ 15%** |
